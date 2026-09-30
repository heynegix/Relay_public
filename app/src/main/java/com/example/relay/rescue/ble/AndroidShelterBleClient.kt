package com.example.relay.rescue.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/** Real GATT central for the packaged Windows PC BLE bridge. */
class AndroidShelterBleClient(context: Context) : ShelterBleClient {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)

    @SuppressLint("MissingPermission")
    override val advertisements: Flow<ShelterAdvertisement> = callbackFlow {
        val scanner = manager?.adapter?.takeIf { it.state == BluetoothAdapter.STATE_ON }?.bluetoothLeScanner
            ?: run { close(); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val raw = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                val identity = ShelterBleIdentity.decode(raw) ?: return
                // The name/shelter ID is intentionally absent from this metadata. A trusted
                // directory resolves hash+fingerprint only after the characteristic re-read.
                trySend(ShelterAdvertisement(shelterId = "", peerId = result.device.address, identity = identity))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan unavailable"))
            }
        }
        runCatching {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback,
            )
        }.onFailure { close(it) }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(advertisement: ShelterAdvertisement): ShelterBleSession {
        val expectedIdentity = advertisement.identity ?: throw SecurityException("missing bridge identity")
        val device = manager?.adapter?.getRemoteDevice(advertisement.peerId) ?: throw IllegalStateException("BLE adapter unavailable")
        return AndroidGattShelterBleSession.connect(appContext, device, expectedIdentity)
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01")
    }
}

