package com.example.relay.gateway

import com.example.relay.rescue.RescueCryptography

/**
 * Out-of-band trust bootstrap for LAN gateway discovery.
 *
 * LAN discovery ([UdpGatewayDiscovery]) is deliberately *not* a trust mechanism: any host on the
 * segment can broadcast a beacon. This codec lets an operator provision a specific gateway's
 * verified identity to a device through a scanned QR payload or a typed manual code. The device
 * then upgrades a discovered beacon from "unverified" to "trusted" only when the beacon's
 * advertised identity matches an enrolled token, and reuses the pinned [manifestFingerprint] to
 * verify the shelter public-key manifest fetched over HTTPS.
 *
 * The transport is a compact, self-describing text payload so it survives any QR encoder and can be
 * transcribed by hand. Decoding is fail-closed: an unknown version, an oversized payload, a bad
 * checksum, or any invalid field yields [GatewayEnrollmentResult.Rejected], never a partial token.
 */
const val GATEWAY_ENROLLMENT_SCHEME: String = "relay-gw"
const val GATEWAY_ENROLLMENT_VERSION: Int = 1

/** Hard upper bound so a malicious QR cannot force large allocations before validation. */
const val GATEWAY_ENROLLMENT_MAX_PAYLOAD_BYTES: Int = 512

/**
 * Verified identity of a gateway provisioned out of band. [host] is only a connection hint (LAN
 * DHCP addresses change), so it is intentionally excluded from the identity match in
 * [GatewayEnrollmentStore.decisionFor]; [gatewayId], [scheme], [port] and [shelterId] are pinned.
 */
data class GatewayEnrollmentToken(
    val gatewayId: String,
    val shelterId: String,
    val host: String,
    val port: Int,
    val scheme: String,
    val manifestFingerprint: String,
)

enum class GatewayEnrollmentRejection {
    MALFORMED,
    UNSUPPORTED_VERSION,
    PAYLOAD_TOO_LARGE,
    CHECKSUM_MISMATCH,
    INVALID_FIELD,
}

sealed interface GatewayEnrollmentResult {
    data class Enrolled(val token: GatewayEnrollmentToken) : GatewayEnrollmentResult
    data class Rejected(val reason: GatewayEnrollmentRejection) : GatewayEnrollmentResult
}

/** Result of matching a discovered beacon against provisioned trust material. */
enum class GatewayTrustDecision {
    /** Beacon identity matches an enrolled token; safe to deliver as a verified gateway. */
    TRUSTED,

    /** No enrolled token for this gateway; delivery remains anonymous/unverified. */
    UNVERIFIED,

    /** An enrolled token exists for this gatewayId but the beacon contradicts it (possible spoof). */
    REJECTED,
}

object GatewayEnrollmentCodec {
    private const val OUTER_DELIMITER = ':'
    private const val INNER_DELIMITER = '|'
    private const val CHECKSUM_LENGTH = 8
    private const val FINGERPRINT_LENGTH = 64
    private const val MAX_ID_LENGTH = 128
    private const val MAX_HOST_LENGTH = 253

    /** Encodes a verified token into the scannable/typeable payload with an integrity checksum. */
    fun encodeQrPayload(token: GatewayEnrollmentToken): String {
        val normalized = normalize(token)
        requireNotNull(validate(normalized)) { "cannot encode an invalid enrollment token" }
        val body = body(normalized)
        return body + OUTER_DELIMITER + checksum(body)
    }

    /** Fail-closed decode of a scanned QR payload. */
    fun decodeQrPayload(payload: String): GatewayEnrollmentResult {
        val trimmed = payload.trim()
        if (trimmed.encodeToByteArray().size > GATEWAY_ENROLLMENT_MAX_PAYLOAD_BYTES) {
            return GatewayEnrollmentResult.Rejected(GatewayEnrollmentRejection.PAYLOAD_TOO_LARGE)
        }
        val parts = trimmed.split(OUTER_DELIMITER)
        if (parts.size != 4) return rejected(GatewayEnrollmentRejection.MALFORMED)
        val (scheme, versionText, inner, providedChecksum) = parts
        if (scheme != GATEWAY_ENROLLMENT_SCHEME) return rejected(GatewayEnrollmentRejection.MALFORMED)
        val version = versionText.toIntOrNull() ?: return rejected(GatewayEnrollmentRejection.MALFORMED)
        if (version != GATEWAY_ENROLLMENT_VERSION) return rejected(GatewayEnrollmentRejection.UNSUPPORTED_VERSION)
        val body = scheme + OUTER_DELIMITER + versionText + OUTER_DELIMITER + inner
        if (!constantTimeEquals(checksum(body), providedChecksum.lowercase())) {
            return rejected(GatewayEnrollmentRejection.CHECKSUM_MISMATCH)
        }
        val fields = inner.split(INNER_DELIMITER)
        if (fields.size != 6) return rejected(GatewayEnrollmentRejection.MALFORMED)
        val port = fields[3].toIntOrNull() ?: return rejected(GatewayEnrollmentRejection.INVALID_FIELD)
        val token = GatewayEnrollmentToken(
            gatewayId = fields[0],
            shelterId = fields[1],
            host = fields[2],
            port = port,
            scheme = fields[4],
            manifestFingerprint = fields[5],
        )
        return validate(token)?.let { GatewayEnrollmentResult.Enrolled(it) }
            ?: rejected(GatewayEnrollmentRejection.INVALID_FIELD)
    }

