package com.drivecompanion.state

import android.os.Handler
import android.os.Looper
import com.drivecompanion.data.AppProfile
import com.drivecompanion.data.AppUsageData
import com.drivecompanion.data.BatteryData
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.PhoneCallData
import com.drivecompanion.data.ScreenData
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.data.StepData
import com.drivecompanion.data.TimeData
import com.drivecompanion.data.WeatherData
import kotlin.random.Random

/**
 * Finite state machine that determines the companion's current state.
 * Manages automatic BLINK transitions while in CALM state.
 */
class StateMachine(private val settings: SettingsRepository) {

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
        fun onWeatherModifierChanged(modifier: WeatherModifier)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.CALM
    private var currentWeatherModifier: WeatherModifier = WeatherModifier.NONE

    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable = Runnable { triggerBlink() }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    fun setProfile(profile: AppProfile) {
        transitionTo(CompanionState.CALM)
    }

    /**
     * Called by CharacterView (via CompanionService) when the BLINK animation finishes.
     * Transitions back to CALM and schedules the next blink.
     */
    fun onBlinkCompleted() {
        if (currentState != CompanionState.BLINK) return
        transitionTo(CompanionState.CALM)
    }

    fun destroy() {
        cancelBlinkTimer()
    }

    // Auto profile updates
    fun updateDrivingData(data: DrivingData) {}

    fun updateMediaData(data: MediaData) {
        if (!settings.musicReactionEnabled) {
            if (currentState == CompanionState.MUSIC) {
                transitionTo(CompanionState.CALM)
            }
            return
        }

        if (data.isPlaying && currentState != CompanionState.MUSIC) {
            // MUSIC has priority over CALM/BLINK only; don't override future driving emotions
            if (currentState == CompanionState.CALM || currentState == CompanionState.BLINK) {
                transitionTo(CompanionState.MUSIC)
            }
        } else if (!data.isPlaying && currentState == CompanionState.MUSIC) {
            transitionTo(CompanionState.CALM)
        }
    }

    // Daily profile updates
    fun updateBatteryData(data: BatteryData) {}

    fun updateTimeData(data: TimeData) {}

    fun updateScreenData(data: ScreenData) {}

    fun updateStepData(data: StepData) {}

    fun updateWeatherData(data: WeatherData) {
        val newModifier = if (settings.weatherEnabled) data.modifier else WeatherModifier.NONE
        if (newModifier != currentWeatherModifier) {
            currentWeatherModifier = newModifier
            listener?.onWeatherModifierChanged(newModifier)
        }
    }

    fun updateAppUsageData(data: AppUsageData) {}

    fun updatePhoneCallData(data: PhoneCallData) {}

    private fun transitionTo(newState: CompanionState) {
        val previous = currentState
        currentState = newState
        listener?.onStateChanged(newState, previous)

        if (newState == CompanionState.CALM) {
            scheduleNextBlink()
        } else if (newState != CompanionState.BLINK) {
            // Entering a non-blink emotion — stop the blink cycle
            cancelBlinkTimer()
        }
    }

    private fun triggerBlink() {
        if (currentState != CompanionState.CALM) return
        transitionTo(CompanionState.BLINK)
    }

    private fun scheduleNextBlink() {
        cancelBlinkTimer()
        val delayMs = 3000L + Random.nextLong(4001L) // 3000..7000 ms
        handler.postDelayed(blinkRunnable, delayMs)
    }

    private fun cancelBlinkTimer() {
        handler.removeCallbacks(blinkRunnable)
    }
}
