package com.example.relay.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Durable rescue routing state. [envelopeJson] contains only an EncryptedRescueEnvelope and
 * [signedReceiptJson] contains only a public, signed shelter receipt; plaintext rescue payloads
 * and private key material must never be written to this table.
 */
@Entity(
    tableName = "rescue_envelopes",
    primaryKeys = ["requestId", "requestVersion"],
    indices = [Index("expiresAtEpochMillis"), Index("submissionStatus")],
)
data class RescueEntity(
    val requestId: String,
    val requestVersion: Int,
    val envelopeId: String,
    val ciphertextSha256Hex: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val storageSizeBytes: Long,
    val envelopeJson: String,
    val receivedAtEpochMillis: Long,
    val submissionStatus: String,
    val submissionCount: Int,
    val signedReceiptJson: String?,
)
