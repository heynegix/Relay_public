package com.example.relay.rescue

/**
 * Multiplatform replacement for `@Synchronized`.
 * JVM/Android: delegates to `kotlin.synchronized`.
 * iOS/Native: delegates to `platform.Foundation.NSRecursiveLock`.
 */
internal expect fun <T> platformSynchronized(lock: Any, block: () -> T): T
