@file:Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import com.example.relay.gateway.protocol.GatewayRejection
import com.example.relay.gateway.protocol.ReceiptResponse
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIngestResult
import com.example.relay.pcgateway.rescue.RescueOperatorListResponse
import com.example.relay.pcgateway.rescue.RescueStatusChangeRequest
import com.example.relay.pcgateway.rescue.RescueStatusChangeResponse
import com.example.relay.pcgateway.rescue.RescueStatusUpdateResult
import com.example.relay.pcgateway.rescue.toOperatorRequest
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import io.ktor.server.routing.routing

private const val MAX_CONTROL_BODY_BYTES = 16L * 1024

@Serializable data class PairRequest(val code: String, val bridgeId: String, val bridgeName: String)
@Serializable data class PairApproveRequest(val code: String, val bridgeId: String)
@Serializable data class PairRejectRequest(val bridgeId: String, val code: String? = null)
@Serializable data class PairResponse(val paired: Boolean, val token: String? = null, val reason: String? = null)
@Serializable data class StaffLoginRequest(val username: String, val password: String)
@Serializable data class StaffSessionResponse(
    val username: String,
    val role: StaffRole,
    val expiresAtEpochMillis: Long? = null,
)
@Serializable data class CreateStaffAccountRequest(val username: String, val password: String, val role: StaffRole)
@Serializable data class UpdateStaffRoleRequest(val role: StaffRole)
@Serializable data class UpdateStaffDisabledRequest(val disabled: Boolean)
@Serializable data class HealthResponse(
    val status: String,
    val gatewayId: String,
    val database: String,
    val profile: String = "production",
    /** Defaults to false so older clients that omit the field are treated as production. */
    val trainingMode: Boolean = false,
    val lanMode: String = "disabled",
    val anonymousIngress: Boolean = true,
    val remoteManagementEnabled: Boolean = false,
    val legacyAdminKeyEnabled: Boolean = false,
    val bootstrapRequired: Boolean = true,
    val configurationWarnings: List<String> = emptyList(),
    val lanDiscoveryPort: Int = 42888,
    /** Fail-closed until an authenticated local BLE bridge heartbeat is wired. */
    val bleBridgeStatus: String = "unavailable",
    val version: String = "unknown",
    val buildSha: String = "unknown",
    val regionId: String = "global",
    val regionName: String = "Configured region",
    val timezoneId: String = "UTC",
    val locale: String = "en",
    val offlineMapEnabled: Boolean = false,
    val officialInfoEnabled: Boolean = false,
    val shelterId: String = "unknown",
    val recipientKeyId: String? = null,
    val manifestFingerprint: String? = null,
    val rescueIngressReady: Boolean = false,
    val rescueKeyStorage: String = "not_checked",
    val rescueKeyStatus: String = "not_checked",
    val rescueKeyExpiresAtEpochMillis: Long? = null,
    val localPilotIngress: Boolean = false,
    val rescueKeyRotationStatus: String = "manual_reprovisioning_required",
)

@Serializable
data class MessagesListResponse(
    val messages: Int,
    val active: Int,
    /** Legacy aliases; both fields count content verification, not route authentication. */
    val verified: Int,
    val unverified: Int,
    val contentVerified: Int,
    val contentUnverified: Int,
    val authenticatedRoute: Int,
    val anonymousRoute: Int,
    val items: List<MessageSummary>,
)

