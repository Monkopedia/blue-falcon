package dev.bluefalcon.integration

import android.app.Activity
import android.os.Bundle

/**
 * Blank foreground activity. Android throttles BLE scans for background
 * apps, so instrumented tests need a visible Activity to get scan results.
 */
class BleTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
