package dev.bluefalcon.integration

import android.app.Application
import android.content.Intent
import android.os.Build
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
    return harness.scanForDevice(filters = emptyList(), timeoutMs = 120_000L) { device, _ ->
        device.name == BfTestConstants.DEVICE_NAME
    }
}

actual fun ensurePlatformReady() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val uiAutomation = instrumentation.uiAutomation
    val pkg = instrumentation.targetContext.packageName

    // Grant BLE permissions — SDK 31+ has BLUETOOTH_SCAN/CONNECT,
    // older versions just need ACCESS_FINE_LOCATION
    val permissions = mutableListOf("android.permission.ACCESS_FINE_LOCATION")
    if (Build.VERSION.SDK_INT >= 31) {
        permissions.add("android.permission.BLUETOOTH_SCAN")
        permissions.add("android.permission.BLUETOOTH_CONNECT")
    }
    for (perm in permissions) {
        try {
            uiAutomation.grantRuntimePermission(pkg, perm)
        } catch (_: Exception) {}
    }

    // Launch foreground activity (Android throttles background BLE scans)
    val context = instrumentation.targetContext
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