fun Application.gatewayModule(
    config: GatewayConfig,
    store: GatewayStore,
    /** Maintenance compatibility only. General-user rescue delivery never trusts this unsigned document. */
    rescueManifest: ShelterPublicKeyManifest? = null,
    /** True only after startup verified a root-signed manifest against this PC's local keys. */
    rescueBleReady: Boolean = false,
    /**
     * Allows anonymous encrypted rescue ingress. Production/lab require a verified signed
     * manifest; development may opt in to generated local keys for debug/localDev interoperability.
     */
    rescueDeliveryReady: Boolean = rescueBleReady,
    rescueIntakeService: RescueIntakeService? = null,
    offlineMap: OfflineMapTileCache? = null,
    officialInformation: OfficialInformationService? = null,
    rescueKeyStatus: GatewayRescueKeyStatus = GatewayRescueKeyStatus.notChecked(),
    anonymousLimiter: AnonymousIngressRateLimiter = AnonymousIngressRateLimiter(
        config.maxAnonymousRequestsPerMinute,
        config.maxAnonymousMessagesPerMinute,
        config.maxAnonymousBytesPerMinute,
    ),
) {
    install(ContentNegotiation) { json(GatewayJson) }
    val access = store.accessStore()
    val puerta = store.puertaStore()
    val pilotOperations = store.pilotOperationsStore()
    routing {
        // Avoid presenting an operator console through a reverse proxy until remote management
        // has been explicitly enabled. API endpoints enforce the same boundary independently.
        intercept(ApplicationCallPipeline.Plugins) {
            val staticOperatorPath = call.request.uri.substringBefore('?') in setOf(
                "/", "/index.html", "/app.js", "/app.css", "/legacy",
            )
            if (staticOperatorPath && !config.managementSourceAllowed(call.remoteSource())) {
                access.audit(null, null, "AUTH_REJECTED", "REMOTE_OPERATOR_UI_DISABLED", call.remoteSource())
                call.respond(HttpStatusCode.NotFound)
                finish()
            }
        }
        get("/api/health") {
            call.respond(
                HealthResponse(
                    status = if (access.bootstrapRequired()) "bootstrap_required" else "ok",
                    gatewayId = config.gatewayId,
                    database = "ready",
                    profile = config.profile.name.lowercase(),
                    trainingMode = config.trainingMode,
                    lanMode = config.lanMode.name.lowercase(),
                    anonymousIngress = config.anonymousIngressEnabled,
                    remoteManagementEnabled = config.remoteManagementEnabled,
                    legacyAdminKeyEnabled = config.legacyAdminKeyEnabled,
                    bootstrapRequired = access.bootstrapRequired(),
                    configurationWarnings = config.configurationWarnings + listOfNotNull(rescueKeyStatus.warningCode),
                    lanDiscoveryPort = config.lanDiscoveryPort,
                    bleBridgeStatus = if (rescueBleReady) "awaiting_sidecar" else "not_ready",
                    version = config.version,
                    buildSha = config.buildSha,
                    regionId = config.regionalProfile.regionId,
                    regionName = config.regionalProfile.displayName,
                    timezoneId = config.regionalProfile.timezoneId,
                    locale = config.regionalProfile.defaultLocale,
                    offlineMapEnabled = config.regionalProfile.map.enabled,
                    officialInfoEnabled = config.regionalProfile.officialInfo.enabled,
                    shelterId = config.shelterId,
                    recipientKeyId = config.rescueRecipientKeyId,
                    manifestFingerprint = config.rescueManifestFingerprint,
                    rescueIngressReady = rescueIntakeService != null && rescueDeliveryReady && config.anonymousIngressEnabled,
                    rescueKeyStorage = rescueKeyStatus.storage,
                    rescueKeyStatus = rescueKeyStatus.status,
                    rescueKeyExpiresAtEpochMillis = rescueKeyStatus.expiresAtEpochMillis,
                    localPilotIngress = config.localPilotIngressEnabled && rescueDeliveryReady && rescueManifest != null,
                ),
            )
        }
        post("/api/auth/login") {
            if (!config.managementSourceAllowed(call.remoteSource())) {
                access.audit(null, null, "AUTH_REJECTED", "REMOTE_MANAGEMENT_DISABLED", call.remoteSource())
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("reason" to "remote_management_disabled"))
            }
            val request = runCatching { call.receive<StaffLoginRequest>() }.getOrElse {
                access.audit(null, null, "LOGIN", "MALFORMED", call.remoteSource())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_login"))
            }
            when (val result = access.login(request.username, request.password, call.remoteSource(), config.sessionTtlMillis)) {
                is LoginResult.Success -> {
                    call.appendSessionCookie(config, result.session)
                    call.respond(StaffSessionResponse(result.staff.username, result.staff.role, result.session.expiresAtEpochMillis))
                }
                LoginResult.InvalidCredentials,
                LoginResult.Disabled,
                -> call.respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_credentials"))
            }
        }
        get("/api/auth/session") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "SESSION_VIEW", "SUCCESS", call.remoteSource())
            call.respond(StaffSessionResponse(staff.username, staff.role))
        }
        post("/api/auth/logout") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@post
            access.revokeSession(call.request.cookies[SESSION_COOKIE_NAME], staff, call.remoteSource())
            call.clearSessionCookie(config)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/public/rescue/manifest") {
            val manifest = rescueManifest ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(manifest)
        }
        // PUERTA Local is a separate general-user page, never the staff console.  Production
        // returns 404 even if an environment variable was accidentally supplied.
        get("/local-pilot") {
            if (!config.localPilotIngressEnabled) return@get call.respond(HttpStatusCode.NotFound)
            val bytes = this::class.java.classLoader.getResourceAsStream("web/local-pilot.html")?.readBytes()
                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(bytes, ContentType.Text.Html)
        }
        post("/local-pilot/api/rescue") {
            if (!config.localPilotIngressEnabled || !rescueDeliveryReady) return@post call.respond(HttpStatusCode.NotFound)
            // This public, unauthenticated form has no staff cookie.  When a browser sends an
            // Origin, it must match the request host exactly; arbitrary suffixes are not safe.
            if (!call.isSameOriginLocalPilotRequest()) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "cross_site_request_rejected"))
            }
            if (!call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim().equals("application/json", ignoreCase = true)) {
                return@post call.respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to "invalid_content_type"))
            }
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val raw = call.receiveText()
            if (raw.encodeToByteArray().size > MAX_CONTROL_BODY_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge)
            }
            if (!anonymousLimiter.allow(call.request.local.remoteHost, 1, raw.encodeToByteArray().size)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "local_pilot_rate_limit"))
            }
            val input = runCatching { GatewayJson.decodeFromString(LocalWebRescueRequest.serializer(), raw) }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_submission"))
            }
            val manifest = rescueManifest ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val service = rescueIntakeService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val result = runCatching { PilotIngress(puerta, manifest, RescueDeliveryIngress(service), pilotOperations::recordRouteAttempt).submitWeb(input) }.getOrElse {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "submission_rejected"))
            }
            // Do not retain an IP address or browser identity for public reports.
            access.audit(null, result.requestId, "PUERTA_LOCAL_WEB_INTAKE", "STORED", null)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.Created, result)
        }
        get("/api/rescue/requests") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val service = rescueIntakeService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            service.purgeExpiredDetails(config.rescueRetentionMillis)
            val items = service.list(latestOnly = true).mapNotNull { summary ->
                service.detail(summary.requestId, summary.requestVersion)?.toOperatorRequest()?.let { request ->
                    puerta.ingress(request.requestId).let { provenance ->
                        request.copy(sourceChannel = provenance.sourceChannel.name, ingressAssurance = provenance.assurance.name)
                    }
                }
            }.sortedWith(
                compareByDescending<com.example.relay.pcgateway.rescue.RescueOperatorRequest> {
                    it.responseStatus == com.example.relay.pcgateway.rescue.RescueResponseStatus.UNCONFIRMED &&
                        it.urgency == "IMMEDIATE" && it.action != "CANCELLED"
                }.thenByDescending { it.urgency == "IMMEDIATE" }
                    .thenByDescending { it.receivedAtEpochMillis },
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            access.audit(staff, null, "RESCUE_LIST_VIEW", "SUCCESS", call.remoteSource())
            call.respond(
                RescueOperatorListResponse(
                    System.currentTimeMillis(),
                    municipality = config.regionalProfile.displayName,
                    retentionDays = config.rescueRetentionDays,
                    items = items,
                ),
            )
        }
        get("/api/rescue/requests/{id}") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val service = rescueIntakeService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val version = call.request.queryParameters["version"]?.toIntOrNull()
            val detail = service.detail(id, version) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            access.audit(staff, id, "RESCUE_VIEW", "SUCCESS", call.remoteSource())
            val request = detail.toOperatorRequest()
            val provenance = puerta.ingress(request.requestId)
            call.respond(request.copy(sourceChannel = provenance.sourceChannel.name, ingressAssurance = provenance.assurance.name))
        }
        post("/api/rescue/requests/{id}/status") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            val service = rescueIntakeService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val request = call.receive<RescueStatusChangeRequest>()
            // Never trust an operator name supplied by a browser.  Claim ownership is bound to
            // the authenticated local account, and the audit record uses the same identity.
            when (val result = service.updateStatus(id, request.status, staff.username, request.expectedRequestVersion)) {
                is RescueStatusUpdateResult.Updated -> {
                    val auditAction = if (
                        request.status == com.example.relay.pcgateway.rescue.RescueResponseStatus.CONFIRMED &&
                        result.request.assignedNodeId == staff.username
                    ) {
                        "RESCUE_ASSIGNMENT_START"
                    } else {
                        "RESCUE_STATUS_CHANGE"
                    }
                    access.audit(staff, id, auditAction, "SUCCESS", call.remoteSource())
                    call.respond(
                        RescueStatusChangeResponse(
                            updated = true,
                            status = result.request.responseStatus,
                            assignedNodeId = result.request.assignedNodeId,
                        ),
                    )
                }
                RescueStatusUpdateResult.NotFound -> {
                    access.audit(staff, id, "RESCUE_STATUS_CHANGE", "NOT_FOUND", call.remoteSource())
                    call.respond(HttpStatusCode.NotFound, RescueStatusChangeResponse(false, reason = "not_found"))
                }
                is RescueStatusUpdateResult.InvalidTransition -> {
                    access.audit(staff, id, "RESCUE_STATUS_CHANGE", "REJECTED", call.remoteSource())
                    call.respond(HttpStatusCode.Conflict, RescueStatusChangeResponse(false, result.current, reason = "invalid_transition"))
                }
                is RescueStatusUpdateResult.AssignedElsewhere -> {
                    access.audit(staff, id, "RESCUE_STATUS_CHANGE", "ASSIGNED_ELSEWHERE", call.remoteSource())
                    call.respond(HttpStatusCode.Conflict, RescueStatusChangeResponse(false, assignedNodeId = result.assignedNodeId, reason = "assigned_elsewhere"))
                }
            }
        }
        post("/api/observations") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val input = runCatching { call.receive<ObservationInput>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_observation")) }
            val assurance = when (input.observationType) { ObservationType.THIRD_PARTY_REPORT -> IngressAssurance.THIRD_PARTY_REPORTED; ObservationType.DEVICE_OBSERVED -> IngressAssurance.UNVERIFIED; else -> IngressAssurance.STAFF_CONFIRMED }
            val observation = runCatching { pilotOperations.addObservation(input, SourceChannel.STAFF_DESK, assurance) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "observation_rejected")) }
            access.audit(staff, observation.observationId, "PONTE_OBSERVATION_CREATE", "SUCCESS", call.remoteSource())
            call.respond(HttpStatusCode.Created, observation)
        }
        get("/api/observations") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "PONTE_OBSERVATION_LIST", "SUCCESS", call.remoteSource())
            call.respond(pilotOperations.observations())
        }
        post("/api/import/preview") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            if (!call.requireBoundedBody(PilotOperationsStore.MAX_CSV_BYTES.toLong() + 4096)) return@post
            val request = runCatching { call.receive<CsvImportRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_csv_request")) }
            val preview = runCatching { pilotOperations.previewCsv(request.kind, request.csv, true) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "csv_rejected")) }
            access.audit(staff, null, "PONTE_CSV_PREVIEW", "SUCCESS", call.remoteSource())
            call.respond(preview)
        }
        post("/api/import") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            if (!call.requireBoundedBody(PilotOperationsStore.MAX_CSV_BYTES.toLong() + 4096)) return@post
            val request = runCatching { call.receive<CsvImportRequest>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_csv_request")) }
            val result = runCatching { pilotOperations.importCsv(request.copy(dryRun = false)) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "csv_rejected")) }
            access.audit(staff, null, "PONTE_CSV_IMPORT", if(result.rejectedRows==0) "SUCCESS" else "REJECTED", call.remoteSource())
            call.respond(result)
        }
        post("/api/support-profiles") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val input = runCatching { call.receive<SupportProfileInput>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_profile")) }
            runCatching { pilotOperations.saveProfile(input) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "profile_rejected")) }
            access.audit(staff, input.subjectToken, "ANTICIPO_PROFILE_SAVE", "SUCCESS", call.remoteSource())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/support-profiles") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "ANTICIPO_PROFILE_LIST", "SUCCESS", call.remoteSource())
            call.respond(pilotOperations.profiles())
        }
        post("/api/support-profiles/{subject}/revoke") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            val subject = call.parameters["subject"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            runCatching { pilotOperations.revokeProfile(subject) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "profile_rejected")) }
            access.audit(staff, subject, "ANTICIPO_PROFILE_REVOKE", "SUCCESS", call.remoteSource())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/review-queue") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "ECART_QUEUE_VIEW", "SUCCESS", call.remoteSource())
            call.respond(pilotOperations.reviewQueue())
        }
        post("/api/review-queue/{subject}") {
            val staff = call.requireStaff(config, access, StaffRole.OPERATOR) ?: return@post
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val subject = call.parameters["subject"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val input = runCatching { call.receive<ReviewOverrideInput>() }.getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_override")) }
            if (input.subjectToken != subject) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "subject_mismatch"))
            runCatching { pilotOperations.saveOverride(input) }.getOrElse { return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "override_rejected")) }
            access.audit(staff, subject, "ECART_MANUAL_OVERRIDE", "SUCCESS", call.remoteSource())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/rescue/requests/{id}/routes") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val service = rescueIntakeService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val request = service.detail(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            access.audit(staff, id, "MOSAIK_ROUTE_VIEW", "SUCCESS", call.remoteSource())
            call.respond(pilotOperations.routeAttempts(request.envelope.envelopeId))
        }
        get("/api/map/status") {
            call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val map = offlineMap ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            // The browser polls this endpoint.  Successful read-only polling is deliberately not
            // appended to the audit database, otherwise opening the map creates a writer storm.
            call.respond(map.status())
        }
        post("/api/map/prepare") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            val map = offlineMap ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            map.prepare()
            access.audit(staff, null, "MAP_PREPARE", "SUCCESS", call.remoteSource())
            call.respond(HttpStatusCode.Accepted, map.status())
        }
        get("/api/map/tiles/{z}/{x}/{y}") {
            call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val map = offlineMap ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val z = call.parameters["z"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val x = call.parameters["x"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val y = call.parameters["y"]?.removeSuffix(".png")?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val bytes = map.tile(z, x, y) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.Image.PNG)
        }
        get("/api/official-info") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val information = officialInformation ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            access.audit(staff, null, "OFFICIAL_INFO_VIEW", "SUCCESS", call.remoteSource())
            call.respond(information.current())
        }
        get("/api/pair/code") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@get
            access.audit(staff, null, "PAIR_CODE_CREATE", "SUCCESS", call.remoteSource())
            call.respond(mapOf("code" to store.createPairingCode()))
        }
        post("/api/pair/request") {
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val request = call.receive<PairRequest>()
            val accepted = store.requestPair(request.code, request.bridgeId, request.bridgeName)
            call.respond(if (accepted) PairResponse(true) else PairResponse(false, reason = "invalid_or_expired_code"))
        }
        post("/api/pair/approve") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val request = call.receive<PairApproveRequest>()
            val token = store.approvePair(request.bridgeId, request.code)
            access.audit(staff, request.bridgeId, "PAIR_APPROVE", if (token == null) "REJECTED" else "SUCCESS", call.remoteSource())
            call.respond(if (token == null) PairResponse(false, reason = "pairing_failed") else PairResponse(true, token))
        }
        post("/api/pair/reject") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            if (!call.requireBoundedBody(MAX_CONTROL_BODY_BYTES)) return@post
            val request = call.receive<PairRejectRequest>()
            if (request.bridgeId.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bridgeId_required"))
            }
            val ok = store.rejectPair(request.bridgeId, request.code)
            access.audit(staff, request.bridgeId, "PAIR_REJECT", if (ok) "SUCCESS" else "NOT_FOUND", call.remoteSource())
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
        post("/api/sync/messages") {
            val bridgeId = call.request.headers["X-Bridge-Id"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val token = bearer(call.request.headers["Authorization"])
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(bridgeId, token)) return@post call.respond(HttpStatusCode.Forbidden)
            if (!call.requireBoundedBody(config.maxPayloadBytes.toLong() * config.maxMessagesPerRequest)) return@post
            val request = call.receive<SyncMessagesRequest>()
            if (request.bridgeId != bridgeId) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.messages.size > config.maxMessagesPerRequest) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SyncMessagesResponse(
                        rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") },
                    ),
                )
            }
            val now = System.currentTimeMillis()
            val outcomes = request.messages.map { message ->
                val reason = validate(message, config.maxPayloadBytes, now)
                if (reason != null) StoreOutcome(message.messageId, "REJECTED", reason = reason) else null
            }
            val accepted = request.messages.zip(outcomes).filter { it.second == null }.map { it.first }
            val stored = runCatching { store.ingest(request.bridgeId, accepted, now) }
                .getOrElse { return@post call.respond(HttpStatusCode.ServiceUnavailable) }
            val all = request.messages.zip(outcomes)
            val rejected = all.filter { it.second != null }.map { GatewayRejection(it.first.messageId, it.second!!.reason!!) }
            // Authentication covers the Bridge transport only. A REPORT signature, when carried,
            // is informational until an issuer registry verifies its origin binding.
            call.response.headers.append("X-Relay-Route-Authentication", "authenticated_bridge")
            call.response.headers.append(
                "X-Relay-Content-Verification",
                if (accepted.any { it.reportSignature != null }) "signed_unverified" else "unverified",
            )
            call.response.headers.append("X-Relay-Receipt-Semantics", "gateway_saved")
            call.respond(syncResponse(stored, rejected))
        }
        post("/api/public/sync/messages") {
            if (!config.anonymousIngressEnabled) return@post call.respond(HttpStatusCode.NotFound)
            if (!call.requireBoundedBody(config.maxAnonymousRequestBytes.toLong())) return@post
            val raw = call.receiveText()
            val rawBytes = raw.encodeToByteArray().size
            if (rawBytes > config.maxAnonymousRequestBytes) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val request = runCatching {
                GatewayJson.decodeFromString(SyncMessagesRequest.serializer(), raw)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            if (request.protocolVersion != GATEWAY_PROTOCOL_VERSION || request.bridgeId.length !in 1..64 ||
                request.messages.size > config.maxAnonymousMessagesPerRequest
            ) {
                return@post call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    SyncMessagesResponse(
                        rejected = request.messages.map { GatewayRejection(it.messageId, "unsupported_or_too_many") },
                    ),
                )
            }
            // Socket peer only — ignore spoofable bridge/header identity for rate limits.
            val source = call.request.local.remoteHost
            if (!anonymousLimiter.allow(source, request.messages.size, rawBytes)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "anonymous_rate_limit"))
            }
            val now = System.currentTimeMillis()
            val validation = request.messages.associateWith { validate(it, config.maxPayloadBytes, now) }
            val accepted = validation.filterValues { it == null }.keys.toList()
            val rejected = validation.filterValues { it != null }.map { (message, reason) ->
                GatewayRejection(message.messageId, reason!!)
            }
            val stored = runCatching { store.ingestUnregistered(accepted, now) }
                .getOrElse { return@post call.respond(HttpStatusCode.ServiceUnavailable) }
            // Keep the legacy header for old clients, but expose the independent axes explicitly.
            call.response.headers.append("X-Relay-Receipt-Trust", "unverified")
            call.response.headers.append("X-Relay-Route-Authentication", "anonymous_lan")
            call.response.headers.append(
                "X-Relay-Content-Verification",
                if (accepted.any { it.reportSignature != null }) "signed_unverified" else "unverified",
            )
            call.response.headers.append("X-Relay-Receipt-Semantics", "gateway_saved")
            call.respond(syncResponse(stored, rejected))
        }
        post("/api/public/rescue/deliver") {
            if (!config.anonymousIngressEnabled || !rescueDeliveryReady) return@post call.respond(HttpStatusCode.NotFound)
            val service = rescueIntakeService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            if (!call.requireBoundedBody(config.maxAnonymousRequestBytes.toLong())) return@post
            val raw = call.receiveText()
            if (raw.encodeToByteArray().size > config.maxAnonymousRequestBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge)
            }
            val request = runCatching {
                GatewayJson.decodeFromString(PublicRescueDeliveryRequest.serializer(), raw)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            val source = call.request.local.remoteHost
            if (!anonymousLimiter.allow(source, 1, raw.encodeToByteArray().size)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "anonymous_rate_limit"))
            }
            val ingress = RescueDeliveryIngress(service, routeType = RouteType.LAN_GATEWAY, routeAttemptSink = pilotOperations::recordRouteAttempt)
            when (val result = ingress.ingest(
                GatewayJson.encodeToString(EncryptedRescueEnvelope.serializer(), request.envelope).encodeToByteArray(),
                request.carrierId,
                request.courierDeliveryId,
            )) {
                is RescueIngestResult.Accepted -> call.respond(HttpStatusCode.OK, PublicRescueDeliveryResponse("accepted", receipt = result.request.receipt))
                is RescueIngestResult.Duplicate -> call.respond(HttpStatusCode.OK, PublicRescueDeliveryResponse("duplicate", receipt = result.request.receipt))
                is RescueIngestResult.Rejected -> call.respond(HttpStatusCode.UnprocessableEntity, PublicRescueDeliveryResponse("rejected", result.code.name))
                is RescueIngestResult.Quarantined -> call.respond(HttpStatusCode.Conflict, PublicRescueDeliveryResponse("quarantined"))
            }
        }
        get("/api/sync/receipts") {
            val bridgeId = call.request.headers["X-Bridge-Id"]
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val token = bearer(call.request.headers["Authorization"])
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
            if (!store.authenticate(bridgeId, token)) return@get call.respond(HttpStatusCode.Forbidden)
            // Scope to this bridge's submissions — do not leak other bridges' receipts.
            call.respond(ReceiptResponse(receipts = store.receiptsForBridge(bridgeId)))
        }
        get("/api/bridges") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "BRIDGE_LIST_VIEW", "SUCCESS", call.remoteSource())
            call.respond(store.summaries())
        }
        /** Operator snapshot intentionally requires the same viewer boundary as the detailed views. */
        get("/api/dashboard") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            access.audit(staff, null, "DASHBOARD_VIEW", "SUCCESS", call.remoteSource())
            call.respond(store.dashboard(config))
        }
        get("/api/messages") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val counts = store.counts()
            val trust = store.trustCounts()
            val routes = store.routeAuthenticationCounts()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 300
            access.audit(staff, null, "MESSAGE_LIST_VIEW", "SUCCESS", call.remoteSource())
            call.respond(
                MessagesListResponse(
                    messages = counts.first,
                    active = counts.second,
                    verified = trust.first,
                    unverified = trust.second,
                    contentVerified = trust.first,
                    contentUnverified = trust.second,
                    authenticatedRoute = routes.authenticatedBridge,
                    anonymousRoute = routes.anonymousLan,
                    items = store.messages(
                        type = call.request.queryParameters["type"],
                        status = call.request.queryParameters["status"],
                        query = call.request.queryParameters["q"],
                        trust = call.request.queryParameters["trust"],
                        routeAuthentication = call.request.queryParameters["routeAuthentication"],
                        limit = limit,
                    ),
                ),
            )
        }
        get("/api/messages/export.csv") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@get
            val csv = store.exportCsv()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"relay-messages.csv\"")
            access.audit(staff, null, "MESSAGE_CSV_EXPORT", "SUCCESS", call.remoteSource())
            call.respondText(csv, ContentType.Text.CSV)
        }
        get("/api/messages/{id}") {
            val staff = call.requireStaff(config, access, StaffRole.VIEWER) ?: return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val detail = store.messageDetail(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            access.audit(staff, id, "MESSAGE_VIEW", "SUCCESS", call.remoteSource())
            call.respond(detail)
        }
        get("/api/admin/accounts") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@get
            access.audit(staff, null, "ACCOUNT_LIST_VIEW", "SUCCESS", call.remoteSource())
            call.respond(access.accounts())
        }
        post("/api/admin/accounts") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            val request = runCatching { call.receive<CreateStaffAccountRequest>() }.getOrElse {
                access.audit(staff, null, "ACCOUNT_CREATE", "MALFORMED", call.remoteSource())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_account"))
            }
            val created = access.createAccount(staff, request.username, request.password, request.role, call.remoteSource())
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.UnprocessableEntity, mapOf("created" to created))
        }
        post("/api/admin/accounts/{username}/role") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            val username = call.parameters["username"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = runCatching { call.receive<UpdateStaffRoleRequest>() }.getOrElse {
                access.audit(staff, username, "ACCOUNT_ROLE_CHANGE", "MALFORMED", call.remoteSource())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_role"))
            }
            val updated = access.updateRole(staff, username, request.role, call.remoteSource())
            call.respond(if (updated) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("updated" to updated))
        }
        post("/api/admin/accounts/{username}/disabled") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@post
            val username = call.parameters["username"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = runCatching { call.receive<UpdateStaffDisabledRequest>() }.getOrElse {
                access.audit(staff, username, "ACCOUNT_DISABLE", "MALFORMED", call.remoteSource())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("reason" to "malformed_disable"))
            }
            val updated = access.setAccountDisabled(staff, username, request.disabled, call.remoteSource())
            call.respond(if (updated) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("updated" to updated))
        }
        get("/api/audit") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@get
            access.audit(staff, null, "AUDIT_LOG_VIEW", "SUCCESS", call.remoteSource())
            call.respond(
                access.auditRecords(
                    action = call.request.queryParameters["action"],
                    operator = call.request.queryParameters["operator"],
                    targetId = call.request.queryParameters["targetId"],
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500,
                ),
            )
        }
        get("/api/audit/export.csv") {
            val staff = call.requireStaff(config, access, StaffRole.ADMIN) ?: return@get
            val csv = access.exportAuditCsv(call.request.queryParameters["limit"]?.toIntOrNull() ?: 5_000)
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"relay-audit-log.csv\"")
            access.audit(staff, null, "AUDIT_CSV_EXPORT", "SUCCESS", call.remoteSource())
            call.respondText(csv, ContentType.Text.CSV)
        }
        // Static operator console (classpath: web/)
        staticResources("/", "web") {
            default("index.html")
        }
        get("/legacy") {
            // Minimal fallback if static resources fail to load in some packaging setups.
            val bytes = this::class.java.classLoader.getResourceAsStream("web/index.html")?.readBytes()
            if (bytes == null) {
                call.respondText("Relay PC Gateway UI missing", status = HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondBytes(bytes, ContentType.Text.Html)
            }
        }
    }
}

