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

    /**
     * The last looping (ambient) state: either CALM or MUSIC.
     * TAP returns here when its one-shot animation finishes.
     */
    private var baseLoopingState: CompanionState = CompanionState.CALM

    /**
     * True when a TAP is waiting for the current one-shot animation (e.g. BLINK) to finish.
     * Ignored if already set — duplicate taps during queue are discarded.
     */
    private var tapQueued: Boolean = false

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
     * If a TAP was queued during the blink, plays TAP next; otherwise returns to CALM.
     */
    fun onBlinkCompleted() {
        if (currentState != CompanionState.BLINK) return
        if (tapQueued) {
            tapQueued = false
            transitionTo(CompanionState.TAP)
        } else {
            transitionTo(CompanionState.CALM)
        }
    }

    /**
     * Called by CharacterView (via CompanionService) when the TAP animation finishes.
     * Returns to the looping state that was active before TAP started.
     */
    fun onTapCompleted() {
        if (currentState != CompanionState.TAP) return
        transitionTo(baseLoopingState)
    }

    /**
     * Triggered by a short tap on the character overlay.
     *
     * - If a looping state (CALM, MUSIC) is active → play TAP immediately.
     * - If a one-shot animation is active (BLINK, future MUSIC_DANCE, etc.) → queue TAP.
     * - If TAP is already queued or playing → ignore (no duplicate taps).
     * - High-priority transitions (ALERT, etc.) will clear [tapQueued] via [transitionTo].
     */
    fun onTap() {
        if (tapQueued || currentState == CompanionState.TAP) return

        if (currentState.isLooping) {
            // Immediate: remember where to return and play TAP
            baseLoopingState = currentState
            transitionTo(CompanionState.TAP)
        } else {
            // One-shot in progress (BLINK / future MUSIC_DANCE) — queue for later
            tapQueued = true
        }
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
            } else if (currentState == CompanionState.TAP) {
                // Music started while TAP is playing — update return target so TAP lands in MUSIC
                baseLoopingState = CompanionState.MUSIC
            }
        } else if (!data.isPlaying) {
            if (currentState == CompanionState.MUSIC) {
                transitionTo(CompanionState.CALM)
            } else if (currentState == CompanionState.TAP && baseLoopingState == CompanionState.MUSIC) {
                // Music stopped while TAP is playing — return to CALM instead
                baseLoopingState = CompanionState.CALM
            }
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

        // If a one-shot was cut short by a looping state (not TAP), the queued tap is stale.
        if (!previous.isLooping && newState.isLooping) {
            tapQueued = false
        }

        currentState = newState
        listener?.onStateChanged(newState, previous)

        when (newState) {
            CompanionState.CALM -> {
                baseLoopingState = CompanionState.CALM
                scheduleNextBlink()
            }
            CompanionState.MUSIC -> {
                baseLoopingState = CompanionState.MUSIC
                cancelBlinkTimer()
            }
            CompanionState.BLINK -> { /* timer already armed; wait for onBlinkCompleted */ }
            CompanionState.TAP -> cancelBlinkTimer()
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
