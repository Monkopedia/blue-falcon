package dev.bluefalcon.integration

import dev.bluefalcon.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration tests against the BF-Test ESP32-C6 peripheral.
 *
 * Each test is fully isolated: @BeforeTest scans, connects, and discovers
 * services; @AfterTest disconnects and waits for the radio to release.
 *
 * Prerequisites:
 * - BF-Test peripheral is powered on and advertising
 * - Device has Bluetooth enabled and permissions granted
 *
 * @see <a href="https://github.com/Monkopedia/bf-test-peripheral">bf-test-peripheral</a>
 */
class BleIntegrationTests {

    private var harness: BlueFalconTestHarness? = null
    private var peripheral: BluetoothPeripheral? = null

    @BeforeTest
    fun setUp() = runBlocking {
        ensurePlatformReady()
        val falcon = createBlueFalcon()
        val h = BlueFalconTestHarness(falcon)
        harness = h
        val found = scanForBfTestDevice(h)
        peripheral = h.connectAndDiscover(found, timeoutMs = 120_000L)
    }

    @AfterTest
    fun tearDown() = runBlocking {
        val h = harness ?: return@runBlocking
        val p = peripheral
        if (p != null) {
            try { h.disconnectAndAwait(p) } catch (_: Exception) {}
            awaitFullDisconnect(p)
        }
        h.destroy()
        h.falcon.destroy()
    }

    private fun harness() = harness ?: fail("setUp failed")
    private fun peripheral() = peripheral ?: fail("setUp failed")

    private fun findChar(uuid: String): BluetoothCharacteristic {
        val target = uuidFrom(uuid)
        return peripheral().characteristics.values.flatten().firstOrNull { it.uuid == target }
            ?: fail("Characteristic $uuid not found")
    }

    // ---- Device identity ----

    @Test
    fun connectedDeviceIsBfTest() {
        assertEquals(BfTestConstants.DEVICE_NAME, peripheral().name)
    }

    // ---- Connection ----

    @Test
    fun connectionStateIsConnected() {
        assertEquals(
            BluetoothPeripheralState.Connected,
            harness().falcon.connectionState(peripheral())
        )
    }

    // ---- Service discovery ----

    @Test
    fun discoversTestService() {
        val uuids = peripheral().services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_1 in it }, "Service 1 (BF10) missing")
    }

    @Test
    fun discoversSecureService() {
        val uuids = peripheral().services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_2 in it }, "Service 2 (BF20) missing")
    }

    @Test
    fun discoversAllCharacteristics() {
        val uuids = peripheral().characteristics.values.flatten()
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
            assertTrue(uuids.any { expected in it }, "Characteristic $expected missing")
        }
    }

    // ---- Read ----

    @Test
    fun readFixedValue(): Unit = runBlocking {
        val result = harness().readCharacteristicAndAwait(
            peripheral(), findChar(BfTestConstants.CHAR_A_READ)
        )
        assertContentEquals(BfTestConstants.CHAR_A_EXPECTED, result.value)
    }

    // ---- Write ----

    @Test
    fun writeAndReadBack(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertTrue(h.writeCharacteristicAndAwait(p, charB, data), "Write should succeed")
        assertContentEquals(data, h.readCharacteristicAndAwait(p, charB).value)
    }

    @Test
    fun writeNoResponse(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        val charC = findChar(BfTestConstants.CHAR_C_WRITE_NR)
        val data = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        writeNoResponse(h.falcon, p, charC, data)
        delay(500) // no callback for write-without-response
        assertContentEquals(data, h.readCharacteristicAndAwait(p, charC).value)
    }

    // ---- Notifications ----

    @Test
    fun charDNotifications(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        val charD = findChar(BfTestConstants.CHAR_D_NOTIFY)
        h.enableNotifyAndAwait(p, charD)
        val values = h.collectNotifications(
            uuidFrom(BfTestConstants.CHAR_D_NOTIFY), count = 2, timeoutMs = 15_000L
        )
        assertEquals(2, values.size, "Should receive 2 notifications")
        h.disableNotify(p, charD)
    }

    @Test
    fun indications(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        val charE = findChar(BfTestConstants.CHAR_E_INDICATE)
        h.enableIndicateAndAwait(p, charE)
        val data = byteArrayOf(0x42, 0x46) // "BF"
        h.writeCharacteristicAndAwait(p, findChar(BfTestConstants.CHAR_B_WRITE), data)
        val indication = h.awaitCharacteristicValue(
            uuidFrom(BfTestConstants.CHAR_E_INDICATE), timeoutMs = 5_000L
        )
        assertContentEquals(data, indication.value,
            "Char E should echo value written to Char B")
    }

    @Test
    fun notifyAndIndicate(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        val charH = findChar(BfTestConstants.CHAR_H_NOTIFY_IND)
        h.falcon.notifyAndIndicateCharacteristic(p, charH, enable = true)
        delay(500)
        val values = h.collectNotifications(
            uuidFrom(BfTestConstants.CHAR_H_NOTIFY_IND), count = 2, timeoutMs = 10_000L
        )
        assertTrue(values.isNotEmpty(), "Should receive values from Char H")
        h.falcon.notifyAndIndicateCharacteristic(p, charH, enable = false)
    }

    // ---- Descriptors ----

    @Test
    fun descriptorsPresent() {
        assertTrue(
            findChar(BfTestConstants.CHAR_F_DESC).descriptors.isNotEmpty(),
            "Char F should have descriptors"
        )
    }

    @Test
    fun writeDescriptor(): Unit = runBlocking {
        val charF = findChar(BfTestConstants.CHAR_F_DESC)
        val descriptor = charF.descriptors.firstOrNull { desc ->
            desc.toString().contains("2901", ignoreCase = true)
        } ?: charF.descriptors.first()
        harness().writeDescriptorAndAwait(peripheral(), descriptor, "Test".encodeToByteArray())
    }

    // ---- MTU ----

    @Test
    fun changeMtu(): Unit = runBlocking {
        assertEquals(
            0,
            harness().changeMtuAndAwait(peripheral(), 247),
            "MTU change should succeed (0 = GATT_SUCCESS)"
        )
    }

    // ---- Bonding ----

    @Test
    fun bondAndReadEncrypted(): Unit = runBlocking {
        val h = harness()
        val p = peripheral()
        try {
            h.createBondAndAwait(p, timeoutMs = 15_000L)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // May already be bonded from a previous run
        }
        val result = h.readCharacteristicAndAwait(p, findChar(BfTestConstants.CHAR_G_ENCRYPTED))
        assertContentEquals(BfTestConstants.CHAR_G_EXPECTED, result.value,
            "Char G should return SECURE after bonding")
    }

    // L2CAP CoC is not testable: not exposed via BlueZ D-Bus API, and
    // Android's createL2capChannel requires an encrypted link.
}