// GATT reads/writes require BLUETOOTH_CONNECT. A session is only ever constructed by
// AndroidShelterBleClient.connect(), which the caller invokes behind the app's Bluetooth
// permission gate, so every call site here is already permission-checked.
@SuppressLint("MissingPermission")
private class AndroidGattShelterBleSession private constructor(
    private val gatt: BluetoothGatt,
    private val expectedIdentity: ShelterBleIdentity,
    private val callback: Callback,
) : ShelterBleSession {
    private val identityCharacteristic = requireNotNull(gatt.getService(SERVICE_UUID)?.getCharacteristic(IDENTITY_CHARACTERISTIC_UUID))
    private val uplinkCharacteristic = requireNotNull(gatt.getService(SERVICE_UUID)?.getCharacteristic(UPLINK_CHARACTERISTIC_UUID))
    private val downlinkCharacteristic = requireNotNull(gatt.getService(SERVICE_UUID)?.getCharacteristic(DOWNLINK_CHARACTERISTIC_UUID))
    private var closed = false

    override suspend fun readIdentity(): ShelterBleIdentity {
        check(!closed)
        val completion = callback.beginIdentityRead()
        if (!gatt.readCharacteristic(identityCharacteristic)) {
            callback.cancelIdentityRead(completion)
            throw IllegalStateException("identity read could not start")
        }
        val identity = completion.await()
        if (!expectedIdentity.sameWireIdentity(identity)) throw SecurityException("bridge identity changed")
        return identity
    }

    override suspend fun submit(submission: RescueBleSubmission): RescueBleSubmissionResult {
        check(!closed)
        // A durable courier id maps deterministically to the PC bridge's four-byte session
        // identifier, so retrying after an indication loss replays the same idempotent intake.
        val sessionId = RescueBleFrameCodec.sessionIdFor(submission.courierDeliveryId)
        subscribeToResults()
        try {
            writeFrames(RescueBleFrameCodec.startFrames(submission.courierDeliveryId, submission.carrierId, sessionId))
            writeFrames(RescueBleFrameCodec.chunkFrames(submission.encryptedEnvelope, sessionId))
            writeFrames(RescueBleFrameCodec.commitFrames(submission.encryptedEnvelope, sessionId))
            val resultBytes = receiveResult(sessionId)
            val receipt = runCatching {
                JSON.decodeFromString<com.example.relay.rescue.SignedShelterReceipt>(resultBytes.decodeToString())
            }.getOrNull() ?: return RescueBleSubmissionResult.Rejected("shelter_rejected")
            return RescueBleSubmissionResult.Accepted(receipt)
        } catch (cancelled: CancellationException) {
            runCatching { writeFrames(listOf(RescueBleFrameCodec.abort(sessionId))) }
            throw cancelled
        } catch (failure: Exception) {
            runCatching { writeFrames(listOf(RescueBleFrameCodec.abort(sessionId))) }
            throw failure
        }
    }

    private suspend fun subscribeToResults() {
        if (!gatt.setCharacteristicNotification(downlinkCharacteristic, true)) throw IllegalStateException("result indication unavailable")
        val descriptor = downlinkCharacteristic.getDescriptor(CLIENT_CONFIGURATION_UUID)
            ?: throw IllegalStateException("result indication descriptor unavailable")
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val completion = callback.beginDescriptorWrite()
        if (!gatt.writeDescriptor(descriptor)) {
            callback.cancelDescriptorWrite(completion)
            throw IllegalStateException("result indication subscription failed")
        }
        if (!completion.await()) throw IllegalStateException("result indication subscription rejected")
    }

    private suspend fun writeFrames(frames: List<ByteArray>) {
        frames.forEach { frame ->
            uplinkCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            uplinkCharacteristic.value = frame
            val completion = callback.beginCharacteristicWrite()
            if (!gatt.writeCharacteristic(uplinkCharacteristic)) {
                callback.cancelCharacteristicWrite(completion)
                throw IllegalStateException("uplink write could not start")
            }
            if (!completion.await()) throw IllegalStateException("uplink write rejected")
        }
    }

    private suspend fun receiveResult(sessionId: ByteArray): ByteArray = withTimeout(RESULT_TIMEOUT_MILLIS) {
        val pieces = mutableListOf<ByteArray>()
        var expectedSequence = 0
        var total: Int? = null
        while (true) {
            val frame = callback.indications.receiveCatching().getOrNull() ?: throw IllegalStateException("result indication closed")
            if (frame.kind != RescueBleFrameCodec.Kind.RESULT || !frame.sessionId.contentEquals(sessionId)) continue
            if (frame.total == 0 || frame.sequence != expectedSequence || (total != null && frame.total != total)) {
                throw SecurityException("invalid result frame ordering")
            }
            total = frame.total
            pieces += frame.payload
            expectedSequence++
            if (expectedSequence == total) return@withTimeout pieces.fold(byteArrayOf()) { acc, piece -> acc + piece }
        }
        error("unreachable result receive loop")
    }

    override fun close() {
        if (!closed) {
            closed = true
            callback.close()
            gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private class Callback(private val expectedIdentity: ShelterBleIdentity) : BluetoothGattCallback() {
        val connected = CompletableDeferred<BluetoothGatt>()
        val services = CompletableDeferred<Unit>()
        val indications = Channel<RescueBleFrameCodec.Frame>(Channel.BUFFERED)
        private val operationLock = Any()
        private var identityRead: CompletableDeferred<ShelterBleIdentity>? = null
        private var descriptorWrite: CompletableDeferred<Boolean>? = null
        private var characteristicWrite: CompletableDeferred<Boolean>? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> fail(IllegalStateException("BLE connection failed"))
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    connected.complete(gatt)
                    if (!gatt.discoverServices()) fail(IllegalStateException("BLE discovery could not start"))
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> fail(IllegalStateException("BLE disconnected"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt.getService(SERVICE_UUID) == null) fail(IllegalStateException("Relay GATT service unavailable"))
            else services.complete(Unit)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != IDENTITY_CHARACTERISTIC_UUID) return
            val completion = synchronized(operationLock) {
                identityRead.also { identityRead = null }
            } ?: return
            val identity = characteristic.value?.let(ShelterBleIdentity::decode)
            if (status == BluetoothGatt.GATT_SUCCESS && identity != null && expectedIdentity.sameWireIdentity(identity)) completion.complete(identity)
            else completion.completeExceptionally(SecurityException("invalid bridge identity"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != UPLINK_CHARACTERISTIC_UUID) return
            synchronized(operationLock) {
                characteristicWrite.also { characteristicWrite = null }
            }?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CONFIGURATION_UUID) return
            synchronized(operationLock) {
                descriptorWrite.also { descriptorWrite = null }
            }?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != DOWNLINK_CHARACTERISTIC_UUID) return
            RescueBleFrameCodec.decode(characteristic.value ?: return)?.let { indications.trySend(it) }
        }

        fun beginIdentityRead() = CompletableDeferred<ShelterBleIdentity>().also { completion -> synchronized(operationLock) { check(identityRead == null); identityRead = completion } }
        fun cancelIdentityRead(completion: CompletableDeferred<ShelterBleIdentity>) = synchronized(operationLock) { if (identityRead === completion) identityRead = null }
        fun beginDescriptorWrite() = CompletableDeferred<Boolean>().also { completion -> synchronized(operationLock) { check(descriptorWrite == null); descriptorWrite = completion } }
        fun cancelDescriptorWrite(completion: CompletableDeferred<Boolean>) = synchronized(operationLock) { if (descriptorWrite === completion) descriptorWrite = null }
        fun beginCharacteristicWrite() = CompletableDeferred<Boolean>().also { completion -> synchronized(operationLock) { check(characteristicWrite == null); characteristicWrite = completion } }
        fun cancelCharacteristicWrite(completion: CompletableDeferred<Boolean>) = synchronized(operationLock) { if (characteristicWrite === completion) characteristicWrite = null }

        fun close() { indications.close(); fail(IllegalStateException("BLE session closed")) }
        private fun fail(error: Throwable) {
            if (!connected.isCompleted) connected.completeExceptionally(error)
            if (!services.isCompleted) services.completeExceptionally(error)
            val pending = synchronized(operationLock) {
                listOf(identityRead, descriptorWrite, characteristicWrite).also {
                    identityRead = null; descriptorWrite = null; characteristicWrite = null
                }
            }
            pending.forEach { it?.completeExceptionally(error) }
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01")
        private val IDENTITY_CHARACTERISTIC_UUID: UUID = UUID.fromString("1f7f7e91-4e0a-4b0b-8fad-1e3c5e3f4a01")
        private val UPLINK_CHARACTERISTIC_UUID: UUID = UUID.fromString("1f7f7e92-4e0a-4b0b-8fad-1e3c5e3f4a01")
        private val DOWNLINK_CHARACTERISTIC_UUID: UUID = UUID.fromString("1f7f7e93-4e0a-4b0b-8fad-1e3c5e3f4a01")
        private val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val RESULT_TIMEOUT_MILLIS = 30_000L
        private val JSON = Json { ignoreUnknownKeys = false }

        @SuppressLint("MissingPermission")
        suspend fun connect(context: Context, device: BluetoothDevice, expectedIdentity: ShelterBleIdentity): AndroidGattShelterBleSession {
            val callback = Callback(expectedIdentity)
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("BLE connection could not start")
            try {
                callback.connected.await()
                callback.services.await()
                return AndroidGattShelterBleSession(gatt, expectedIdentity, callback)
            } catch (error: Throwable) {
                callback.close(); gatt.close(); throw error
            }
        }
    }
}