/** Safe key diagnostics: no key path, key identifier, certificate, or private material is exposed. */
data class GatewayRescueKeyStatus(
    val storage: String,
    val status: String,
    val expiresAtEpochMillis: Long? = null,
    val warningCode: String? = null,
) {
    companion object {
        fun notChecked() = GatewayRescueKeyStatus(storage = "not_checked", status = "not_checked")
        fun valid(expiresAtEpochMillis: Long, warning: Boolean, dpapiProtected: Boolean = false) = GatewayRescueKeyStatus(
            storage = if (dpapiProtected) "dpapi_protected_file" else "local_file_permission_checked",
            status = if (warning) "expiring_soon" else "valid",
            expiresAtEpochMillis = expiresAtEpochMillis,
            warningCode = if (warning) "rescue_key_expiring_soon_manual_reprovisioning_required" else null,
        )
    }
}

private const val SESSION_COOKIE_NAME = "relay_staff_session"

private fun io.ktor.server.application.ApplicationCall.remoteSource(): String? =
    request.local.remoteHost.takeIf { it.isNotBlank() }

private suspend fun io.ktor.server.application.ApplicationCall.requireStaff(
    config: GatewayConfig,
    access: GatewayAccessStore,
    requiredRole: StaffRole,
): AuthenticatedStaff? {
    val source = remoteSource()
    if (!config.managementSourceAllowed(source)) {
        access.audit(null, null, "AUTH_REJECTED", "REMOTE_MANAGEMENT_DISABLED", source)
        respond(HttpStatusCode.Forbidden, mapOf("reason" to "remote_management_disabled"))
        return null
    }
    val legacyHeader = request.headers["X-Admin-Key"]
    if (legacyHeader != null) {
        if (!config.legacyAdminKeyEnabled || config.adminKey == null) {
            access.audit(null, null, "AUTH_REJECTED", "LEGACY_ADMIN_KEY_DISABLED", source)
            respond(HttpStatusCode.Unauthorized, mapOf("reason" to "legacy_admin_key_disabled"))
            return null
        }
        val accepted = MessageDigest.isEqual(
            legacyHeader.toByteArray(Charsets.UTF_8),
            config.adminKey.toByteArray(Charsets.UTF_8),
        )
        if (!accepted) {
            access.audit(null, null, "AUTH_REJECTED", "INVALID_LEGACY_ADMIN_KEY", source)
            respond(HttpStatusCode.Unauthorized, mapOf("reason" to "invalid_credentials"))
            return null
        }
        val legacy = AuthenticatedStaff("legacy-development-admin", StaffRole.ADMIN, legacyDevelopmentKey = true)
        access.audit(legacy, null, "LEGACY_ADMIN_KEY_AUTH", "SUCCESS", source)
        return legacy
    }
    val staff = access.authenticateSession(request.cookies[SESSION_COOKIE_NAME])
    if (staff == null) {
        access.audit(null, null, "AUTH_REJECTED", "INVALID_OR_EXPIRED_SESSION", source)
        respond(HttpStatusCode.Unauthorized, mapOf("reason" to "authentication_required"))
        return null
    }
    if (!staff.role.permits(requiredRole)) {
        access.audit(staff, null, "AUTHORIZATION_REJECTED", "INSUFFICIENT_ROLE", source)
        respond(HttpStatusCode.Forbidden, mapOf("reason" to "insufficient_role"))
        return null
    }
    return staff
}

