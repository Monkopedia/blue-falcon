# blue-falcon Integration Tests

BLE integration tests for [blue-falcon](https://github.com/Reedyuk/blue-falcon)
that run against a real BLE peripheral.

## Test Peripheral

Tests require [bf-test-peripheral](https://github.com/Monkopedia/bf-test-peripheral),
an ESP32-C6 firmware that exposes a GATT profile covering the full
blue-falcon API surface. Pre-built firmware is available in the
[releases](https://github.com/Monkopedia/bf-test-peripheral/releases).

## Tests

| Test | API covered |
|------|-------------|
| `connectedDeviceIsBfTest` | scan, device name |
| `connectionStateIsConnected` | connectionState |
| `discoversTestService` | discoverServices |
| `discoversSecureService` | discoverServices |
| `discoversAllCharacteristics` | discoverCharacteristics |
| `readFixedValue` | readCharacteristic |
| `writeAndReadBack` | writeCharacteristic, readCharacteristic |
| `writeNoResponse` | writeCharacteristicWithoutEncoding |
| `charDNotifications` | notifyCharacteristic |
| `indications` | indicateCharacteristic |
| `notifyAndIndicate` | notifyAndIndicateCharacteristic |
| `descriptorsPresent` | descriptor discovery |
| `writeDescriptor` | writeDescriptor |
| `changeMtu` | changeMTU |
| `bondAndReadEncrypted` | createBond, readCharacteristic (encrypted) |

## Running

### Prerequisites

- BF-Test peripheral powered on and advertising
- Bluetooth enabled on the test device
- Library published to mavenLocal: `cd ../library && ./gradlew publishToMavenLocal`

### Android

```sh
./gradlew connectedDebugAndroidTest
```

Requires a connected Android device with BLE support.

### Linux

```sh
./gradlew linuxX64Test
```

Requires BlueZ and libsystemd-dev.

## Architecture

- **commonMain**: `BlueFalconTestHarness` (bridges callback API to
  coroutines), `BfTestConstants` (UUIDs and expected values)
- **commonTest**: `BleIntegrationTests` (test cases), `TestPlatform`
  (expect declarations)
- **androidInstrumentedTest**: Android platform actuals
- **linuxX64Test**: Linux/BlueZ platform actuals
