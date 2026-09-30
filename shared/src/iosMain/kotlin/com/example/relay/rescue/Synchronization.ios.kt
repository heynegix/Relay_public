package com.example.relay.rescue

import platform.Foundation.NSRecursiveLock

private val syncLock = NSRecursiveLock()

internal actual fun <T> platformSynchronized(lock: Any, block: () -> T): T {
    syncLock.lock()
    try {
        return block()
    } finally {
        syncLock.unlock()
    }
}
