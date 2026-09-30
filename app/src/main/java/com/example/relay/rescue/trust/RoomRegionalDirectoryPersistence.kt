package com.example.relay.rescue.trust

import com.example.relay.data.local.RegionalDirectoryEntity
import com.example.relay.data.local.RelayDatabase

/** Room/SQLCipher-backed persistence for verified regional directory state. */
class RoomRegionalDirectoryPersistence(
    private val database: RelayDatabase,
) : RegionalDirectoryPersistence {
    private val dao = database.regionalDirectoryDao()

    override fun <T> transaction(block: () -> T): T = database.runInTransaction<T> { block() }

    override fun find(regionId: String): StoredRegionalDirectory? = dao.find(regionId)?.toStored()

    override fun all(): List<StoredRegionalDirectory> = dao.all().map { it.toStored() }

    override fun replace(record: StoredRegionalDirectory): Boolean =
        dao.replace(record.toEntity()) != -1L

    private fun RegionalDirectoryEntity.toStored() = StoredRegionalDirectory(
        regionId = regionId,
        generation = generation,
        directoryDigest = directoryDigest,
        directoryJson = directoryJson,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )

    private fun StoredRegionalDirectory.toEntity() = RegionalDirectoryEntity(
        regionId = regionId,
        generation = generation,
        directoryDigest = directoryDigest,
        directoryJson = directoryJson,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )
}
