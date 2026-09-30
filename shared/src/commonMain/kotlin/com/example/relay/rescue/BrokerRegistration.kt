package com.example.relay.rescue

/**
 * Canonical proof-of-possession payload for Broker device registration.
 *
 * The domain separator prevents a valid upload or receipt signature from being replayed as a
 * registration proof. Length-prefixing keeps the two caller-controlled fields unambiguous.
 */
fun brokerDeviceRegistrationBytes(deviceKeyId: String, publicKeyBase64: String): ByteArray = buildString {
    listOf(
        "RelayBrokerDeviceRegistration/v1",
        deviceKeyId,
        publicKeyBase64,
    ).forEach { field -> append(field.encodeToByteArray().size).append(':').append(field) }
}.encodeToByteArray()