private fun io.ktor.server.application.ApplicationCall.appendSessionCookie(config: GatewayConfig, session: GatewaySession) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = session.token,
            maxAge = (config.sessionTtlMillis / 1_000).toInt(),
            path = "/",
            secure = config.sessionCookieSecure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

private fun io.ktor.server.application.ApplicationCall.clearSessionCookie(config: GatewayConfig) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = "",
            maxAge = 0,
            path = "/",
            secure = config.sessionCookieSecure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

@Serializable
private data class PublicRescueDeliveryRequest(
    val envelope: EncryptedRescueEnvelope,
    val carrierId: String,
    val courierDeliveryId: String,
)

@Serializable
private data class PublicRescueDeliveryResponse(
    val outcome: String,
    val reason: String? = null,
    val receipt: com.example.relay.rescue.SignedShelterReceipt? = null,
)

private fun syncResponse(
    stored: List<StoreOutcome>,
    rejectedBeforeStore: List<GatewayRejection>,
): SyncMessagesResponse {
    val storeRejected = stored.filter { it.disposition !in setOf("STORED", "DUPLICATE") }
        .map { outcome -> GatewayRejection(outcome.messageId, outcome.reason ?: outcome.disposition.lowercase()) }
    return SyncMessagesResponse(
        acceptedMessageIds = stored.filter { it.disposition == "STORED" }.map { it.messageId },
        duplicateMessageIds = stored.filter { it.disposition == "DUPLICATE" }.map { it.messageId },
        rejected = rejectedBeforeStore + storeRejected,
        receipts = stored.mapNotNull { it.receipt },
    )
}