    /**
     * Manual-entry path: the operator types the identity fields and the fingerprint (which may be
     * grouped for readability by [formatManualFingerprint]). Normalization strips block separators
     * before the same strict validation as the QR path.
     */
    fun parseManual(
        gatewayId: String,
        shelterId: String,
        host: String,
        port: Int,
        scheme: String,
        fingerprintText: String,
    ): GatewayEnrollmentResult {
        val token = GatewayEnrollmentToken(
            gatewayId = gatewayId.trim(),
            shelterId = shelterId.trim(),
            host = host.trim(),
            port = port,
            scheme = scheme.trim().lowercase(),
            manifestFingerprint = normalizeFingerprint(fingerprintText),
        )
        return validate(token)?.let { GatewayEnrollmentResult.Enrolled(it) }
            ?: rejected(GatewayEnrollmentRejection.INVALID_FIELD)
    }

    /** Groups a 64-hex fingerprint into 4-character blocks so an operator can transcribe it. */
    fun formatManualFingerprint(fingerprint: String): String =
        normalizeFingerprint(fingerprint).chunked(4).joinToString("-")

    private fun normalizeFingerprint(value: String): String =
        value.filterNot { it == '-' || it == ':' || it.isWhitespace() }.lowercase()

    private fun normalize(token: GatewayEnrollmentToken): GatewayEnrollmentToken = token.copy(
        gatewayId = token.gatewayId.trim(),
        shelterId = token.shelterId.trim(),
        host = token.host.trim(),
        scheme = token.scheme.trim().lowercase(),
        manifestFingerprint = normalizeFingerprint(token.manifestFingerprint),
    )

    private fun body(token: GatewayEnrollmentToken): String {
        val inner = listOf(
            token.gatewayId,
            token.shelterId,
            token.host,
            token.port.toString(),
            token.scheme,
            token.manifestFingerprint,
        ).joinToString(INNER_DELIMITER.toString())
        return GATEWAY_ENROLLMENT_SCHEME + OUTER_DELIMITER + GATEWAY_ENROLLMENT_VERSION + OUTER_DELIMITER + inner
    }

    private fun checksum(body: String): String =
        RescueCryptography.sha256Hex(body.encodeToByteArray()).take(CHECKSUM_LENGTH)

    /** Returns the normalized token when every field is valid, otherwise null. */
    private fun validate(token: GatewayEnrollmentToken): GatewayEnrollmentToken? {
        if (!isValidId(token.gatewayId)) return null
        if (!isValidId(token.shelterId)) return null
        if (!isValidHost(token.host)) return null
        if (token.port !in 1..65_535) return null
        if (token.scheme != "http" && token.scheme != "https") return null
        if (!isValidFingerprint(token.manifestFingerprint)) return null
        return token
    }

    private fun isValidId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH &&
            value.none { it == OUTER_DELIMITER || it == INNER_DELIMITER } &&
            value.all { it.isLetterOrDigitAscii() || it == '.' || it == '_' || it == '-' }

    private fun isValidHost(value: String): Boolean =
        value.length in 1..MAX_HOST_LENGTH &&
            value.none { it == OUTER_DELIMITER || it == INNER_DELIMITER } &&
            value.all { it.isLetterOrDigitAscii() || it == '.' || it == '-' }

    private fun isValidFingerprint(value: String): Boolean =
        value.length == FINGERPRINT_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun Char.isLetterOrDigitAscii(): Boolean =
        this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    /** Length-independent comparison so a checksum test does not leak timing on the prefix. */
    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }

    private fun rejected(reason: GatewayEnrollmentRejection): GatewayEnrollmentResult.Rejected =
        GatewayEnrollmentResult.Rejected(reason)
}

/**
 * In-memory catalog of enrolled gateway identities. A device keeps one instance and asks it whether
 * a freshly discovered beacon may be trusted before delivering to it.
 */
class GatewayEnrollmentStore(initial: Collection<GatewayEnrollmentToken> = emptyList()) {
    private val byGatewayId = HashMap<String, GatewayEnrollmentToken>()

    init {
        initial.forEach { enroll(it) }
    }

    /** Records (or replaces) the trusted identity for a gateway. */
    fun enroll(token: GatewayEnrollmentToken) {
        byGatewayId[token.gatewayId] = token
    }

    fun forget(gatewayId: String) {
        byGatewayId.remove(gatewayId)
    }

    fun enrolledTokens(): List<GatewayEnrollmentToken> = byGatewayId.values.toList()

    /** Returns the enrolled token only when the discovered beacon is fully trusted. */
    fun trustedTokenFor(discovered: DiscoveredGateway): GatewayEnrollmentToken? =
        byGatewayId[discovered.gatewayId]?.takeIf { it.trustDecisionFor(discovered) == GatewayTrustDecision.TRUSTED }

    fun decisionFor(discovered: DiscoveredGateway): GatewayTrustDecision =
        byGatewayId[discovered.gatewayId]?.trustDecisionFor(discovered) ?: GatewayTrustDecision.UNVERIFIED
}

/**
 * Compares a discovered beacon against this enrolled identity. [host] is not compared because LAN
 * addresses change; a matching gatewayId with a contradicting scheme, port, or shelter is treated
 * as a spoofing attempt and [GatewayTrustDecision.REJECTED].
 */
fun GatewayEnrollmentToken.trustDecisionFor(discovered: DiscoveredGateway): GatewayTrustDecision {
    if (discovered.gatewayId != gatewayId) return GatewayTrustDecision.UNVERIFIED
    if (discovered.scheme.lowercase() != scheme) return GatewayTrustDecision.REJECTED
    if (discovered.port != port) return GatewayTrustDecision.REJECTED
    val advertisedShelter = discovered.shelterId
    if (advertisedShelter != null && advertisedShelter != shelterId) return GatewayTrustDecision.REJECTED
    return GatewayTrustDecision.TRUSTED
}
