package com.drivecompanion.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

data class ScreenData(
    val isScreenOn: Boolean = true,
    val screenOffDurationMs: Long = 0L,
    val justTurnedOn: Boolean = false
)

class ScreenStateProvider(private val context: Context) {

    interface Listener {
        fun onScreenDataChanged(data: ScreenData)
    }

    private var listener: Listener? = null
    private var currentData = ScreenData()
    private val handler = Handler(Looper.getMainLooper())
    private var screenOffTimestamp: Long = 0L
    private val screenOffThresholdMs = 30 * 60 * 1000L // 30 minutes

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffTimestamp = System.currentTimeMillis()
                    currentData = ScreenData(
                        isScreenOn = false,
                        screenOffDurationMs = 0L,
                        justTurnedOn = false
                    )
                    listener?.onScreenDataChanged(currentData)
                    startScreenOffTimer()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val duration = if (screenOffTimestamp > 0) {
                        System.currentTimeMillis() - screenOffTimestamp
                    } else 0L
                    handler.removeCallbacksAndMessages(null)
                    currentData = ScreenData(
                        isScreenOn = true,
                        screenOffDurationMs = duration,
                        justTurnedOn = duration >= screenOffThresholdMs
                    )
                    listener?.onScreenDataChanged(currentData)

                    // Reset justTurnedOn after animation plays
                    if (currentData.justTurnedOn) {
                        handler.postDelayed({
                            currentData = currentData.copy(justTurnedOn = false)
                            listener?.onScreenDataChanged(currentData)
                        }, 3000L)
                    }
                }
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): ScreenData = currentData

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    private fun startScreenOffTimer() {
        handler.postDelayed({
            if (!currentData.isScreenOn) {
                val duration = System.currentTimeMillis() - screenOffTimestamp
                currentData = ScreenData(
                    isScreenOn = false,
                    screenOffDurationMs = duration,
                    justTurnedOn = false
                )
                listener?.onScreenDataChanged(currentData)
            }
        }, screenOffThresholdMs)
    }
}
