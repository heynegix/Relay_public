package com.example.relay.pcgateway

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes Gateway-owned SQLite writes for one database file.
 *
 * WAL allows readers to proceed while a writer is active, but it still permits only one writer.
 * GatewayStore, GatewayAccessStore, and rescue persistence intentionally use separate JDBC
 * connections, so they share this coordinator before starting a write transaction.  The sibling
 * lock file also coordinates two current Gateway processes that accidentally point at the same
 * development database.
 */
internal class GatewaySqliteWriteCoordinator private constructor(private val databasePath: Path) {
    private val processLock = ReentrantLock(true)
    private val reentrancyDepth = ThreadLocal.withInitial { 0 }
    private val lockChannel: FileChannel

    init {
        databasePath.parent?.let(Files::createDirectories)
        val lockName = "${databasePath.fileName}.relay-writer.lock"
        lockChannel = FileChannel.open(databasePath.resolveSibling(lockName), CREATE, WRITE)
    }

    /** Executes [block] while this Gateway owns the database writer slot. */
    fun <T> write(block: () -> T): T = processLock.withLock {
        if (reentrancyDepth.get() > 0) {
            block()
        } else {
            lockChannel.lock().use {
                reentrancyDepth.set(1)
                try {
                    block()
                } finally {
                    reentrancyDepth.remove()
                }
            }
        }
    }

    companion object {
        private val coordinators = ConcurrentHashMap<Path, GatewaySqliteWriteCoordinator>()

        fun forDatabase(dbPath: String): GatewaySqliteWriteCoordinator {
            val normalized = Path.of(dbPath).toAbsolutePath().normalize()
            return coordinators.computeIfAbsent(normalized, ::GatewaySqliteWriteCoordinator)
        }
    }
}
