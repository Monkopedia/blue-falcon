package dev.bluefalcon.integration

import dev.bluefalcon.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * Bridges BlueFalcon's callback-based API to suspending functions for tests.
 *
 * One-shot operations (connect, write, MTU) use [CompletableDeferred].
 * Streaming operations (scan, notifications) use [Channel].
 */
class BlueFalconTestHarness(
    val falcon: BlueFalcon,
    private val defaultTimeoutMs: Long = 15_000L
) : BlueFalconDelegate {

    private var connectDeferred: CompletableDeferred<BluetoothPeripheral>? = null
    private var disconnectDeferred: CompletableDeferred<BluetoothPeripheral>? = null
    private var discoverServicesDeferred: CompletableDeferred<BluetoothPeripheral>? = null
    private var writeCharDeferred: CompletableDeferred<Pair<BluetoothCharacteristic, Boolean>>? = null
    private var readDescriptorDeferred: CompletableDeferred<BluetoothCharacteristicDescriptor>? = null
    private var writeDescriptorDeferred: CompletableDeferred<BluetoothCharacteristicDescriptor>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var bondDeferred: CompletableDeferred<BlueFalconBondState>? = null
    private var l2capDeferred: CompletableDeferred<BluetoothSocket?>? = null
    private var notifyStateDeferred: CompletableDeferred<BluetoothCharacteristic>? = null
    private var notifyStateTargetUuid: Uuid? = null

    private val charValueChannel = Channel<Pair<BluetoothPeripheral, BluetoothCharacteristic>>(Channel.BUFFERED)
    private val scanChannel = Channel<Pair<BluetoothPeripheral, Map<AdvertisementDataRetrievalKeys, Any>>>(Channel.BUFFERED)

    init {
        falcon.delegates.add(this)
    }

    fun destroy() {
        falcon.delegates.remove(this)
        charValueChannel.close()
        scanChannel.close()
    }

    // ---- Suspending API ----

    suspend fun scanForDevice(
        filters: List<ServiceFilter> = emptyList(),
        timeoutMs: Long = defaultTimeoutMs,
        predicate: (BluetoothPeripheral, Map<AdvertisementDataRetrievalKeys, Any>) -> Boolean
    ): BluetoothPeripheral = withTimeout(timeoutMs) {
        while (scanChannel.tryReceive().isSuccess) { /* drain */ }
        falcon.scan(filters)
        try {
            while (true) {
                val (peripheral, advData) = scanChannel.receive()
                if (predicate(peripheral, advData)) {
                    falcon.stopScanning()
                    return@withTimeout peripheral
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: kotlinx.coroutines.CancellationException) {
            falcon.stopScanning()
            throw e
        }
    }

    /**
     * Connect and wait for service discovery to complete.
     * Sets up the discovery deferred BEFORE connecting because Android's
     * auto-discover fires inside onConnectionStateChange.
     */
    suspend fun connectAndDiscover(
        peripheral: BluetoothPeripheral,
        timeoutMs: Long = 20_000L
    ): BluetoothPeripheral = withTimeout(timeoutMs) {
        connectDeferred = CompletableDeferred()
        discoverServicesDeferred = CompletableDeferred()
        falcon.connect(peripheral)
        connectDeferred!!.await()
        discoverServicesDeferred!!.await()
    }

    suspend fun disconnectAndAwait(
        peripheral: BluetoothPeripheral,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothPeripheral = withTimeout(timeoutMs) {
        disconnectDeferred = CompletableDeferred()
        falcon.disconnect(peripheral)
        disconnectDeferred!!.await()
    }

    suspend fun readCharacteristicAndAwait(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothCharacteristic = withTimeout(timeoutMs) {
        while (charValueChannel.tryReceive().isSuccess) { /* drain */ }
        falcon.readCharacteristic(peripheral, characteristic)
        while (true) {
            val (_, changed) = charValueChannel.receive()
            if (changed.uuid == characteristic.uuid) {
                return@withTimeout changed
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun writeCharacteristicAndAwait(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int? = null,
        timeoutMs: Long = defaultTimeoutMs
    ): Boolean = withTimeout(timeoutMs) {
        writeCharDeferred = CompletableDeferred()
        falcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, value, writeType)
        val (_, success) = writeCharDeferred!!.await()
        success
    }

    suspend fun enableNotifyAndAwait(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        timeoutMs: Long = defaultTimeoutMs
    ) = withTimeout(timeoutMs) {
        notifyStateTargetUuid = characteristic.uuid
        notifyStateDeferred = CompletableDeferred()
        falcon.notifyCharacteristic(peripheral, characteristic, true)
        notifyStateDeferred!!.await()
    }

    suspend fun disableNotify(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        falcon.notifyCharacteristic(peripheral, characteristic, false)
    }

    suspend fun enableIndicateAndAwait(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        timeoutMs: Long = defaultTimeoutMs
    ) = withTimeout(timeoutMs) {
        notifyStateTargetUuid = characteristic.uuid
        notifyStateDeferred = CompletableDeferred()
        falcon.indicateCharacteristic(peripheral, characteristic, true)
        notifyStateDeferred!!.await()
    }

    suspend fun awaitCharacteristicValue(
        targetUuid: Uuid,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothCharacteristic = withTimeout(timeoutMs) {
        while (true) {
            val (_, changed) = charValueChannel.receive()
            if (changed.uuid == targetUuid) {
                return@withTimeout changed
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun collectNotifications(
        targetUuid: Uuid,
        count: Int,
        timeoutMs: Long = defaultTimeoutMs
    ): List<ByteArray> = withTimeout(timeoutMs) {
        val results = mutableListOf<ByteArray>()
        while (results.size < count) {
            val (_, changed) = charValueChannel.receive()
            if (changed.uuid == targetUuid) {
                changed.value?.let { results.add(it.copyOf()) }
            }
        }
        results
    }

    suspend fun readDescriptorAndAwait(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothCharacteristicDescriptor = withTimeout(timeoutMs) {
        readDescriptorDeferred = CompletableDeferred()
        falcon.readDescriptor(peripheral, characteristic, descriptor)
        readDescriptorDeferred!!.await()
    }

    suspend fun writeDescriptorAndAwait(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothCharacteristicDescriptor = withTimeout(timeoutMs) {
        writeDescriptorDeferred = CompletableDeferred()
        falcon.writeDescriptor(peripheral, descriptor, value)
        writeDescriptorDeferred!!.await()
    }

    suspend fun openL2capAndAwait(
        peripheral: BluetoothPeripheral,
        psm: Int,
        timeoutMs: Long = defaultTimeoutMs
    ): BluetoothSocket? = withTimeout(timeoutMs) {
        l2capDeferred = CompletableDeferred()
        falcon.openL2capChannel(peripheral, psm)
        l2capDeferred!!.await()
    }

    suspend fun changeMtuAndAwait(
        peripheral: BluetoothPeripheral,
        mtuSize: Int,
        timeoutMs: Long = defaultTimeoutMs
    ): Int = withTimeout(timeoutMs) {
        mtuDeferred = CompletableDeferred()
        falcon.changeMTU(peripheral, mtuSize)
        mtuDeferred!!.await()
    }

    suspend fun createBondAndAwait(
        peripheral: BluetoothPeripheral,
        timeoutMs: Long = 30_000L
    ): BlueFalconBondState = withTimeout(timeoutMs) {
        bondDeferred = CompletableDeferred()
        falcon.createBond(peripheral)
        bondDeferred!!.await()
    }

    // ---- Delegate callbacks ----

    override fun didDiscoverDevice(
        bluetoothPeripheral: BluetoothPeripheral,
        advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
    ) {
        scanChannel.trySend(bluetoothPeripheral to advertisementData)
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        connectDeferred?.complete(bluetoothPeripheral)
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        disconnectDeferred?.complete(bluetoothPeripheral)
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        discoverServicesDeferred?.complete(bluetoothPeripheral)
    }

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        charValueChannel.trySend(bluetoothPeripheral to bluetoothCharacteristic)
    }

    override fun didWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        success: Boolean
    ) {
        writeCharDeferred?.complete(bluetoothCharacteristic to success)
    }

    override fun didUpdateNotificationStateFor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        val target = notifyStateTargetUuid
        if (target == null || bluetoothCharacteristic.uuid == target) {
            notifyStateDeferred?.complete(bluetoothCharacteristic)
        }
    }

    override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral, status: Int) {
        mtuDeferred?.complete(status)
    }

    override fun didBondStateChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        state: BlueFalconBondState
    ) {
        if (state == BlueFalconBondState.Bonded) {
            bondDeferred?.complete(state)
        }
    }

    override fun didOpenL2capChannel(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothSocket: BluetoothSocket?
    ) {
        l2capDeferred?.complete(bluetoothSocket)
    }

    override fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        readDescriptorDeferred?.complete(bluetoothCharacteristicDescriptor)
    }

    override fun didWriteDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        writeDescriptorDeferred?.complete(bluetoothCharacteristicDescriptor)
    }
}
