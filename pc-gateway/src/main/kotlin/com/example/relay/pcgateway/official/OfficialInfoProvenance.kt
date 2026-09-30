package com.example.relay.pcgateway.official

import kotlinx.serialization.Serializable

/**
 * Provenance record for one piece of official information shown to staff.
 *
 * This model deliberately never claims content authenticity: JMA's bosai JSON and Atom
 * feeds carry no content signature, so the strongest state a live fetch can reach is
 * [OfficialInfoVerification.TRANSPORT_TLS_ONLY] (the HTTPS server was authenticated by
 * TLS, the payload itself was not). Cached replays are explicitly weaker, and an
 * unavailable source is never presented as verified. Staff-facing UI must render this
 * state instead of implying the Gateway validated the alert content.
 */
@Serializable
data class OfficialInfoProvenance(
    val sourceUrl: String,
    val format: OfficialInfoFormat,
    val retrieval: OfficialInfoRetrieval,
    val verification: OfficialInfoVerification,
    /** When this provenance record was produced (the staff request time). */
    val checkedAtEpochMillis: Long,
    /** When the content itself was obtained from the source; null when nothing was retrievable. */
    val fetchedAtEpochMillis: Long? = null,
    /** SHA-256 of the exact raw document the summary was derived from; null when unavailable. */
    val contentSha256Hex: String? = null,
)

@Serializable
enum class OfficialInfoFormat {
    JMA_BOSAI_JSON,
    JMA_XML_ATOM_FEED,
    CAP_1_2,
}

@Serializable
enum class OfficialInfoRetrieval {
    /** Fetched from the origin during this check. */
    LIVE_FETCH,

    /** Origin unreachable; replayed from the local cache written by an earlier live fetch. */
    LOCAL_CACHE,

    /** Neither the origin nor a cache copy was available. */
    UNAVAILABLE,
}

@Serializable
enum class OfficialInfoVerification {
    /** TLS authenticated the origin server; the document content itself is unsigned. */
    TRANSPORT_TLS_ONLY,

    /** Replayed from local cache; only the original fetch's transport evidence exists. */
    CACHED_UNVERIFIED,

    /** No evidence at all. */
    UNVERIFIED,
}
