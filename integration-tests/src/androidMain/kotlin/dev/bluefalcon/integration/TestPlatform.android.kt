package dev.bluefalcon.integration

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import dev.bluefalcon.*

actual fun createBlueFalcon(): BlueFalcon {
    val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as Application
    return BlueFalcon(context = appContext, autoDiscoverAllServicesAndCharacteristics = true)
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
actual fun uuidFrom(string: String): Uuid = kotlin.uuid.Uuid.parse(string)

actual fun falcon_writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
) {
    // Android WRITE_TYPE_NO_RESPONSE = 2
    falcon.writeCharacteristicWithoutEncoding(peripheral, characteristic, value, writeType = 2)
}
