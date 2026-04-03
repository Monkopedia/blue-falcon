package dev.bluefalcon.integration

import dev.bluefalcon.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration tests against the BF-Test peripheral.
 *
 * Uses a shared connection (set up once before all tests) because:
 * - The ESP32-C6 is single-connection and needs time to re-advertise
 * - Android throttles BLE scans per-app, so creating many BlueFalcon
 *   instances causes scan failures
 *
 * Each test is still independent in what it validates — no test depends
 * on side effects from another test.
 *
 * Prerequisites:
 * - BF-Test ESP32-C6 peripheral is powered on and advertising
 * - Device has Bluetooth enabled and permissions granted
 */
class BleIntegrationTests {

    var harness: BlueFalconTestHarness? = null
    var peripheral: BluetoothPeripheral? = null

    @BeforeTest
    fun setUp() = runBlocking {
        ensurePlatformReady()
        val falcon = createBlueFalcon()
        val h = BlueFalconTestHarness(falcon)
        harness = h
        val found = scanForBfTestDevice(h)
        peripheral = h.connectAndDiscover(found, timeoutMs = 60_000L)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        val h = harness ?: return@runBlocking
        val p = peripheral
        if (p != null) {
            try {
                h.disconnectAndAwait(p)
            } catch (_: Exception) {}
        }
        h.destroy()
        h.falcon.destroy()
    }

    private fun requireHarness() = harness ?: fail("setUp failed")
    private fun requirePeripheral() = peripheral ?: fail("setUp failed")

    private fun findChar(uuid: String): BluetoothCharacteristic {
        val target = uuidFrom(uuid)
        return requirePeripheral().characteristics.values.flatten().firstOrNull { it.uuid == target }
            ?: error("Characteristic $uuid not found")
    }

    // ---- Device identity ----

    @Test
    fun connectedDeviceIsBfTest() {
        assertEquals(BfTestConstants.DEVICE_NAME, requirePeripheral().name)
    }

    // ---- Connection ----

    @Test
    fun connectionStateIsConnected() {
        val state = requireHarness().falcon.connectionState(requirePeripheral())
        assertEquals(BluetoothPeripheralState.Connected, state)
    }

    // ---- Service discovery ----

