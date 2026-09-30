package com.example.relay.ui.rescue

import com.example.relay.rescue.RescueSubmissionStatus

/**
 * How far a rescue request has *provably* travelled.
 *
 * Copy must never imply a stronger guarantee than the trust level allows. In particular
 * an HTTP success, a relay handoff, a peer ACK, or an unsigned gateway store are **not**
 * a shelter receipt and **not** rescue completion.
 */
internal enum class RescueDeliveryTrust {
    /** Encrypted on the sender device only; no transferable envelope exists yet. */
    DEVICE_ONLY,

    /** A transferable envelope exists and is being carried by nearby devices. No shelter confirmation. */
    RELAYING,

    /** A signed shelter receipt confirms the shelter PC stored it. Staff have not acted yet. */
    SHELTER_STORED,

    /** Shelter staff have accepted the request or are actively responding. */
    SHELTER_HANDLING,

    /** Terminal outcome: completed, cancelled, or returned for shelter review. */
    RESOLVED,
}

/**
 * Single source of truth for user-facing rescue submission status copy.
 *
 * This consolidates what used to be two diverging label tables (the home "own request" card and
 * the status screens). Every screen now derives its wording from here so the same
 * [RescueSubmissionStatus] can never be described two different ways.
 */
internal data class RescueStatusCopy(
    val shortLabelJa: String,
    val shortLabelEn: String,
    val descriptionJa: String,
    val descriptionEn: String,
    val isTerminal: Boolean,
    val trustLevel: RescueDeliveryTrust,
) {
    fun shortLabel(language: RescueLanguage): String = language.text(shortLabelJa, shortLabelEn)

    fun description(language: RescueLanguage): String = language.text(descriptionJa, descriptionEn)
}

/**
 * Maps each submission status to trust-safe copy. Wording rules:
 * - Do not say "送信済み/到達" until the matching signed shelter receipt exists.
 * - Do not present a relay handoff or gateway store as a shelter receipt.
 * - Never expose the raw enum name to a general user.
 */
internal fun RescueSubmissionStatus.statusCopy(): RescueStatusCopy = when (this) {
    RescueSubmissionStatus.PENDING_DESTINATION -> RescueStatusCopy(
        shortLabelJa = "この端末に保存しました",
        shortLabelEn = "Saved on this device",
        descriptionJa = "救助内容をこの端末に安全に保存しました。信頼できる受信先を確認中で、" +
            "まだほかの端末や避難所には送っていません。受信先の確認前なら、この端末から取り消せます。",
        descriptionEn = "Your request is stored safely on this device. A trusted receiver is being " +
            "confirmed, so nothing has been sent to other devices or a shelter yet. " +
            "You can cancel from this device until a receiver is confirmed.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.DEVICE_ONLY,
    )
    RescueSubmissionStatus.PENDING -> RescueStatusCopy(
        shortLabelJa = "近くの端末を探しています",
        shortLabelEn = "Looking for nearby devices",
        descriptionJa = "受信先を確認しました。近くの端末を探して中継の準備をしています。" +
            "まだ避難所には届いていません。",
        descriptionEn = "A receiver is confirmed. Relay is looking for nearby devices to carry your " +
            "request. It has not reached a shelter yet.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.RELAYING,
    )
    RescueSubmissionStatus.IN_TRANSIT -> RescueStatusCopy(
        shortLabelJa = "近くの端末へ中継中です",
        shortLabelEn = "Relaying via nearby devices",
        descriptionJa = "近くの端末へ救助内容を中継しています。避難所への到達はまだ確認できていません。",
        descriptionEn = "Your request is being relayed through nearby devices. Arrival at a shelter " +
            "is not confirmed yet.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.RELAYING,
    )
    RescueSubmissionStatus.SHELTER_STORED -> RescueStatusCopy(
        shortLabelJa = "救助拠点に保存（署名確認済み）",
        shortLabelEn = "Stored at the rescue hub (signed)",
        descriptionJa = "救助拠点の受信PCに保存され、署名付きの受信確認が返ってきました。" +
            "スタッフの受領・対応はこれからです。",
        descriptionEn = "Stored on the rescue hub's receiving PC with a signed confirmation. " +
            "Staff have not accepted or started responding yet.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.SHELTER_STORED,
    )
    RescueSubmissionStatus.SHELTER_ACCEPTED -> RescueStatusCopy(
        shortLabelJa = "スタッフが受領しました",
        shortLabelEn = "Accepted by shelter staff",
        descriptionJa = "救助拠点のスタッフが救助依頼を受領しました。対応の準備が始まります。",
        descriptionEn = "Shelter staff have accepted your request and are preparing to respond.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.SHELTER_HANDLING,
    )
    RescueSubmissionStatus.SHELTER_RESPONDING -> RescueStatusCopy(
        shortLabelJa = "避難所が対応中です",
        shortLabelEn = "Shelter is responding",
        descriptionJa = "救助拠点が対応を進めています。",
        descriptionEn = "The shelter is actively responding.",
        isTerminal = false,
        trustLevel = RescueDeliveryTrust.SHELTER_HANDLING,
    )
    RescueSubmissionStatus.SHELTER_COMPLETED -> RescueStatusCopy(
        shortLabelJa = "対応が完了しました",
        shortLabelEn = "Response complete",
        descriptionJa = "救助拠点が対応の完了を記録しました。",
        descriptionEn = "The shelter recorded this response as complete.",
        isTerminal = true,
        trustLevel = RescueDeliveryTrust.RESOLVED,
    )
    RescueSubmissionStatus.CANCELLED -> RescueStatusCopy(
        shortLabelJa = "取り消し済みです",
        shortLabelEn = "Cancelled",
        descriptionJa = "この救助依頼の取り消しを救助拠点が確認しました。",
        descriptionEn = "The rescue hub confirmed the cancellation of this request.",
        isTerminal = true,
        trustLevel = RescueDeliveryTrust.RESOLVED,
    )
    RescueSubmissionStatus.SHELTER_REJECTED -> RescueStatusCopy(
        shortLabelJa = "確認が必要です",
        shortLabelEn = "Needs shelter review",
        descriptionJa = "救助拠点側で確認が必要な状態です。内容の更新や再依頼が必要な場合があります。",
        descriptionEn = "The shelter needs to review this request. You may need to update it or " +
            "request help again.",
        isTerminal = true,
        trustLevel = RescueDeliveryTrust.RESOLVED,
    )
}
