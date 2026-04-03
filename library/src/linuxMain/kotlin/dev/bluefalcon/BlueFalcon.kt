package dev.bluefalcon

import com.monkopedia.sdbus.InterfaceName
import com.monkopedia.sdbus.ObjectPath
import com.monkopedia.sdbus.Resource
import com.monkopedia.sdbus.ServiceName
import com.monkopedia.sdbus.SignalName
import com.monkopedia.sdbus.Variant
import com.monkopedia.sdbus.createObject
import com.monkopedia.sdbus.createProxy
import com.monkopedia.sdbus.createSystemBusConnection
import com.monkopedia.sdbus.onSignal
import dev.bluefalcon.bluez.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    actual val scope = CoroutineScope(Dispatchers.Default)
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()

    private fun notifyDelegates(action: (BlueFalconDelegate) -> Unit) {
        delegates.toList().forEach(action)
    }
    actual var isScanning: Boolean = false

    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    actual val managerState: StateFlow<BluetoothManagerState> =
        MutableStateFlow(BluetoothManagerState.Ready)

    companion object {
        private var pendingShutdown: Job? = null
    }

    private val connection = createSystemBusConnection()
    private val bluezService = ServiceName("org.bluez")
    private val adapterPath = ObjectPath("/org/bluez/hci0")
    private val bluezRoot = ObjectPath("/")
    private lateinit var adapterProxy: Adapter1Proxy
    private lateinit var objectManagerProxy: ObjectManagerProxy

    private val knownPeripherals = mutableMapOf<ObjectPath, BluetoothPeripheralImpl>()
    private val propertiesListeners = mutableMapOf<ObjectPath, Resource>()
    private var scanJob: Job? = null
    private val agentPath = ObjectPath("/dev/bluefalcon/agent")

    private val initJob = scope.launch {
        // Wait for any previous instance's event loop to shut down
        pendingShutdown?.join()
        pendingShutdown = null

        adapterProxy = Adapter1Proxy(
            createProxy(connection, bluezService, adapterPath)
        )
        objectManagerProxy = ObjectManagerProxy(
            createProxy(connection, bluezService, bluezRoot)
        )
        connection.enterEventLoopAsync()
        registerAgent()
    }

    private fun registerAgent() {
        val agent = NoInputNoOutputAgent(createObject(connection, agentPath))
        agent.register()
        val agentManager = AgentManager1Proxy(
            createProxy(connection, bluezService, ObjectPath("/org/bluez"))
        )
        scope.launch {
            try {
                agentManager.registerAgent(agentPath, "NoInputNoOutput")
                agentManager.requestDefaultAgent(agentPath)
                log?.info("Registered NoInputNoOutput pairing agent")
            } catch (e: Exception) {
                log?.error("Failed to register pairing agent: ${e.message}", e)
            }
        }
    }

    actual fun scan(filters: List<ServiceFilter>) {
        log?.info("Scan started with filters: $filters")
        isScanning = true

        scope.launch {
            initJob.join()
            try {
                val filterMap = mutableMapOf<String, Variant>(
                    "Transport" to Variant("le"),
                    "DuplicateData" to Variant(false),
                )
                if (filters.isNotEmpty()) {
                    val uuids = filters.flatMap { it.serviceUuids }.map { it.toString() }
                    if (uuids.isNotEmpty()) {
                        filterMap["UUIDs"] = Variant(uuids)
                    }
                }
                try {
                    adapterProxy.setDiscoveryFilter(filterMap)
                } catch (e: Exception) {
                    log?.debug("setDiscoveryFilter failed (may already be set): ${e.message}")
                }

                try {
                    adapterProxy.startDiscovery()
                } catch (e: Exception) {
                    log?.debug("startDiscovery failed (may already be discovering): ${e.message}")
                }

                // Poll GetManagedObjects to discover devices.
                // InterfacesAdded signals are unreliable for devices that
                // BlueZ has recently seen, so we poll periodically.
                scanJob = scope.launch {
                    while (isScanning) {
                        try {
                            val managed = objectManagerProxy.getManagedObjects()
                            var deviceCount = 0
                            for ((path, interfaces) in managed) {
                                if (!path.value.startsWith(adapterPath.value + "/dev_")) continue
                                val devProps = interfaces["org.bluez.Device1"] ?: continue
                                deviceCount++
                                handleDeviceFound(path, devProps)
                            }
                            log?.debug("Scan poll: found $deviceCount devices")
                        } catch (e: Exception) {
                            log?.error("Scan poll error: ${e.message}", e)
                        }
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                log?.error("Scan failed: ${e.message}", e)
                isScanning = false
            }
        }
    }

    actual fun stopScanning() {
        log?.info("Scan stopped")
        isScanning = false
        scope.launch {
            try {
                adapterProxy.stopDiscovery()
            } catch (_: Exception) {}
            scanJob?.cancel()
            scanJob = null
        }
    }

    actual fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        log?.info("Connecting to ${impl.uuid}")

        scope.launch {
            initJob.join()
            try {
                val deviceProxy = Device1Proxy(
                    createProxy(connection, bluezService, impl.device.objectPath)
                )

                // Set up property change listener
                val listener = deviceProxy.proxy.onSignal(
                    InterfaceName("org.freedesktop.DBus.Properties"),
                    SignalName("PropertiesChanged")
                ) {
                    call { iface: String, changed: Map<String, Variant>, _: List<String> ->
                        if (iface == "org.bluez.Device1") {
                            handleDevicePropertyChanged(impl, changed)
                        }
                    }
                }
                propertiesListeners[impl.device.objectPath] = listener

                // TODO: decide how to handle devices already connected in BlueZ
                // from a previous process. Currently this will fail if the device
                // is already connected.
                deviceProxy.connect()
            } catch (e: Exception) {
                log?.error("Connect failed: ${e.message}", e)
            }
        }
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            // Use NonCancellable so scope.cancel() in destroy() doesn't
            // kill the D-Bus disconnect call mid-flight
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val deviceProxy = Device1Proxy(
                        createProxy(connection, bluezService, impl.device.objectPath)
                    )
                    deviceProxy.disconnect()
                    propertiesListeners.remove(impl.device.objectPath)?.release()
                } catch (e: Exception) {
                    log?.error("Disconnect failed: ${e.message}", e)
                }
            }
            notifyDelegates { it.didDisconnect(bluetoothPeripheral) }
        }
    }

    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        val devPath = ObjectPath(
            "${adapterPath.value}/dev_${identifier.replace(":", "_")}"
        )
        return knownPeripherals[devPath]
    }

    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        return try {
            val deviceProxy = Device1Proxy(
                createProxy(connection, bluezService, impl.device.objectPath)
            )
            if (deviceProxy.connected) BluetoothPeripheralState.Connected
            else BluetoothPeripheralState.Disconnected
        } catch (_: Exception) {
            BluetoothPeripheralState.Unknown
        }
    }

    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) { /* No BlueZ equivalent */ }

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            resolveGattObjects(impl)
            notifyDelegates { it.didDiscoverServices(bluetoothPeripheral) }
        }
    }

    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        notifyDelegates { it.didDiscoverCharacteristics(bluetoothPeripheral) }
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        scope.launch {
            try {
                val charProxy = GattCharacteristic1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristic.objectPath)
                )
                val value = charProxy.readValue(emptyMap())
                bluetoothCharacteristic._value = value.toUByteArray().asByteArray()
                notifyDelegates {
                    it.didCharacteristcValueChanged(bluetoothPeripheral, bluetoothCharacteristic)
                }
            } catch (e: Exception) {
                log?.error("Read characteristic failed: ${e.message}", e)
            }
        }
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristicWithoutEncoding(
            bluetoothPeripheral, bluetoothCharacteristic,
            value.encodeToByteArray(), writeType
        )
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCharacteristicWithoutEncoding(
            bluetoothPeripheral, bluetoothCharacteristic, value, writeType
        )
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        scope.launch {
            try {
                val charProxy = GattCharacteristic1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristic.objectPath)
                )
                val options = mutableMapOf<String, Variant>()
                if (writeType == 1) {
                    options["type"] = Variant("command")
                } else {
                    options["type"] = Variant("request")
                }
                charProxy.writeValue(value.asUByteArray().toList(), options)
                bluetoothCharacteristic._value = value
                notifyDelegates {
                    it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true)
                }
            } catch (e: Exception) {
                log?.error("Write failed: ${e.message}", e)
                notifyDelegates {
                    it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false)
                }
            }
        }
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        scope.launch {
            try {
                val charProxy = GattCharacteristic1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristic.objectPath)
                )
                if (notify) {
                    val listener = charProxy.proxy.onSignal(
                        InterfaceName("org.freedesktop.DBus.Properties"),
                        SignalName("PropertiesChanged")
                    ) {
                        call { iface: String, changed: Map<String, Variant>, _: List<String> ->
                            if (iface == "org.bluez.GattCharacteristic1" && "Value" in changed) {
                                val bytes = changed["Value"]?.get<List<UByte>>()
                                    ?.toUByteArray()?.asByteArray()
                                if (bytes != null) {
                                    bluetoothCharacteristic._value = bytes
                                    notifyDelegates {
                                        it.didCharacteristcValueChanged(
                                            bluetoothPeripheral, bluetoothCharacteristic
                                        )
                                    }
                                }
                            }
                        }
                    }
                    propertiesListeners[bluetoothCharacteristic.objectPath] = listener
                    charProxy.startNotify()
                    bluetoothCharacteristic._isNotifying = true
                } else {
                    charProxy.stopNotify()
                    bluetoothCharacteristic._isNotifying = false
                    propertiesListeners.remove(bluetoothCharacteristic.objectPath)?.release()
                }
                notifyDelegates {
                    it.didUpdateNotificationStateFor(bluetoothPeripheral, bluetoothCharacteristic)
                }
            } catch (e: Exception) {
                log?.error("Notify failed: ${e.message}", e)
            }
        }
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // BlueZ StartNotify handles both notify and indicate
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        scope.launch {
            try {
                val descProxy = GattDescriptor1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristicDescriptor.objectPath)
                )
                val value = descProxy.readValue(emptyMap())
                bluetoothCharacteristicDescriptor._value = value.toUByteArray().asByteArray()
                notifyDelegates {
                    it.didReadDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor)
                }
            } catch (e: Exception) {
                log?.error("Read descriptor failed: ${e.message}", e)
            }
        }
    }

    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        scope.launch {
            try {
                val descProxy = GattDescriptor1Proxy(
                    createProxy(connection, bluezService, bluetoothCharacteristicDescriptor.objectPath)
                )
                descProxy.writeValue(value.asUByteArray().toList(), emptyMap())
                bluetoothCharacteristicDescriptor._value = value
                notifyDelegates {
                    it.didWriteDescriptor(
                        bluetoothPeripheral as BluetoothPeripheralImpl,
                        bluetoothCharacteristicDescriptor
                    )
                }
            } catch (e: Exception) {
                log?.error("Write descriptor failed: ${e.message}", e)
            }
        }
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                val firstChar = impl.services.values.flatMap { it.characteristics }.firstOrNull()
                if (firstChar != null) {
                    val charProxy = GattCharacteristic1Proxy(
                        createProxy(connection, bluezService, firstChar.objectPath)
                    )
                    impl.mtuSize = charProxy.mTU.toInt()
                }
                notifyDelegates { it.didUpdateMTU(bluetoothPeripheral, 0) }
            } catch (e: Exception) {
                log?.error("changeMTU failed: ${e.message}", e)
                notifyDelegates { it.didUpdateMTU(bluetoothPeripheral, -1) }
            }
        }
    }

    actual fun openL2capChannel(bluetoothPeripheral: BluetoothPeripheral, psm: Int) {
        notifyDelegates { it.didOpenL2capChannel(bluetoothPeripheral, null) }
    }

    actual fun createBond(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                val deviceProxy = Device1Proxy(
                    createProxy(connection, bluezService, impl.device.objectPath)
                )
                deviceProxy.pair()
                // didBondStateChanged fires from PropertiesChanged handler
                // when Paired property becomes true
            } catch (e: Exception) {
                log?.error("Pair failed: ${e.message}", e)
                notifyDelegates {
                    it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.None)
                }
            }
        }
    }

    actual fun removeBond(bluetoothPeripheral: BluetoothPeripheral) {
        val impl = bluetoothPeripheral as BluetoothPeripheralImpl
        scope.launch {
            try {
                adapterProxy.removeDevice(impl.device.objectPath)
                notifyDelegates {
                    it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.None)
                }
            } catch (e: Exception) {
                log?.error("Remove bond failed: ${e.message}", e)
            }
        }
    }

    actual fun destroy() {
        isScanning = false
        scanJob?.cancel()
        scanJob = null
        val listeners = propertiesListeners.toMap()
        propertiesListeners.clear()
        listeners.values.forEach { try { it.release() } catch (_: Exception) {} }
        // Kick off event loop shutdown — the next BlueFalcon instance
        // will await this in its initJob before using the D-Bus connection
        pendingShutdown = CoroutineScope(Dispatchers.IO).launch {
            connection.leaveEventLoop()
        }
        scope.cancel()
    }

    // ---- Internal ----

    private fun handleDeviceFound(path: ObjectPath, properties: Map<String, Variant>) {
        if (!path.value.startsWith(adapterPath.value)) return

        val device = NativeBluetoothDevice(path)
        val peripheral = knownPeripherals.getOrPut(path) { BluetoothPeripheralImpl(device) }

        properties["Name"]?.let { peripheral._name = it.get<String>() }
        properties["RSSI"]?.let { peripheral.rssi = it.get<Short>().toFloat() }

        _peripherals.tryEmit(_peripherals.value + setOf(peripheral))

        val advData = mutableMapOf<AdvertisementDataRetrievalKeys, Any>()
        properties["Name"]?.let { advData[AdvertisementDataRetrievalKeys.LocalName] = it.get<String>() }
        advData[AdvertisementDataRetrievalKeys.IsConnectable] = 1
        notifyDelegates { it.didDiscoverDevice(peripheral, advData) }
    }

    private fun handleDevicePropertyChanged(
        peripheral: BluetoothPeripheralImpl,
        changed: Map<String, Variant>
    ) {
        changed["Connected"]?.let { v ->
            if (v.get<Boolean>()) {
                notifyDelegates { it.didConnect(peripheral) }
                if (autoDiscoverAllServicesAndCharacteristics) {
                    scope.launch { waitForServicesResolved(peripheral) }
                }
            } else {
                notifyDelegates { it.didDisconnect(peripheral) }
            }
        }
        changed["RSSI"]?.let {
            peripheral.rssi = it.get<Short>().toFloat()
            notifyDelegates { it.didRssiUpdate(peripheral) }
        }
        changed["Paired"]?.let { v ->
            val paired = v.get<Boolean>()
            notifyDelegates {
                it.didBondStateChanged(
                    peripheral,
                    if (paired) BlueFalconBondState.Bonded else BlueFalconBondState.None
                )
            }
        }
        changed["ServicesResolved"]?.let { v ->
            if (v.get<Boolean>()) {
                scope.launch {
                    resolveGattObjects(peripheral)
                    notifyDelegates { it.didDiscoverServices(peripheral) }
                }
            }
        }
    }

    private suspend fun waitForServicesResolved(peripheral: BluetoothPeripheralImpl) {
        try {
            val deviceProxy = Device1Proxy(
                createProxy(connection, bluezService, peripheral.device.objectPath)
            )
            if (deviceProxy.servicesResolved) {
                resolveGattObjects(peripheral)
                notifyDelegates { it.didDiscoverServices(peripheral) }
            }
        } catch (e: Exception) {
            log?.error("waitForServicesResolved failed: ${e.message}", e)
        }
    }

    private suspend fun resolveGattObjects(peripheral: BluetoothPeripheralImpl) {
        try {
            val managed = objectManagerProxy.getManagedObjects()
            val devPrefix = peripheral.device.objectPath.value

            val services = mutableListOf<BluetoothService>()
            val characteristics = mutableMapOf<ObjectPath, BluetoothCharacteristic>()

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val svcProps = interfaces["org.bluez.GattService1"] ?: continue
                val uuidStr = svcProps["UUID"]?.get<String>() ?: continue
                services.add(BluetoothService(path, Uuid.parse(uuidStr)))
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val charProps = interfaces["org.bluez.GattCharacteristic1"] ?: continue
                val uuidStr = charProps["UUID"]?.get<String>() ?: continue
                val svcPath = charProps["Service"]?.get<ObjectPath>() ?: continue
                val char = BluetoothCharacteristic(path, Uuid.parse(uuidStr), svcPath)
                val parent = services.find { it.objectPath == svcPath }
                if (parent != null) {
                    parent.addCharacteristic(char)
                    char.setService(parent)
                }
                characteristics[path] = char
            }

            for ((path, interfaces) in managed) {
                if (!path.value.startsWith("$devPrefix/")) continue
                val descProps = interfaces["org.bluez.GattDescriptor1"] ?: continue
                val uuidStr = descProps["UUID"]?.get<String>() ?: continue
                val charPath = descProps["Characteristic"]?.get<ObjectPath>() ?: continue
                val desc = BluetoothCharacteristicDescriptor(path, Uuid.parse(uuidStr), charPath)
                characteristics[charPath]?.addDescriptor(desc)
            }

            peripheral._servicesFlow.tryEmit(services)
        } catch (e: Exception) {
            log?.error("resolveGattObjects failed: ${e.message}", e)
        }
    }
}