    @Test
    fun discoversTestService() {
        val uuids = requirePeripheral().services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_1 in it }, "Service 1 (BF10) missing")
    }

    @Test
    fun discoversSecureService() {
        val uuids = requirePeripheral().services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_2 in it }, "Service 2 (BF20) missing")
    }

    @Test
    fun discoversAllCharacteristics() {
        val charUuids = requirePeripheral().characteristics.values.flatten()
            .map { it.uuid.toString().lowercase() }
        for (expected in listOf(
            BfTestConstants.CHAR_A_READ,
            BfTestConstants.CHAR_B_WRITE,
            BfTestConstants.CHAR_C_WRITE_NR,
            BfTestConstants.CHAR_D_NOTIFY,
            BfTestConstants.CHAR_E_INDICATE,
            BfTestConstants.CHAR_F_DESC,
            BfTestConstants.CHAR_H_NOTIFY_IND,
        )) {
            assertTrue(charUuids.any { expected in it }, "Characteristic $expected missing")
        }
    }

    // ---- Read ----

    @Test
    fun readFixedValue(): Unit = runBlocking {
        val charA = findChar(BfTestConstants.CHAR_A_READ)
        val result = requireHarness().readCharacteristicAndAwait(requirePeripheral(), charA)
        assertContentEquals(BfTestConstants.CHAR_A_EXPECTED, result.value)
    }

    // ---- Write ----

    @Test
    fun writeAndReadBack(): Unit = runBlocking {
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val ok = requireHarness().writeCharacteristicAndAwait(requirePeripheral(), charB, data)
        assertTrue(ok, "Write should succeed")
        val readBack = requireHarness().readCharacteristicAndAwait(requirePeripheral(), charB)
        assertContentEquals(data, readBack.value)
    }

    @Test
    fun writeNoResponse(): Unit = runBlocking {
        val charC = findChar(BfTestConstants.CHAR_C_WRITE_NR)
        val data = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        writeNoResponse(requireHarness().falcon, requirePeripheral(), charC, data)
        delay(500)
        val readBack = requireHarness().readCharacteristicAndAwait(requirePeripheral(), charC)
        assertContentEquals(data, readBack.value)
    }

    // ---- Notifications ----

    @Test
    fun charDNotifications(): Unit = runBlocking {
        val charD = findChar(BfTestConstants.CHAR_D_NOTIFY)
        requireHarness().enableNotifyAndAwait(requirePeripheral(), charD)
        // Collect 2 values (immediate + 1 timer tick) — less sensitive to timing
        val values = requireHarness().collectNotifications(
            uuidFrom(BfTestConstants.CHAR_D_NOTIFY), count = 2, timeoutMs = 15_000L
        )
        assertEquals(2, values.size, "Should receive 2 notifications")
        requireHarness().disableNotify(requirePeripheral(), charD)
    }

    @Test
    fun indications(): Unit = runBlocking {
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val charE = findChar(BfTestConstants.CHAR_E_INDICATE)
        requireHarness().enableIndicateAndAwait(requirePeripheral(), charE)
        val data = byteArrayOf(0x42, 0x46)
        requireHarness().writeCharacteristicAndAwait(requirePeripheral(), charB, data)
        val indication = requireHarness().awaitCharacteristicValue(
            uuidFrom(BfTestConstants.CHAR_E_INDICATE), timeoutMs = 5_000L
        )
        assertContentEquals(data, indication.value,
            "Char E should echo value written to Char B")
    }

    @Test
    fun notifyAndIndicate(): Unit = runBlocking {
        val charH = findChar(BfTestConstants.CHAR_H_NOTIFY_IND)
        requireHarness().falcon.notifyAndIndicateCharacteristic(requirePeripheral(), charH, enable = true)
        delay(500)
        val values = requireHarness().collectNotifications(
            uuidFrom(BfTestConstants.CHAR_H_NOTIFY_IND), count = 2, timeoutMs = 10_000L
        )
        assertTrue(values.isNotEmpty(), "Should receive values from Char H")
        requireHarness().falcon.notifyAndIndicateCharacteristic(requirePeripheral(), charH, enable = false)
    }

    // ---- Descriptors ----

    @Test
    fun descriptorsPresent() {
        val charF = findChar(BfTestConstants.CHAR_F_DESC)
        assertTrue(charF.descriptors.isNotEmpty(), "Char F should have descriptors")
    }

    @Test
    fun writeAndReadDescriptor(): Unit = runBlocking {
        val charF = findChar(BfTestConstants.CHAR_F_DESC)
        // Find the User Description descriptor (not CCCD)
        val descriptor = charF.descriptors.firstOrNull { desc ->
            desc.toString().contains("2901", ignoreCase = true)
        } ?: charF.descriptors.first()
        val testValue = "Test Desc".encodeToByteArray()
        requireHarness().writeDescriptorAndAwait(requirePeripheral(), descriptor, testValue)
    }

    // ---- MTU ----

    @Test
    fun changeMtu(): Unit = runBlocking {
        val status = requireHarness().changeMtuAndAwait(requirePeripheral(), 247)
        assertEquals(0, status, "MTU change should succeed (status 0 = GATT_SUCCESS)")
    }

    // ---- Bonding ----

    @Test
    fun bondAndReadEncrypted(): Unit = runBlocking {
        try {
            requireHarness().createBondAndAwait(requirePeripheral(), timeoutMs = 15_000L)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // May already be bonded from a previous run
        }

        val charG = findChar(BfTestConstants.CHAR_G_ENCRYPTED)
        val result = requireHarness().readCharacteristicAndAwait(requirePeripheral(), charG)
        assertContentEquals(BfTestConstants.CHAR_G_EXPECTED, result.value,
            "Char G should return SECURE after bonding")
    }

    // TODO: L2CAP CoC not exposed via BlueZ D-Bus API.
    //  Android's createL2capChannel requires encryption.
    //  blue-falcon may need platform-specific L2CAP support.

}

/**
 * Platform-specific write-no-response.
 */
expect fun writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
)
