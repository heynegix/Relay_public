package com.example.relay.gateway

import com.example.relay.rescue.ShelterManifestEnrollment
import com.example.relay.rescue.ShelterPublicKeys

/**
 * Token-driven shelter manifest enrollment. A verified [GatewayEnrollmentToken] already pins the
 * shelter's [GatewayEnrollmentToken.manifestFingerprint], [GatewayEnrollmentToken.shelterId], and
 * connection hint; this bridge feeds those directly into [ShelterManifestEnrollment] so the fetched
 * manifest is verified against the same out-of-band material the operator scanned, and refused if it
 * advertises a different shelter.
 */
fun ShelterManifestEnrollment.enrollShelterFor(token: GatewayEnrollmentToken): ShelterPublicKeys =
    enroll(
        host = token.host,
        port = token.port,
        expectedFingerprint = token.manifestFingerprint,
        expectedShelterId = token.shelterId,
    )
