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
     * TAP and ALERT return here when their one-shot animation finishes.
     */
    private var baseLoopingState: CompanionState = CompanionState.CALM

    /**
     * True when a TAP is waiting for the current one-shot animation (e.g. BLINK) to finish.
     * Ignored if already set — duplicate taps during queue are discarded.
     */
    private var tapQueued: Boolean = false

    /** True while the ALERT one-shot animation is playing. Prevents re-triggering. */
    private var alertActive: Boolean = false

    /** System time (ms) before which a new ALERT cannot be triggered (3-second cooldown). */
    private var alertCooldownUntil: Long = 0L

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
     * Called by CharacterView (via CompanionService) when the ALERT animation finishes.
     * Clears alert active flag, starts cooldown, and returns to the saved looping state.
     */
    fun onAlertCompleted() {
        if (currentState != CompanionState.ALERT) return
        alertActive = false
        alertCooldownUntil = System.currentTimeMillis() + ALERT_COOLDOWN_MS
        transitionTo(baseLoopingState)
    }

    /**
     * Triggered by [AccelerometerMonitor] when sharp braking is detected.
     *
     * - Ignored if ALERT is already playing.
     * - Ignored if within the 3-second cooldown window after a previous ALERT.
     * - Immediately interrupts any current state (CALM, BLINK, TAP, MUSIC).
     * - Resets blink timer and clears the tap queue.
     */
    fun onAlertTriggered() {
        if (alertActive) return
        if (System.currentTimeMillis() < alertCooldownUntil) return

        alertActive = true
        tapQueued = false
        cancelBlinkTimer()

        // Save the looping base state before interrupting it.
        // If we're currently in a one-shot (BLINK/TAP), baseLoopingState already holds the right target.
        // If we're in a looping state, update it now.
        if (currentState.isLooping) {
            baseLoopingState = currentState
        }

        transitionTo(CompanionState.ALERT)
    }

    /**
     * Triggered by a short tap on the character overlay.
     *
     * - If a looping state (CALM, MUSIC) is active → play TAP immediately.
     * - If a one-shot animation is active (BLINK, future MUSIC_DANCE, etc.) → queue TAP.
     * - If TAP is already queued or playing → ignore (no duplicate taps).
     * - ALERT is active → ignore (high-priority state blocks taps).
     */
    fun onTap() {
        if (alertActive || tapQueued || currentState == CompanionState.TAP) return

        if (currentState.isLooping) {
            // Immediate: remember where to return and play TAP
            baseLoopingState = currentState
            transitionTo(CompanionState.TAP)
        } else {
            // One-shot in progress (BLINK) — queue for later
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
            // MUSIC has priority over CALM/BLINK only; don't override ALERT or TAP mid-play
            if (currentState == CompanionState.CALM || currentState == CompanionState.BLINK) {
                transitionTo(CompanionState.MUSIC)
            } else if (currentState == CompanionState.TAP || currentState == CompanionState.ALERT) {
                // Music started while one-shot is playing — update return target
                baseLoopingState = CompanionState.MUSIC
            }
        } else if (!data.isPlaying) {
            if (currentState == CompanionState.MUSIC) {
                transitionTo(CompanionState.CALM)
            } else if (
                (currentState == CompanionState.TAP || currentState == CompanionState.ALERT) &&
                baseLoopingState == CompanionState.MUSIC
            ) {
                // Music stopped while one-shot is playing — return to CALM instead
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

        // If a one-shot was cut short by a looping state (not TAP/ALERT), the queued tap is stale.
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
            CompanionState.ALERT -> {
                // Blink timer and tap queue already cleared in onAlertTriggered()
            }
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

    companion object {
        private const val ALERT_COOLDOWN_MS = 3000L
    }
}
