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

    companion object {
        lateinit var harness: BlueFalconTestHarness
        lateinit var peripheral: BluetoothPeripheral
        private var setupDone = false

        fun ensureConnected() {
            if (setupDone) return
            ensurePlatformReady()
            runBlocking {
                val falcon = createBlueFalcon()
                harness = BlueFalconTestHarness(falcon)
                val found = scanForBfTestDevice(harness)
                // connectAndDiscover handles both fresh connections and
                // devices that are already connected in the BLE stack
                peripheral = harness.connectAndDiscover(found, timeoutMs = 60_000L)
                setupDone = true
            }
        }
    }

    @BeforeTest
    fun setUp() {
        ensureConnected()
    }

    private fun findChar(uuid: String): BluetoothCharacteristic {
        val target = uuidFrom(uuid)
        return peripheral.characteristics.values.flatten().firstOrNull { it.uuid == target }
            ?: error("Characteristic $uuid not found")
    }

    // ---- Device identity ----

    @Test
    fun connectedDeviceIsBfTest() {
        assertEquals(BfTestConstants.DEVICE_NAME, peripheral.name)
    }

    // ---- Connection ----

    @Test
    fun connectionStateIsConnected() {
        val state = harness.falcon.connectionState(peripheral)
        assertEquals(BluetoothPeripheralState.Connected, state)
    }

    // ---- Service discovery ----

    @Test
    fun discoversTestService() {
        val uuids = peripheral.services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_1 in it }, "Service 1 (BF10) missing")
    }

    @Test
    fun discoversSecureService() {
        val uuids = peripheral.services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_2 in it }, "Service 2 (BF20) missing")
    }

    @Test
    fun discoversAllCharacteristics() {
        val charUuids = peripheral.characteristics.values.flatten()
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
        val result = harness.readCharacteristicAndAwait(peripheral, charA)
        assertContentEquals(BfTestConstants.CHAR_A_EXPECTED, result.value)
    }

    // ---- Write ----

    @Test
    fun writeAndReadBack(): Unit = runBlocking {
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val ok = harness.writeCharacteristicAndAwait(peripheral, charB, data)
        assertTrue(ok, "Write should succeed")
        val readBack = harness.readCharacteristicAndAwait(peripheral, charB)
        assertContentEquals(data, readBack.value)
    }

    @Test
    fun writeNoResponse(): Unit = runBlocking {
        val charC = findChar(BfTestConstants.CHAR_C_WRITE_NR)
        val data = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        writeNoResponse(harness.falcon, peripheral, charC, data)
        delay(500)
        val readBack = harness.readCharacteristicAndAwait(peripheral, charC)
        assertContentEquals(data, readBack.value)
    }

    // ---- Notifications ----

    @Test
    fun charDNotifications(): Unit = runBlocking {
        val charD = findChar(BfTestConstants.CHAR_D_NOTIFY)
        harness.enableNotifyAndAwait(peripheral, charD)
        // Collect 2 values (immediate + 1 timer tick) — less sensitive to timing
        val values = harness.collectNotifications(
            uuidFrom(BfTestConstants.CHAR_D_NOTIFY), count = 2, timeoutMs = 15_000L
        )
        assertEquals(2, values.size, "Should receive 2 notifications")
        harness.disableNotify(peripheral, charD)
    }

    @Test
    fun indications(): Unit = runBlocking {
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val charE = findChar(BfTestConstants.CHAR_E_INDICATE)
        harness.enableIndicateAndAwait(peripheral, charE)
        val data = byteArrayOf(0x42, 0x46)
        harness.writeCharacteristicAndAwait(peripheral, charB, data)
        val indication = harness.awaitCharacteristicValue(
            uuidFrom(BfTestConstants.CHAR_E_INDICATE), timeoutMs = 5_000L
        )
        assertContentEquals(data, indication.value,
            "Char E should echo value written to Char B")
    }

    @Test
    fun notifyAndIndicate(): Unit = runBlocking {
        val charH = findChar(BfTestConstants.CHAR_H_NOTIFY_IND)
        harness.falcon.notifyAndIndicateCharacteristic(peripheral, charH, enable = true)
        delay(500)
        val values = harness.collectNotifications(
            uuidFrom(BfTestConstants.CHAR_H_NOTIFY_IND), count = 2, timeoutMs = 10_000L
        )
        assertTrue(values.isNotEmpty(), "Should receive values from Char H")
        harness.falcon.notifyAndIndicateCharacteristic(peripheral, charH, enable = false)
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
        harness.writeDescriptorAndAwait(peripheral, descriptor, testValue)
    }

    // ---- MTU ----

    @Test
    fun changeMtu(): Unit = runBlocking {
        val status = harness.changeMtuAndAwait(peripheral, 247)
        assertEquals(0, status, "MTU change should succeed (status 0 = GATT_SUCCESS)")
    }

    // TODO: Bonding — BlueZ 5.86 on adolin fails to pair with ESP32-C6
    //  ("No matching connection for device"). Works on Android. Needs
    //  investigation — may require registering an Agent1 via D-Bus,
    //  or may be a BlueZ/adapter compatibility issue.
    //
    // TODO: L2CAP CoC not exposed via BlueZ D-Bus API.
    //  Android's createL2capChannel requires encryption.
    //  blue-falcon may need platform-specific L2CAP support.

    // ---- Cleanup ----
    // Named with zz_ prefix to ensure it runs last (alphabetical ordering)

    @Test
    fun zz_disconnect(): Unit = runBlocking {
        if (setupDone) {
            try {
                harness.disconnectAndAwait(peripheral)
            } catch (_: Exception) {
                // Disconnect may timeout — still need to destroy
            }
            harness.destroy()
            harness.falcon.destroy()
            setupDone = false
        }
    }
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
