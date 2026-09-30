package com.example.relay.domain

import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun monotonicMillis(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

actual fun randomUuid(): String = NSUUID().UUIDString()

actual fun defaultRelayClock(): RelayClock = SystemRelayClock()
