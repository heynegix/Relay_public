package com.example.relay.rescue

/** The complete courier-facing projection. It intentionally excludes sender and ciphertext data. */
data class CourierRescueItem(
    val requestId: String,
    val requestVersion: Int,
    val destinationShelterId: String,
    val receivedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val submissionStatus: RescueSubmissionStatus,
    val submissionCount: Int,
)

class CourierRescuePresenter(
    private val repository: RescueEnvelopeRepository,
) {
    fun items(): List<CourierRescueItem> = repository.all().map { record ->
        CourierRescueItem(
            requestId = record.envelope.requestId,
            requestVersion = record.envelope.requestVersion,
            destinationShelterId = record.envelope.destinationShelterId,
            receivedAtEpochMillis = record.state.receivedAtEpochMillis,
            expiresAtEpochMillis = record.envelope.expiresAtEpochMillis,
            submissionStatus = record.state.submissionStatus,
            submissionCount = record.state.submissionCount,
        )
    }
}
