package com.example.relay.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GatewayEnrollmentCodecTest {
    private val fingerprint = "a".repeat(64)
    private val token = GatewayEnrollmentToken(
        gatewayId = "pc-gateway-example-01",
        shelterId = "shelter-example-01",
        host = "192.168.50.20",
        port = 8443,
        scheme = "https",
        manifestFingerprint = fingerprint,
    )

    private fun beacon(
        gatewayId: String = token.gatewayId,
        port: Int = token.port,
        scheme: String = token.scheme,
        shelterId: String? = token.shelterId,
        host: String = "10.0.0.99",
    ) = DiscoveredGateway(host = host, port = port, gatewayId = gatewayId, scheme = scheme, shelterId = shelterId)

    @Test
    fun qrPayloadRoundTrips() {
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token)
        val result = assertIs<GatewayEnrollmentResult.Enrolled>(GatewayEnrollmentCodec.decodeQrPayload(payload))
        assertEquals(token, result.token)
    }

    @Test
    fun manualEntryRoundTripsThroughGroupedFingerprint() {
        val grouped = GatewayEnrollmentCodec.formatManualFingerprint(fingerprint)
        val result = assertIs<GatewayEnrollmentResult.Enrolled>(
            GatewayEnrollmentCodec.parseManual(
                gatewayId = "  ${token.gatewayId}  ",
                shelterId = token.shelterId,
                host = token.host,
                port = token.port,
                scheme = "HTTPS",
                fingerprintText = grouped,
            ),
        )
        assertEquals(token, result.token)
    }

    @Test
    fun tamperedChecksumIsRejected() {
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token)
        val tampered = payload.dropLast(1) + if (payload.last() == '0') '1' else '0'
        val result = assertIs<GatewayEnrollmentResult.Rejected>(GatewayEnrollmentCodec.decodeQrPayload(tampered))
        assertEquals(GatewayEnrollmentRejection.CHECKSUM_MISMATCH, result.reason)
    }

    @Test
    fun unknownVersionIsRejected() {
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token)
        // Rewrite only the version segment and recompute nothing: version check runs before checksum.
        val bumped = payload.replaceFirst("$GATEWAY_ENROLLMENT_SCHEME:$GATEWAY_ENROLLMENT_VERSION:", "$GATEWAY_ENROLLMENT_SCHEME:2:")
        val result = assertIs<GatewayEnrollmentResult.Rejected>(GatewayEnrollmentCodec.decodeQrPayload(bumped))
        assertEquals(GatewayEnrollmentRejection.UNSUPPORTED_VERSION, result.reason)
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val huge = GatewayEnrollmentCodec.encodeQrPayload(token) + "&".repeat(GATEWAY_ENROLLMENT_MAX_PAYLOAD_BYTES)
        val result = assertIs<GatewayEnrollmentResult.Rejected>(GatewayEnrollmentCodec.decodeQrPayload(huge))
        assertEquals(GatewayEnrollmentRejection.PAYLOAD_TOO_LARGE, result.reason)
    }

    @Test
    fun foreignSchemeIsMalformed() {
        val result = assertIs<GatewayEnrollmentResult.Rejected>(
            GatewayEnrollmentCodec.decodeQrPayload("other-scheme:1:a|b|c|8443|https|$fingerprint:00000000"),
        )
        assertEquals(GatewayEnrollmentRejection.MALFORMED, result.reason)
    }

    @Test
    fun invalidFingerprintFromManualIsRejected() {
        val result = assertIs<GatewayEnrollmentResult.Rejected>(
            GatewayEnrollmentCodec.parseManual(
                gatewayId = token.gatewayId,
                shelterId = token.shelterId,
                host = token.host,
                port = token.port,
                scheme = token.scheme,
                fingerprintText = "not-hex-and-too-short",
            ),
        )
        assertEquals(GatewayEnrollmentRejection.INVALID_FIELD, result.reason)
    }

    @Test
    fun matchingBeaconIsTrustedEvenWhenHostChanged() {
        val store = GatewayEnrollmentStore(listOf(token))
        assertEquals(GatewayTrustDecision.TRUSTED, store.decisionFor(beacon(host = "172.16.9.5")))
        assertNotNull(store.trustedTokenFor(beacon()))
    }

    @Test
    fun unknownGatewayIsUnverified() {
        val store = GatewayEnrollmentStore(listOf(token))
        assertEquals(GatewayTrustDecision.UNVERIFIED, store.decisionFor(beacon(gatewayId = "someone-else")))
        assertNull(store.trustedTokenFor(beacon(gatewayId = "someone-else")))
    }

    @Test
    fun contradictingSchemePortOrShelterIsRejected() {
        val store = GatewayEnrollmentStore(listOf(token))
        assertEquals(GatewayTrustDecision.REJECTED, store.decisionFor(beacon(scheme = "http")))
        assertEquals(GatewayTrustDecision.REJECTED, store.decisionFor(beacon(port = 8080)))
        assertEquals(GatewayTrustDecision.REJECTED, store.decisionFor(beacon(shelterId = "shelter-somewhere-else")))
        // A legacy beacon that omits its shelter still matches on the pinned identity.
        assertEquals(GatewayTrustDecision.TRUSTED, store.decisionFor(beacon(shelterId = null)))
        assertNull(store.trustedTokenFor(beacon(scheme = "http")))
    }
}