private fun bearer(value: String?): String? =
    value?.removePrefix("Bearer ")?.takeIf { it != value && it.isNotBlank() }

private suspend fun ApplicationCall.requireBoundedBody(maxBytes: Long): Boolean {
    val rawLength = request.headers[HttpHeaders.ContentLength]
        ?: run {
            respond(HttpStatusCode.LengthRequired)
            return false
        }
    val contentLength = rawLength.toLongOrNull()
        ?: run {
            respond(HttpStatusCode.BadRequest)
            return false
        }
    if (contentLength < 0) {
        respond(HttpStatusCode.BadRequest)
        return false
    }
    if (contentLength > maxBytes) {
        respond(HttpStatusCode.PayloadTooLarge)
        return false
    }
    return true
}

/**
 * Local pilot reports have no authenticated browser state.  Reject a browser-originated request
 * unless the serialized Origin exactly matches the Host that received it; this permits an
 * explicitly selected development/LAN host without accepting suffix tricks such as evil:8080.
 */
private fun ApplicationCall.isSameOriginLocalPilotRequest(): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    val host = request.headers[HttpHeaders.Host] ?: return false
    val parsed = runCatching { URI(origin) }.getOrNull() ?: return false
    return parsed.scheme in setOf("http", "https") &&
        parsed.userInfo == null && parsed.query == null && parsed.fragment == null &&
        (parsed.path.isNullOrEmpty() || parsed.path == "/") &&
        parsed.rawAuthority?.equals(host, ignoreCase = true) == true
}

private fun validate(
    message: com.example.relay.gateway.protocol.GatewayMessage,
    maxBytes: Int,
    now: Long,
): String? {
    if (message.messageId.length !in 1..64 || message.originDeviceId.length !in 1..64) return "invalid_identifier"
    if (message.messageType !in setOf("SAFETY", "SUPPLY") ||
        message.recordType !in setOf("REPORT", "STATUS_CHANGE")
    ) {
        return "invalid_type"
    }
    if (message.priority !in setOf("LOW", "NORMAL", "HIGH", "CRITICAL") ||
        message.status !in setOf("ACTIVE", "RESOLVED", "RETRACTED")
    ) {
        return "invalid_enum"
    }
    if (message.lifetimeMs !in 1..604_800_000L || message.accumulatedAgeMs !in 0..message.lifetimeMs) return "invalid_ttl"
    if (message.hopCount !in 0..message.hopLimit || message.hopLimit !in 1..32) return "invalid_hop"
    if (message.accumulatedAgeMs >= message.lifetimeMs || message.createdAt > now + 86_400_000L) return "expired_or_future"
    if (message.payload.toString().toByteArray().size > maxBytes) return "payload_too_large"
    return null
}
