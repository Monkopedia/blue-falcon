package dev.bluefalcon.integration

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.Uuid

expect fun createBlueFalcon(): BlueFalcon

expect fun uuidFrom(string: String): Uuid

/**
 * Scan for the BF-Test device using platform-appropriate filters.
 * Returns the found peripheral.
 */
expect suspend fun scanForBfTestDevice(harness: BlueFalconTestHarness): BluetoothPeripheral

/** Platform hook to ensure the test is in the foreground (needed for Android BLE scans). */
expect fun ensureForeground()
