package dev.bluefalcon.integration

import dev.bluefalcon.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration tests that run real BLE operations against the BF-Test peripheral.
 *
 * Tests are numbered to enforce execution order. State is shared via companion.
 *
 * Prerequisites:
 * - BF-Test ESP32-C6 peripheral is powered on and advertising
 * - Device has Bluetooth enabled and permissions granted
 */
open class BleIntegrationTests {

    companion object {
        var harness: BlueFalconTestHarness? = null
        var peripheral: BluetoothPeripheral? = null

        fun findChar(uuid: String): BluetoothCharacteristic {
            val p = peripheral ?: error("No peripheral")
            val target = uuidFrom(uuid)
            return p.characteristics.values.flatten().firstOrNull { it.uuid == target }
                ?: error("Characteristic $uuid not found")
        }
    }

    @Test
    fun test_01_initialize() {
        val falcon = createBlueFalcon()
        harness = BlueFalconTestHarness(falcon)
        assertNotNull(harness)
    }

    @Test
    fun test_02_scan() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val found = h.scanForDevice(timeoutMs = 20_000L) { device, _ ->
            device.name == BfTestConstants.DEVICE_NAME
        }
        assertNotNull(found, "BF-Test device not found")
        println("Found BF-Test: ${found.name} (${found.uuid})")
        // Don't assign to peripheral yet — need connected instance
    }

    @Test
    fun test_03_connect() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        // Scan again to get a fresh peripheral reference, then connect
        val found = h.scanForDevice(timeoutMs = 20_000L) { device, _ ->
            device.name == BfTestConstants.DEVICE_NAME
        }
        peripheral = h.connectAndDiscover(found, timeoutMs = 20_000L)
        assertNotNull(peripheral)
        val services = peripheral!!.services
        assertTrue(services.isNotEmpty(), "Should have discovered services")
        println("Connected. ${services.size} services discovered.")
    }

    @Test
    fun test_04_verifyServices() {
        val p = peripheral ?: fail("Not connected")
        val uuids = p.services.keys.map { it.toString().lowercase() }
        assertTrue(uuids.any { BfTestConstants.SERVICE_1 in it }, "Service 1 (BF10) missing")
        assertTrue(uuids.any { BfTestConstants.SERVICE_2 in it }, "Service 2 (BF20) missing")
    }

    @Test
    fun test_05_verifyCharacteristics() {
        val p = peripheral ?: fail("Not connected")
        val allChars = p.characteristics.values.flatten().map { it.uuid.toString().lowercase() }
        assertTrue(allChars.any { BfTestConstants.CHAR_A_READ in it }, "Char A missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_B_WRITE in it }, "Char B missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_C_WRITE_NR in it }, "Char C missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_D_NOTIFY in it }, "Char D missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_E_INDICATE in it }, "Char E missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_F_DESC in it }, "Char F missing")
        assertTrue(allChars.any { BfTestConstants.CHAR_H_NOTIFY_IND in it }, "Char H missing")
    }

    @Test
    fun test_06_readFixedValue() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charA = findChar(BfTestConstants.CHAR_A_READ)
        val result = h.readCharacteristicAndAwait(p, charA)
        assertNotNull(result.value, "Char A value should not be null")
        assertContentEquals(BfTestConstants.CHAR_A_EXPECTED, result.value!!,
            "Char A should return BF 01 02 03 04 05 06 07")
    }

    @Test
    fun test_07_writeAndReadBack() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val testData = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val success = h.writeCharacteristicAndAwait(p, charB, testData)
        assertTrue(success, "Write to Char B should succeed")
        val readResult = h.readCharacteristicAndAwait(p, charB)
        assertContentEquals(testData, readResult.value, "Char B should echo written value")
    }

    @Test
    fun test_08_writeNoResponse() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charC = findChar(BfTestConstants.CHAR_C_WRITE_NR)
        val testData = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        // Write-without-response fires and forgets; no write callback expected
        falcon_writeNoResponse(h.falcon, p, charC, testData)
        delay(500) // give peripheral time to process
        val readResult = h.readCharacteristicAndAwait(p, charC)
        assertContentEquals(testData, readResult.value, "Char C should store write-no-response data")
    }

    @Test
    fun test_09_notifications() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charD = findChar(BfTestConstants.CHAR_D_NOTIFY)
        h.enableNotifyAndAwait(p, charD)
        val values = h.collectNotifications(uuidFrom(BfTestConstants.CHAR_D_NOTIFY), count = 3, timeoutMs = 10_000L)
        assertEquals(3, values.size, "Should receive 3 notification values")
        // Values should be incrementing counters
        println("Notification values: ${values.map { it.toList() }}")
        h.disableNotify(p, charD)
    }

    @Test
    fun test_10_indications() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charB = findChar(BfTestConstants.CHAR_B_WRITE)
        val charE = findChar(BfTestConstants.CHAR_E_INDICATE)
        h.enableIndicateAndAwait(p, charE)
        // Write to Char B — firmware echoes via Char E indication
        val testData = byteArrayOf(0x42, 0x46) // "BF"
        h.writeCharacteristicAndAwait(p, charB, testData)
        val indication = h.awaitCharacteristicValue(uuidFrom(BfTestConstants.CHAR_E_INDICATE), timeoutMs = 5_000L)
        assertContentEquals(testData, indication.value,
            "Char E indication should echo value written to Char B")
    }

    @Test
    fun test_11_notifyAndIndicate() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charH = findChar(BfTestConstants.CHAR_H_NOTIFY_IND)
        // Enable both notify and indicate
        h.falcon.notifyAndIndicateCharacteristic(p, charH, enable = true)
        delay(500)
        val values = h.collectNotifications(uuidFrom(BfTestConstants.CHAR_H_NOTIFY_IND), count = 2, timeoutMs = 10_000L)
        assertTrue(values.isNotEmpty(), "Should receive values from Char H (notify+indicate)")
        h.falcon.notifyAndIndicateCharacteristic(p, charH, enable = false)
    }

    @Test
    fun test_12_descriptors() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val charF = findChar(BfTestConstants.CHAR_F_DESC)
        val descriptors = charF.descriptors
        assertTrue(descriptors.isNotEmpty(), "Char F should have descriptors")
    }

    @Test
    fun test_13_connectionState() {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        val state = h.falcon.connectionState(p)
        assertEquals(BluetoothPeripheralState.Connected, state, "Should still be connected")
    }

    @Test
    fun test_14_disconnect() = runBlocking {
        val h = harness ?: fail("Harness not initialized")
        val p = peripheral ?: fail("Not connected")
        h.disconnectAndAwait(p)
        delay(500)
        val state = h.falcon.connectionState(p)
        assertEquals(BluetoothPeripheralState.Disconnected, state, "Should be disconnected")
    }

    @Test
    fun test_15_cleanup() {
        harness?.destroy()
        harness?.falcon?.destroy()
        harness = null
        peripheral = null
    }
}

/**
 * Platform-specific write-no-response. On Android, writeType=2 is WRITE_TYPE_NO_RESPONSE.
 * Each platform can map appropriately.
 */
expect fun falcon_writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
)
