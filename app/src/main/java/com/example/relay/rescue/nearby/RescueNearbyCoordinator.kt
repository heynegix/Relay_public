package com.example.relay.rescue.nearby

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.sameImmutableEnvelopeAs
import com.example.relay.rescue.rank
import com.example.relay.rescue.toSubmissionStatus
import com.example.relay.rescue.validate
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.SendResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

private const val MAX_INVENTORY_HOP_COUNT = 32

sealed interface RescueNearbyDebugEvent {
    data class TransferCompleted(val peerId: String, val packetType: String) : RescueNearbyDebugEvent
    data class TransferFailed(val peerId: String, val packetType: String, val reason: String) : RescueNearbyDebugEvent
}

/**
 * Encrypted rescue store-carry-forward exchange over the existing Nearby byte transport.
 *
 * This class deliberately has no plaintext payload API. Its inventory contains only routing
 * metadata, while a data packet carries the already encrypted [EncryptedRescueEnvelope].
 */
class RescueNearbyCoordinator(
    private val repository: RescueEnvelopeRepository,
    private val transport: OfflineTransport,
    private val nowEpochMillis: () -> Long,
    private val codec: RescueNearbyPacketCodec = RescueNearbyPacketCodec(),
    private val maxInventoryEntries: Int = 64,
    private val maxRequestedEntries: Int = 32,
    private val shelterKeyProvider: ShelterPublicKeyProvider? = null,
    /** Starts durable onward delivery when this device becomes a courier for a new envelope. */
    private val onEnvelopeStored: () -> Unit = {},
    /** Sender sessions receive receipt state only after the existing signature checks succeed. */
    private val receiptApplier: (RescueRequestKey, SignedShelterReceipt, com.example.relay.rescue.RescuePublicKey) -> ReceiptApplicationResult = repository::applyReceipt,
) {
    private val pendingExports = ConcurrentHashMap<Pair<String, RescueRequestKey>, EncryptedRescueEnvelope>()
    private val _debugEvents = MutableSharedFlow<RescueNearbyDebugEvent>(extraBufferCapacity = 64)
    val debugEvents: SharedFlow<RescueNearbyDebugEvent> = _debugEvents.asSharedFlow()

    init {
        require(maxInventoryEntries in 1..256)
        require(maxRequestedEntries in 1..maxInventoryEntries)
    }

    fun isRescuePayload(bytes: ByteArray): Boolean = codec.isRescuePayload(bytes)

    /** Sends no-op-safe metadata only; callers invoke this after the Nearby peer is connected. */
    suspend fun onPeerConnected(peerId: String) {
        sendInventory(peerId)
    }

    /** Drops acknowledgements belonging to a disconnected Nearby session. */
    fun onPeerDisconnected(peerId: String) {
        pendingExports.keys.removeIf { it.first == peerId }
    }

    /** Re-advertises durable local changes to peers that are already connected. */
    suspend fun onLocalStoreChanged() {
        refreshConnectedPeers()
    }

    /**
     * Handles a payload already identified by [isRescuePayload]. Invalid or oversized packets are
     * dropped without touching the encrypted store and never produce an acknowledgement.
     */
    suspend fun handlePayload(peerId: String, bytes: ByteArray) {
        val packet = try {
            codec.decode(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        when (packet) {
            is RescueNearbyPacket.Inventory -> handleInventory(peerId, packet)
            is RescueNearbyPacket.Request -> handleRequest(peerId, packet)
            is RescueNearbyPacket.Envelope -> handleEnvelope(peerId, packet)
            is RescueNearbyPacket.Ack -> handleAck(peerId, packet)
            is RescueNearbyPacket.ReceiptRequest -> handleReceiptRequest(peerId, packet)
            is RescueNearbyPacket.Receipt -> handleReceipt(peerId, packet)
        }
    }

    private suspend fun sendInventory(peerId: String) {
        val now = nowEpochMillis()
        val entries = repository.all()
            .asSequence()
            .filter { it.isAdvertisableAt(now) }
            .map { it.toInventoryEntry() }
            .sortedWith(compareBy<RescueInventoryEntry>({ it.requestId }, { it.requestVersion }, { it.ciphertextSha256Hex }))
            .toList()
        entries.chunked(maxInventoryEntries).ifEmpty { listOf(emptyList()) }.forEach { page ->
            send(peerId, RescueNearbyPacket.Inventory(page))
        }
    }

    private suspend fun handleInventory(peerId: String, packet: RescueNearbyPacket.Inventory) {
        val now = nowEpochMillis()
        val requested = packet.entries
            .asSequence()
            .filter { it.isValidAt(now) }
            .filter { remote -> needsEnvelope(remote) }
            .distinctBy { it.requestId to it.requestVersion }
            .map { RescueRequestKeyWire(it.requestId, it.requestVersion) }
            .toList()
        requested.chunked(maxRequestedEntries).forEach { page ->
            send(peerId, RescueNearbyPacket.Request(page))
        }
        val receiptKeys = packet.entries
            .asSequence()
            .filter { it.isValidAt(now) && needsReceipt(it) }
            .distinctBy { it.requestId to it.requestVersion }
            .map { RescueRequestKeyWire(it.requestId, it.requestVersion) }
            .toList()
        receiptKeys.chunked(maxRequestedEntries).forEach { page ->
            send(peerId, RescueNearbyPacket.ReceiptRequest(page))
        }
    }

    private fun needsEnvelope(remote: RescueInventoryEntry): Boolean {
        val local = repository.get(remote.key())
        // A same-version, different-hash packet is a collision/tamper candidate, not an update.
        // Do not ask a peer to send it repeatedly.
        if (local != null) {
            // v1 inventory deliberately omits hopCount for compatibility with strict older
            // decoders. Do not repeatedly request a same-hash copy whose route depth cannot be
            // compared; a capability-negotiated v2 inventory can restore path optimization.
            return false
        }
        return repository.all().none {
            it.envelope.requestId == remote.requestId && it.envelope.requestVersion > remote.requestVersion
        }
    }

    private fun needsReceipt(remote: RescueInventoryEntry): Boolean {
        val remoteStatus = remote.receiptStatus?.toSubmissionStatus() ?: return false
        val local = repository.get(remote.key()) ?: return false
        return remoteStatus.rank() > local.state.submissionStatus.rank()
    }

    private suspend fun handleRequest(peerId: String, packet: RescueNearbyPacket.Request) {
        val now = nowEpochMillis()
        packet.keys.distinct().take(maxRequestedEntries).forEach { key ->
            val domainKey = key.toKey()
            val exported = repository.prepareForExport(domainKey, now) ?: return@forEach
            // The prepared copy is the exact packet for which an ACK may advance the local hop.
            val pendingKey = peerId to domainKey
            pendingExports[pendingKey] = exported
            when (send(peerId, RescueNearbyPacket.Envelope(exported))) {
                SendResult.PayloadTransferCompleted -> Unit
                is SendResult.Failed -> pendingExports.remove(pendingKey, exported)
            }
        }
    }

    private suspend fun handleEnvelope(peerId: String, packet: RescueNearbyPacket.Envelope) {
        val envelope = packet.envelope
        if (envelope.validate() != RescueValidationResult.Valid) return
        val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)
        val storeResult = repository.store(envelope, nowEpochMillis())
        when (storeResult) {
            is RescueStoreResult.Stored,
            is RescueStoreResult.Rejected -> {
                // Only a duplicate proves that the exact immutable ciphertext is already durable.
                val durable = repository.get(key)?.envelope
                if (durable != null && durable.sameImmutableEnvelopeAs(envelope)
                ) {
                    send(peerId, RescueNearbyPacket.Ack(key, envelope.envelopeId, envelope.ciphertextSha256Hex, envelope.hopCount))
                }
            }
        }
        if (storeResult is RescueStoreResult.Stored) {
            // A receiving device can be the only one with a working LAN or Internet path. Start
            // its durable delivery owner immediately; previously only the creating device did
            // this, so a successfully stored envelope could remain stranded on a courier.
            onEnvelopeStored()
            refreshConnectedPeers(excludingPeerId = peerId)
        }
    }

    private fun handleAck(peerId: String, packet: RescueNearbyPacket.Ack) {
        val pendingKey = peerId to packet.key
        val exported = pendingExports[pendingKey] ?: return
        if (exported.envelopeId != packet.envelopeId ||
            exported.ciphertextSha256Hex != packet.ciphertextSha256Hex ||
            exported.hopCount != packet.exportedHopCount
        ) return
        if (repository.recordSuccessfulExport(packet.key, packet.exportedHopCount)) {
            pendingExports.remove(pendingKey, exported)
        }
    }

    private suspend fun handleReceiptRequest(peerId: String, packet: RescueNearbyPacket.ReceiptRequest) {
        packet.keys.distinct().take(maxRequestedEntries).forEach { wire ->
            val receipt = repository.get(wire.toKey())?.state?.signedReceipt ?: return@forEach
            send(peerId, RescueNearbyPacket.Receipt(receipt))
        }
    }

    private suspend fun handleReceipt(peerId: String, packet: RescueNearbyPacket.Receipt) {
        val keys = shelterKeyProvider?.load() ?: return
        val receipt = packet.receipt
        if (receipt.receipt.shelterId != keys.shelterId) return
        if (receiptApplier(
            RescueRequestKey(receipt.receipt.requestId, receipt.receipt.requestVersion),
            receipt,
            keys.receiptSigningKey,
        ) == ReceiptApplicationResult.APPLIED) {
            refreshConnectedPeers(excludingPeerId = peerId)
        }
    }

    private suspend fun refreshConnectedPeers(excludingPeerId: String? = null) {
        transport.state.value.connectedPeerIds
            .asSequence()
            .filter { it != excludingPeerId }
            .sorted()
            .forEach { sendInventory(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(peerId: String, packet: RescueNearbyPacket): SendResult {
        val packetType = packet::class.simpleName ?: "rescue"
        val result = try {
            transport.send(peerId, codec.encode(packet))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SendResult.Failed(error.message ?: "rescue transport failed")
        }
        when (result) {
            SendResult.PayloadTransferCompleted ->
                _debugEvents.tryEmit(RescueNearbyDebugEvent.TransferCompleted(peerId, packetType))
            is SendResult.Failed ->
                _debugEvents.tryEmit(RescueNearbyDebugEvent.TransferFailed(peerId, packetType, result.reason))
        }
        return result
    }

    private fun StoredRescueRecord.isAdvertisableAt(now: Long): Boolean =
        now < envelope.expiresAtEpochMillis &&
            (envelope.hopCount < envelope.maxHopCount || state.signedReceipt != null)

    private fun StoredRescueRecord.toInventoryEntry() = RescueInventoryEntry(
        requestId = envelope.requestId,
        requestVersion = envelope.requestVersion,
        ciphertextSha256Hex = envelope.ciphertextSha256Hex,
        expiresAtEpochMillis = envelope.expiresAtEpochMillis,
        receiptStatus = state.signedReceipt?.receipt?.status,
        receiptUpdatedAtEpochMillis = state.signedReceipt?.receipt?.receivedAtEpochMillis,
        hopCount = envelope.hopCount,
    )
}

@Serializable
data class RescueInventoryEntry(
    val requestId: String,
    val requestVersion: Int,
    val ciphertextSha256Hex: String,
    val expiresAtEpochMillis: Long,
    val receiptStatus: ShelterReceiptStatus? = null,
    val receiptUpdatedAtEpochMillis: Long? = null,
    /** Kept local until a capability-negotiated v2 inventory field exists. */
    @Transient val hopCount: Int = 0,
) {
    fun key() = RescueRequestKey(requestId, requestVersion)

    fun isValidAt(now: Long): Boolean =
        requestId.length in 1..128 && requestVersion in 1..1_000_000 &&
            ciphertextSha256Hex.length == 64 && ciphertextSha256Hex.all { it in '0'..'9' || it in 'a'..'f' } &&
            expiresAtEpochMillis > now &&
            (receiptStatus == null) == (receiptUpdatedAtEpochMillis == null) &&
            (receiptUpdatedAtEpochMillis == null || receiptUpdatedAtEpochMillis > 0) &&
            hopCount in 0..MAX_INVENTORY_HOP_COUNT
}

@Serializable
data class RescueRequestKeyWire(val requestId: String, val requestVersion: Int) {
    fun toKey() = RescueRequestKey(requestId, requestVersion)
}

@Serializable
sealed interface RescueNearbyPacket {
    @Serializable
    data class Inventory(val entries: List<RescueInventoryEntry>) : RescueNearbyPacket

    @Serializable
    data class Request(val keys: List<RescueRequestKeyWire>) : RescueNearbyPacket

    @Serializable
    data class Envelope(val envelope: EncryptedRescueEnvelope) : RescueNearbyPacket

    @Serializable
    data class ReceiptRequest(val keys: List<RescueRequestKeyWire>) : RescueNearbyPacket

    @Serializable
    data class Receipt(val receipt: SignedShelterReceipt) : RescueNearbyPacket

    @Serializable
    data class Ack(
        val requestId: String,
        val requestVersion: Int,
        val envelopeId: String,
        val ciphertextSha256Hex: String,
        val exportedHopCount: Int,
    ) : RescueNearbyPacket {
        constructor(key: RescueRequestKey, envelopeId: String, ciphertextSha256Hex: String, exportedHopCount: Int) :
            this(key.requestId, key.requestVersion, envelopeId, ciphertextSha256Hex, exportedHopCount)

        val key: RescueRequestKey get() = RescueRequestKey(requestId, requestVersion)
    }
}

/** Fixed discriminator keeps rescue bytes out of the existing relay-protocol [PacketCodec]. */
class RescueNearbyPacketCodec(
    private val maxPacketBytes: Int = 32 * 1024,
    private val maxInventoryEntries: Int = 64,
    private val maxRequestedEntries: Int = 32,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }

    init {
        require(maxPacketBytes in 512..32 * 1024)
        require(maxInventoryEntries in 1..256)
        require(maxRequestedEntries in 1..maxInventoryEntries)
    }

    fun isRescuePayload(bytes: ByteArray): Boolean = bytes.size >= PREFIX.size && bytes.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)

    fun encode(packet: RescueNearbyPacket): ByteArray {
        validate(packet)
        val encoded = PREFIX + json.encodeToString<RescueNearbyPacket>(packet).encodeToByteArray()
        require(encoded.size <= maxPacketBytes) { "rescue nearby packet exceeds 32 KiB" }
        return encoded
    }

    fun decode(bytes: ByteArray): RescueNearbyPacket {
        require(isRescuePayload(bytes) && bytes.size <= maxPacketBytes) { "invalid rescue nearby packet" }
        val decoded = json.decodeFromString<RescueNearbyPacket>(bytes.copyOfRange(PREFIX.size, bytes.size).decodeToString())
        validate(decoded)
        return decoded
    }

    private fun validate(packet: RescueNearbyPacket) = when (packet) {
        is RescueNearbyPacket.Inventory -> require(packet.entries.size <= maxInventoryEntries && packet.entries.all { it.isValidAt(0) })
        is RescueNearbyPacket.Request -> require(packet.keys.size in 1..maxRequestedEntries && packet.keys.distinct().size == packet.keys.size && packet.keys.all { validKey(it) })
        is RescueNearbyPacket.Envelope -> require(packet.envelope.validate() == RescueValidationResult.Valid)
        is RescueNearbyPacket.ReceiptRequest -> require(
            packet.keys.size in 1..maxRequestedEntries && packet.keys.distinct().size == packet.keys.size &&
                packet.keys.all { validKey(it) },
        )
        is RescueNearbyPacket.Receipt -> require(packet.receipt.validate() == RescueValidationResult.Valid)
        is RescueNearbyPacket.Ack -> require(validKey(RescueRequestKeyWire(packet.requestId, packet.requestVersion)) && packet.envelopeId.length in 1..128 && packet.ciphertextSha256Hex.length == 64 && packet.exportedHopCount in 1..32)
    }

    private fun validKey(key: RescueRequestKeyWire): Boolean = key.requestId.length in 1..128 &&
        key.requestId.all { it.isLetterOrDigit() || it in "-_.:" } && key.requestVersion in 1..1_000_000

    private companion object {
        val PREFIX = byteArrayOf('R'.code.toByte(), 'S'.code.toByte(), 'Q'.code.toByte(), 1)
    }
}
