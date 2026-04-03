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

/** Platform hook for any setup needed before BLE tests (permissions, foreground, etc). */
expect fun ensurePlatformReady()

/** Platform hook for cleanup after disconnect — ensures BLE radio releases the link. */
expect suspend fun awaitFullDisconnect(peripheral: BluetoothPeripheral)
