package dev.bluefalcon.integration

object BfTestConstants {
    const val DEVICE_NAME = "BF-Test"

    // Service 1: BF Test Service
    const val SERVICE_1 = "0000bf10-1000-2000-8000-00805f9b34fb"
    const val CHAR_A_READ = "0000bfa1-1000-2000-8000-00805f9b34fb"
    const val CHAR_B_WRITE = "0000bfa2-1000-2000-8000-00805f9b34fb"
    const val CHAR_C_WRITE_NR = "0000bfa3-1000-2000-8000-00805f9b34fb"
    const val CHAR_D_NOTIFY = "0000bfa4-1000-2000-8000-00805f9b34fb"
    const val CHAR_E_INDICATE = "0000bfa5-1000-2000-8000-00805f9b34fb"
    const val CHAR_F_DESC = "0000bfa6-1000-2000-8000-00805f9b34fb"
    const val CHAR_H_NOTIFY_IND = "0000bfa7-1000-2000-8000-00805f9b34fb"

    // Service 2: BF Secure Service
    const val SERVICE_2 = "0000bf20-1000-2000-8000-00805f9b34fb"
    const val CHAR_G_ENCRYPTED = "0000bfb1-1000-2000-8000-00805f9b34fb"

    // Descriptors
    const val USER_DESCRIPTION = "00002901-0000-1000-8000-00805f9b34fb"

    // L2CAP
    const val L2CAP_PSM = 0x0080

    // Char A returns this fixed value
    val CHAR_A_EXPECTED = byteArrayOf(
        0xBF.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07
    )

    // Char G returns this when bonded
    val CHAR_G_EXPECTED = byteArrayOf(0x53, 0x45, 0x43, 0x55, 0x52, 0x45) // "SECURE"
}
