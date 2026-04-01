package dev.bluefalcon.integration

import dev.bluefalcon.*
import kotlin.uuid.Uuid

actual fun createBlueFalcon(): BlueFalcon {
    return BlueFalcon(
        log = PrintLnLogger,
        context = ApplicationContext(),
        autoDiscoverAllServicesAndCharacteristics = true
    )
}

actual fun uuidFrom(string: String): dev.bluefalcon.Uuid = Uuid.parse(string)

actual suspend fun scanForBfTestDevice(harness: BlueFalconTestHarness): BluetoothPeripheral {
    return harness.scanForDevice(filters = emptyList(), timeoutMs = 20_000L) { device, _ ->
        device.name == BfTestConstants.DEVICE_NAME
    }
}

actual fun ensurePlatformReady() {
    // No special setup needed on Linux — BlueZ doesn't require permission dialogs
}

actual fun writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
) {
    // writeType 1 = command (no response) in our Linux BlueFalcon implementation
    falcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, value, writeType = 1)
}
