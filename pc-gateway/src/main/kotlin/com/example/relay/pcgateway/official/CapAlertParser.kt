package com.example.relay.pcgateway.official

import kotlinx.serialization.Serializable

/**
 * A parsed OASIS CAP 1.2 alert (urn:oasis:names:tc:emergency:cap:1.2).
 *
 * Parsing is structure-only: it never claims the alert content is authentic. CAP allows an
 * enveloped XML-DSig signature, but Relay has no trust anchors for any CAP publisher yet, so
 * [signaturePresent] merely records that a Signature element exists - it is NOT verified and
 * must never be rendered as "signed/verified" to staff.
 */
@Serializable
data class CapAlert(
    val identifier: String,
    val sender: String,
    val sent: String,
    val status: CapStatus,
    val msgType: String,
    val scope: String,
    val infos: List<CapInfo>,
    val signaturePresent: Boolean,
)

@Serializable
data class CapInfo(
    val language: String?,
    val category: String,
    val event: String,
    val urgency: String,
    val severity: String,
    val certainty: String,
    val headline: String?,
    val description: String?,
    val areaDescriptions: List<String>,
)

/** CAP `status`: only these values exist in CAP 1.2; anything else is rejected. */
@Serializable
enum class CapStatus { ACTUAL, EXERCISE, SYSTEM, TEST, DRAFT }

/**
 * Fail-closed CAP 1.2 parser over [HardenedXml].
 *
 * Every mandatory CAP element (identifier/sender/sent/status/msgType/scope, and per-info
 * category/event/urgency/severity/certainty) must be present, and status must be a known CAP
 * value; otherwise [OfficialXmlParseException] is thrown. Training/demo feeds use status
 * Exercise or Test, which callers must surface so a drill is never shown as a real alert.
 */
object CapAlertParser {
    const val CAP_NAMESPACE = "urn:oasis:names:tc:emergency:cap:1.2"
    private const val XMLDSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#"

    fun parse(xml: String): CapAlert {
        val root = HardenedXml.parse(xml).documentElement
            ?: throw OfficialXmlParseException("empty CAP document")
        if (root.namespaceURI != CAP_NAMESPACE || root.localName != "alert") {
            throw OfficialXmlParseException(
                "not a CAP 1.2 alert: {${root.namespaceURI}}${root.localName}"
            )
        }
        val statusText = HardenedXml.requiredText(root, CAP_NAMESPACE, "status")
        val status = CapStatus.entries.firstOrNull { it.name.equals(statusText, ignoreCase = true) }
            ?: throw OfficialXmlParseException("unknown CAP status: $statusText")
        val infos = HardenedXml.childElements(root, CAP_NAMESPACE, "info").map { info ->
            CapInfo(
                language = HardenedXml.optionalText(info, CAP_NAMESPACE, "language"),
                category = HardenedXml.requiredText(info, CAP_NAMESPACE, "category"),
                event = HardenedXml.requiredText(info, CAP_NAMESPACE, "event"),
                urgency = HardenedXml.requiredText(info, CAP_NAMESPACE, "urgency"),
                severity = HardenedXml.requiredText(info, CAP_NAMESPACE, "severity"),
                certainty = HardenedXml.requiredText(info, CAP_NAMESPACE, "certainty"),
                headline = HardenedXml.optionalText(info, CAP_NAMESPACE, "headline"),
                description = HardenedXml.optionalText(info, CAP_NAMESPACE, "description"),
                areaDescriptions = HardenedXml.childElements(info, CAP_NAMESPACE, "area")
                    .map { HardenedXml.requiredText(it, CAP_NAMESPACE, "areaDesc") },
            )
        }
        return CapAlert(
            identifier = HardenedXml.requiredText(root, CAP_NAMESPACE, "identifier"),
            sender = HardenedXml.requiredText(root, CAP_NAMESPACE, "sender"),
            sent = HardenedXml.requiredText(root, CAP_NAMESPACE, "sent"),
            status = status,
            msgType = HardenedXml.requiredText(root, CAP_NAMESPACE, "msgType"),
            scope = HardenedXml.requiredText(root, CAP_NAMESPACE, "scope"),
            infos = infos,
            signaturePresent =
                HardenedXml.childElements(root, XMLDSIG_NAMESPACE, "Signature").isNotEmpty(),
        )
    }
}
