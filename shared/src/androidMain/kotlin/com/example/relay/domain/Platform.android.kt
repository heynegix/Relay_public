package com.example.relay.domain

import java.util.UUID

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun monotonicMillis(): Long = android.os.SystemClock.elapsedRealtime()

actual fun randomUuid(): String = UUID.randomUUID().toString()

actual fun defaultRelayClock(): RelayClock = SystemRelayClock()
