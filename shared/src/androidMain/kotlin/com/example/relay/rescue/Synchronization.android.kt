package com.example.relay.rescue

internal actual fun <T> platformSynchronized(lock: Any, block: () -> T): T =
    synchronized(lock) { block() }
