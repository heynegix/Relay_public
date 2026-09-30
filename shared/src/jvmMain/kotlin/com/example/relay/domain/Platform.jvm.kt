package com.example.relay.domain

import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun defaultRelayClock(): RelayClock = SystemRelayClock()
