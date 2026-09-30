package com.example.relay.pcgateway.e2e

import com.example.relay.pcgateway.GatewayConfig
import com.example.relay.pcgateway.GatewayProfile
import com.example.relay.pcgateway.GatewayRescueKeyStatus
import com.example.relay.pcgateway.GatewayStore
import com.example.relay.pcgateway.GsiTileCache
import com.example.relay.pcgateway.OfficialInformationService
import com.example.relay.pcgateway.gatewayModule
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Path

/**
 * Launches the REAL PC Gateway operator console (the static SPA in resources/web driven by the
 * real Ktor [gatewayModule] routes) so a Playwright browser test can drive it end to end.
 *
 * This is a test-only harness. It intentionally uses the DEVELOPMENT profile so rescue keys are
 * generated locally, binds to loopback (which makes the operator console management-source
 * allowed), bootstraps one admin account, and seeds a couple of decrypted rescue requests through
 * the same [RescueIntakeService.ingest] path production uses. Nothing here weakens production: it
 * runs only from `:pc-gateway:staffConsoleE2eServer` against throwaway temp files.
 *
 * Required environment (Playwright sets these in playwright.config.ts):
 *   RELAY_PROFILE=development
 *   RELAY_GATEWAY_HOST=127.0.0.1
 *   RELAY_GATEWAY_PORT=<port>
 *   RELAY_GATEWAY_DB=<temp .db>
 *   RELAY_RESCUE_KEY_FILE=<temp keys.json>
 *   RELAY_OFFLINE_MAP_DIR=<temp dir>
 *   RELAY_OFFICIAL_INFO_CACHE=<temp .json>
 *   RELAY_E2E_STAFF_USERNAME / RELAY_E2E_STAFF_PASSWORD (the login the spec uses)
 */
object StaffConsoleE2eServer {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = GatewayConfig()
        check(config.profile == GatewayProfile.DEVELOPMENT) {
            "StaffConsoleE2eServer must run with RELAY_PROFILE=development"
        }

        val store = GatewayStore(config)
        val access = store.accessStore()

        val username = System.getenv("RELAY_E2E_STAFF_USERNAME")?.trim().orEmpty()
        val password = System.getenv("RELAY_E2E_STAFF_PASSWORD").orEmpty()
        require(username.isNotEmpty() && password.isNotEmpty()) {
            "RELAY_E2E_STAFF_USERNAME and RELAY_E2E_STAFF_PASSWORD must be set"
        }
        val created = access.bootstrapAdmin(username, password, source = "e2e_bootstrap")
        println(if (created) "E2E: bootstrapped staff admin '$username'" else "E2E: staff admin already present")

        val rescueKeys = RescueKeyStore(Path.of(config.rescueKeyPath), config.shelterId).loadOrCreate()
        val intake = RescueIntakeService(
            shelterId = config.shelterId,
            recipientPrivateKey = rescueKeys.recipientPrivateKey,
            shelterSigningPrivateKey = rescueKeys.receiptSigningPrivateKey,
            persistence = store.rescuePersistence(),
        )
        val seeded = seedRequests(intake, config.shelterId, rescueKeys.manifest.recipientPublicKey)
        println("E2E: seeded $seeded rescue request(s)")

        val offlineMap = GsiTileCache(Path.of(config.offlineMapPath))
        val officialInformation = OfficialInformationService(Path.of(config.officialInfoCachePath))

        println("E2E: staff console listening on http://${config.host}:${config.port}/")
        embeddedServer(Netty, host = config.host, port = config.port) {
            gatewayModule(
                config,
                store,
                rescueManifest = rescueKeys.manifest,
                rescueBleReady = false,
                rescueDeliveryReady = true,
                rescueIntakeService = intake,
                offlineMap = offlineMap,
                officialInformation = officialInformation,
                rescueKeyStatus = GatewayRescueKeyStatus.valid(
                    expiresAtEpochMillis = rescueKeys.manifest.validUntilEpochMillis,
                    warning = false,
                ),
            )
        }.start(wait = true)
    }

    /** Seeds requests through the real decrypt/dedup/store path, exactly as broker-relayed traffic. */
    private fun seedRequests(
        intake: RescueIntakeService,
        shelterId: String,
        recipientPublicKey: com.example.relay.rescue.RescuePublicKey,
    ): Int {
        val now = System.currentTimeMillis()
        val seeds = listOf(
            RescuePayload(
                requestId = "e2e-immediate-1",
                senderDeviceId = "e2e-device-1",
                destinationShelterId = shelterId,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + 6L * 60 * 60 * 1_000,
                urgency = RescueUrgency.IMMEDIATE,
                personCount = 3,
                conditions = setOf(RescueCondition.LIFE_THREATENING, RescueCondition.INJURED_OR_UNWELL),
                injured = true,
                seriouslyInjured = true,
                trapped = true,
                supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM, RescueSupportNeed.MEDICINE),
                location = RescueLocation(latitude = 0.0, longitude = 0.0, accuracyMeters = 12f, description = "Example Region / Test Site A", capturedAtEpochMillis = now),
                freeText = "1階が倒壊し2名が閉じ込め。呼びかけに応答あり。",
            ),
            RescuePayload(
                requestId = "e2e-support-2",
                senderDeviceId = "e2e-device-2",
                destinationShelterId = shelterId,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + 6L * 60 * 60 * 1_000,
                urgency = RescueUrgency.URGENT,
                personCount = 1,
                conditions = setOf(RescueCondition.SUPPORT_NEEDED),
                elderlyPresent = true,
                supportNeeds = setOf(RescueSupportNeed.WATER, RescueSupportNeed.FOOD),
                location = RescueLocation(latitude = 0.0, longitude = 0.0, accuracyMeters = 30f, description = "Example Region / Test Site B", capturedAtEpochMillis = now),
                freeText = "高齢者が孤立。水と食料の支援を希望。",
            ),
        )
        var accepted = 0
        seeds.forEachIndexed { index, payload ->
            val envelope = RescueCryptography.encrypt(
                payload = payload,
                recipientPublicKey = recipientPublicKey,
                envelopeId = "e2e-env-$index",
            )
            intake.ingest(envelope, carrierId = "e2e-seed-carrier")
            accepted += 1
        }
        return accepted
    }
}
