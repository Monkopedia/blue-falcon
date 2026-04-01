package dev.bluefalcon.integration

import android.app.Application
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.bluefalcon.*

actual fun createBlueFalcon(): BlueFalcon {
    val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as Application
    return BlueFalcon(log = PrintLnLogger, context = appContext, autoDiscoverAllServicesAndCharacteristics = true)
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
actual fun uuidFrom(string: String): Uuid = kotlin.uuid.Uuid.parse(string)

actual suspend fun scanForBfTestDevice(harness: BlueFalconTestHarness): BluetoothPeripheral {
    return harness.scanForDevice(filters = emptyList(), timeoutMs = 20_000L) { device, _ ->
        device.name == BfTestConstants.DEVICE_NAME
    }
}

actual fun ensureForeground() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = Intent(context, BleTestActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    Thread.sleep(1000)
}

actual fun writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
) {
    falcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, value, writeType = 2)
}
