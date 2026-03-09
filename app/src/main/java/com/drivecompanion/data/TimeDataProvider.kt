package com.drivecompanion.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.Calendar

enum class TimePeriod {
    MORNING,  // 6:00 - 10:00
    DAY,      // 10:00 - 18:00
    EVENING,  // 18:00 - 22:00
    NIGHT     // 22:00 - 6:00
}

data class TimeData(
    val period: TimePeriod = TimePeriod.DAY,
    val hour: Int = 12,
    val isFirstUnlockOfDay: Boolean = false
)

class TimeDataProvider(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onTimeDataChanged(data: TimeData)
    }

    private var listener: Listener? = null
    private var currentData = TimeData()
    private val handler = Handler(Looper.getMainLooper())
    private var lastUnlockDate: Int = -1
    private var morningGreetingShown = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            updateTimeData(isUnlock = false)
            handler.postDelayed(this, 60_000L) // Check every minute
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                updateTimeData(isUnlock = true)
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): TimeData = currentData

    fun start() {
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        context.registerReceiver(screenOnReceiver, filter)
        updateTimeData(isUnlock = false)
        handler.post(checkRunnable)
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
        try {
            context.unregisterReceiver(screenOnReceiver)
        } catch (_: Exception) {}
    }

    private fun updateTimeData(isUnlock: Boolean) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        val period = when (hour) {
            in settings.morningStart until settings.dayStart -> TimePeriod.MORNING
            in settings.dayStart until settings.eveningStart -> TimePeriod.DAY
            in settings.eveningStart until settings.nightStart -> TimePeriod.EVENING
            else -> TimePeriod.NIGHT
        }

        var isFirstUnlock = false
        if (isUnlock && period == TimePeriod.MORNING && !morningGreetingShown) {
            if (lastUnlockDate != dayOfYear) {
                isFirstUnlock = true
                morningGreetingShown = true
                lastUnlockDate = dayOfYear
            }
        }
        // Reset morning greeting flag at night
        if (period == TimePeriod.NIGHT) {
            morningGreetingShown = false
        }

        currentData = TimeData(
            period = period,
            hour = hour,
            isFirstUnlockOfDay = isFirstUnlock
        )
        listener?.onTimeDataChanged(currentData)

        // Reset first unlock flag after notifying
        if (isFirstUnlock) {
            handler.postDelayed({
                currentData = currentData.copy(isFirstUnlockOfDay = false)
            }, 5000L)
        }
    }
}
