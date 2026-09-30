package com.example.relay.domain

/**
 * Boundary for an optional, non-Nearby message ingress such as a QR code or a
 * signed Relay file. Implementations must route data through the shared ingress
 * validation policy; they must not write directly to the database.
 */
interface MessageImportTransport {
    suspend fun importData(data: ByteArray): ImportResult
}

/**
 * Boundary for an optional, non-Nearby message egress such as a QR code or a
 * signed Relay file. This contract does not imply that every requested record can
 * be exported by every transport.
 */
interface MessageExportTransport {
    suspend fun exportMessages(messageIds: List<String>): ExportResult
}

sealed interface ImportResult {
    /** The listed messages have passed ingress validation and were saved locally. */
    data class Imported(val messageIds: List<String>) : ImportResult

    /** No message was accepted. The reason is safe to expose in debug diagnostics. */
    data class Rejected(val reason: ImportRejection) : ImportResult
}

enum class ImportRejection {
    PAYLOAD_TOO_LARGE,
    MALFORMED_DATA,
    UNSUPPORTED_VERSION,
    INVALID_DATA,
    EXPIRED,
    DUPLICATE,
    STORAGE_LIMIT,
    UNVERIFIED_INTEGRITY,
}

sealed interface ExportResult {
    data class Exported(
        val data: ByteArray,
        val messageIds: List<String>,
    ) : ExportResult

    data class Rejected(val reason: ExportRejection) : ExportResult
}

enum class ExportRejection {
    NOT_FOUND,
    NOT_EXPORTABLE,
    PAYLOAD_TOO_LARGE,
    INTEGRITY_UNAVAILABLE,
}
