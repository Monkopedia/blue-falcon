package dev.bluefalcon.integration

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.Uuid

expect fun createBlueFalcon(): BlueFalcon

expect fun uuidFrom(string: String): Uuid

/** Scan for the BF-Test device using platform-appropriate filters and timeouts. */
expect suspend fun scanForBfTestDevice(harness: BlueFalconTestHarness): BluetoothPeripheral

/** Platform hook for setup before BLE tests (permissions, foreground activity, etc). */
expect fun ensurePlatformReady()

/** Wait for the BLE radio to fully release the link after disconnect. */
expect suspend fun awaitFullDisconnect(peripheral: BluetoothPeripheral)

/** Platform-specific write-without-response (writeType varies by platform). */
expect fun writeNoResponse(
    falcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray
)
