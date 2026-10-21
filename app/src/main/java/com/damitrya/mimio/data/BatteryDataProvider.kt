package com.damitrya.mimio.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryData(
    val level: Int = 100,
    val isCharging: Boolean = false,
    val isLow: Boolean = false
)

class BatteryDataProvider(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onBatteryDataChanged(data: BatteryData)
    }

    private var listener: Listener? = null
    private var currentData = BatteryData()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    // Unknown (no battery, a broken broadcast): never "0 %", which would look empty.
                    val percent = if (scale > 0 && level >= 0) (level * 100) / scale else UNKNOWN_LEVEL
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    // A plugged-in charger counts even when the phone reports "not charging" (full, too warm).
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL ||
                            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

                    currentData = BatteryData(
                        level = percent,
                        isCharging = isCharging,
                        isLow = percent in 0..settings.lowBatteryThreshold
                    )
                    listener?.onBatteryDataChanged(currentData)
                }
                Intent.ACTION_BATTERY_LOW -> {
                    currentData = currentData.copy(isLow = true)
                    listener?.onBatteryDataChanged(currentData)
                }
            }
        }
    }

    companion object {
        /** [BatteryData.level] when the level is not known. */
        const val UNKNOWN_LEVEL = -1
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): BatteryData = currentData

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        val stickyIntent = context.registerReceiver(batteryReceiver, filter)
        // Process sticky broadcast immediately
        stickyIntent?.let { batteryReceiver.onReceive(context, it) }
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
    }
}
