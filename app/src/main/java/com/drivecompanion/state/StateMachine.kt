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
 * Manages automatic BLINK transitions while in CALM state and falls asleep
 * after a long period without interaction.
 *
 * Priorities: ALERT > PET > interaction one-shots (TAP, ANNOYED, HEARTS, WAKE) >
 * events (GREETING, TRIP_START) > MUSIC > CALM / SLEEP.
 */
class StateMachine(
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
        fun onWeatherModifierChanged(modifier: WeatherModifier)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.CALM
    private var currentWeatherModifier: WeatherModifier = WeatherModifier.NONE

    /**
     * The last looping (ambient) state: either CALM or MUSIC.
     * One-shots (TAP, RELIEF, HEARTS, WAKE…) return here when their animation finishes.
     */
    private var baseLoopingState: CompanionState = CompanionState.CALM

    /**
     * True when a TAP is waiting for the current one-shot animation (e.g. BLINK) to finish.
     * Ignored if already set — duplicate taps during queue are discarded.
     */
    private var tapQueued: Boolean = false

    /** True while the ALERT one-shot animation is playing. Prevents re-triggering. */
    private var alertActive: Boolean = false

    /** True while PET animation is playing (finger held down). Prevents re-triggering. */
    private var petActive: Boolean = false

    /** System time (ms) before which a new ALERT cannot be triggered (3-second cooldown). */
    private var alertCooldownUntil: Long = 0L

    /** Times of recent taps, used to detect tap spam (→ ANNOYED). */
    private val recentTaps = ArrayDeque<Long>()

    /** Last tap, long press, alert, music change or trip start. Drives falling asleep. */
    private var lastActivityAt: Long = clock()

    /** Latest GPS speed and when it arrived; the companion does not fall asleep while the car is moving. */
    private var lastSpeedKmh: Float = 0f
    private var lastSpeedAt: Long = 0L

    /** When the car came to a stop (0 = moving or unknown). */
    private var stoppedSince: Long = 0L

    /** True once the car has been standing long enough for the next departure to count as a trip start. */
    private var parked: Boolean = false

    /** When the car left the parking spot; TRIP_START blocked by ALERT/PET is retried until [TRIP_START_RETRY_MS]. */
    private var departedAt: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable = Runnable { triggerBlink() }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    fun setProfile(profile: AppProfile) {
        // Forced reset: ALERT/PET may be cut short here, and their completion callbacks
        // will no longer match the current state, so clear their guards explicitly.
        alertActive = false
        petActive = false
        tapQueued = false
        recentTaps.clear()
        lastActivityAt = clock()
        lastSpeedKmh = 0f
        lastSpeedAt = 0L
        stoppedSince = 0L
        parked = false
        departedAt = 0L
        transitionTo(CompanionState.CALM)
    }

    /** Called when the overlay appears: plays GREETING, then returns to CALM. */
    fun onOverlayShown() {
        if (alertActive || petActive) return
        lastActivityAt = clock()
        rememberBaseState()
        transitionTo(CompanionState.GREETING)
    }

    /**
     * Called by CharacterView (via CompanionService) when a one-shot animation finishes.
     * Ignored if the state has already changed (the animation was interrupted).
     */
    fun onOneShotCompleted(state: CompanionState) {
        if (state != currentState) return
        when (state) {
            CompanionState.ALERT -> {
                alertActive = false
                alertCooldownUntil = clock() + ALERT_COOLDOWN_MS
                transitionTo(CompanionState.RELIEF)
            }
            CompanionState.FALL_ASLEEP -> {
                // Music started while dozing off — dance instead of sleeping.
                transitionTo(
                    if (baseLoopingState == CompanionState.MUSIC) CompanionState.MUSIC
                    else CompanionState.SLEEP
                )
            }
            else -> returnToBaseState()
        }
    }

    /**
     * Triggered by [AccelerometerMonitor] when sharp braking is detected.
     *
     * - Ignored if ALERT is already playing.
     * - Ignored if within the 3-second cooldown window after a previous ALERT.
     * - Immediately interrupts any current state (including PET and SLEEP).
     * - Resets blink timer and clears the tap queue.
     * - Followed by RELIEF, then the saved looping state.
     */
    fun onAlertTriggered() {
        if (alertActive) return
        if (clock() < alertCooldownUntil) return

        alertActive = true
        petActive = false
        tapQueued = false
        lastActivityAt = clock()
        cancelBlinkTimer()
        rememberBaseState()

        transitionTo(CompanionState.ALERT)
    }

    /**
     * Triggered by a long press (≥ 600 ms) on the character overlay.
     *
     * - Ignored if ALERT is active (high-priority state).
     * - Ignored if PET is already active (no repeated triggers while held).
     * - Interrupts any current state (also wakes the companion up), pauses BLINK timer, clears TAP queue.
     * - Looping base state is preserved so [onLongPressReleased] can return correctly.
     */
    fun onLongPress() {
        if (alertActive) return
        if (petActive) return

        petActive = true
        tapQueued = false
        lastActivityAt = clock()
        cancelBlinkTimer()
        rememberBaseState()

        transitionTo(CompanionState.PET)
    }

    /**
     * Triggered when the finger is released after a long press.
     * Plays HEARTS, then returns to the saved looping state (CALM or MUSIC).
     */
    fun onLongPressReleased() {
        if (currentState != CompanionState.PET) return
        petActive = false
        transitionTo(CompanionState.HEARTS)
    }

    /**
     * Triggered by a short tap on the character overlay.
     *
     * - ALERT or PET is active → ignore (high-priority states block taps).
     * - ≥ [ANNOY_TAP_COUNT] taps within [ANNOY_WINDOW_MS] → ANNOYED (interrupts TAP).
     * - Sleeping or falling asleep → WAKE.
     * - A looping state (CALM, MUSIC) is active → play TAP immediately.
     * - TAP, ANNOYED or WAKE is playing → ignore (the tap still counts towards ANNOYED).
     * - Another one-shot is active (BLINK, GREETING, HEARTS…) → queue TAP.
     */
    fun onTap() {
        if (alertActive || petActive) return

        val now = clock()
        lastActivityAt = now
        recentTaps.addLast(now)
        while (recentTaps.first() < now - ANNOY_WINDOW_MS) recentTaps.removeFirst()

        if (recentTaps.size >= ANNOY_TAP_COUNT && currentState != CompanionState.ANNOYED) {
            recentTaps.clear()
            tapQueued = false
            rememberBaseState()
            transitionTo(CompanionState.ANNOYED)
            return
        }

        when (currentState) {
            CompanionState.SLEEP, CompanionState.FALL_ASLEEP -> transitionTo(CompanionState.WAKE)
            CompanionState.TAP, CompanionState.ANNOYED, CompanionState.WAKE -> {}
            else -> if (currentState.isLooping) {
                // Immediate: remember where to return and play TAP
                rememberBaseState()
                transitionTo(CompanionState.TAP)
            } else {
                // One-shot in progress — queue for later
                tapQueued = true
            }
        }
    }

    fun destroy() {
        cancelBlinkTimer()
    }

    // Auto profile updates

    /**
     * Detects the start of a trip: the car stood still for at least [PARKED_MS],
     * then reached [MOVING_KMH]. Short stops (traffic lights) do not count.
     * If ALERT or PET is playing at that moment, TRIP_START is retried on the next updates
     * for up to [TRIP_START_RETRY_MS] after departure.
     */
    fun updateDrivingData(data: DrivingData) {
        if (!data.hasGpsFix) return
        val now = clock()
        lastSpeedKmh = data.speedKmh
        lastSpeedAt = now

        if (data.speedKmh < STOPPED_KMH) {
            if (stoppedSince == 0L) stoppedSince = now
            if (now - stoppedSince >= PARKED_MS) {
                parked = true
                departedAt = 0L
            }
        } else {
            stoppedSince = 0L
            if (parked && data.speedKmh >= MOVING_KMH) {
                if (departedAt == 0L) departedAt = now
                if (now - departedAt > TRIP_START_RETRY_MS) {
                    parked = false       // too late to celebrate the departure
                } else if (onTripStarted()) {
                    parked = false
                }
            }
        }
    }

    /** Plays TRIP_START; returns false if a higher-priority state (ALERT, PET) blocked it. */
    private fun onTripStarted(): Boolean {
        if (alertActive || petActive) return false
        lastActivityAt = clock()
        tapQueued = false
        rememberBaseState()
        transitionTo(CompanionState.TRIP_START)
        return true
    }

    /**
     * True if fresh GPS data shows the car is moving (even slowly). Stale speed —
     * no updates for [GPS_STALE_MS], e.g. GPS lost in a tunnel — does not count as moving.
     */
    private fun isConfirmedMoving(): Boolean =
        lastSpeedKmh >= STOPPED_KMH && clock() - lastSpeedAt <= GPS_STALE_MS

    fun updateMediaData(data: MediaData) {
        if (!settings.musicReactionEnabled) {
            if (currentState == CompanionState.MUSIC) {
                transitionTo(CompanionState.CALM)
            } else if (baseLoopingState == CompanionState.MUSIC) {
                baseLoopingState = CompanionState.CALM
            }
            return
        }

        if (data.isPlaying && currentState != CompanionState.MUSIC) {
            if (baseLoopingState != CompanionState.MUSIC) lastActivityAt = clock()
            when (currentState) {
                // MUSIC has priority over CALM/BLINK only
                CompanionState.CALM, CompanionState.BLINK -> transitionTo(CompanionState.MUSIC)
                // Music wakes the companion up, then it starts dancing
                CompanionState.SLEEP -> {
                    baseLoopingState = CompanionState.MUSIC
                    transitionTo(CompanionState.WAKE)
                }
                // Don't override one-shots or PET mid-play — just update the return target
                else -> baseLoopingState = CompanionState.MUSIC
            }
        } else if (!data.isPlaying) {
            if (currentState == CompanionState.MUSIC) {
                lastActivityAt = clock()
                transitionTo(CompanionState.CALM)
            } else if (baseLoopingState == CompanionState.MUSIC) {
                // Music stopped while a one-shot/pet is playing — return to CALM instead
                lastActivityAt = clock()
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

    /**
     * Saves the current state as the return target if it is a true ambient state (CALM/MUSIC).
     * For one-shots, PET and SLEEP, baseLoopingState already holds the correct target.
     */
    private fun rememberBaseState() {
        if (currentState == CompanionState.CALM || currentState == CompanionState.MUSIC) {
            baseLoopingState = currentState
        }
    }

    /** Ends a one-shot: plays a queued TAP, otherwise returns to the looping state. */
    private fun returnToBaseState() {
        if (tapQueued) {
            tapQueued = false
            transitionTo(CompanionState.TAP)
        } else {
            transitionTo(baseLoopingState)
        }
    }

    private fun transitionTo(newState: CompanionState) {
        val previous = currentState

        // If a one-shot was cut short by a looping state, the queued tap is stale.
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
            // BLINK: the timer has already fired; others don't blink.
            else -> cancelBlinkTimer()
        }
    }

    /** Blink timer tick in CALM: blink, or doze off after a long period without activity. */
    private fun triggerBlink() {
        if (currentState != CompanionState.CALM) return
        val idleFor = clock() - lastActivityAt
        if (idleFor >= SLEEP_AFTER_MS && !isConfirmedMoving()) {
            transitionTo(CompanionState.FALL_ASLEEP)
        } else {
            transitionTo(CompanionState.BLINK)
        }
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

        /** Taps within [ANNOY_WINDOW_MS] that make the companion ANNOYED. */
        private const val ANNOY_TAP_COUNT = 5
        private const val ANNOY_WINDOW_MS = 3000L

        /** Time without any activity in CALM before falling asleep. */
        private const val SLEEP_AFTER_MS = 3 * 60_000L

        /** Below this speed the car counts as standing still; at or above it the companion stays awake. */
        private const val STOPPED_KMH = 3f

        /** Speed that counts as "the trip has started". */
        private const val MOVING_KMH = 15f

        /** GPS speed older than this is ignored when deciding whether the car is moving. */
        private const val GPS_STALE_MS = 10_000L

        /** How long after departure a TRIP_START blocked by ALERT/PET may still be played. */
        private const val TRIP_START_RETRY_MS = 30_000L

        /** Standstill longer than this (not a traffic light) makes the next departure a TRIP_START. */
        private const val PARKED_MS = 3 * 60_000L
    }
}
