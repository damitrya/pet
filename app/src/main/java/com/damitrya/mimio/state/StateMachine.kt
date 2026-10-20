package com.damitrya.mimio.state

import android.os.Handler
import android.os.Looper
import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.AppUsageData
import com.damitrya.mimio.data.BatteryData
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.GpsFixGate
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.PhoneCallData
import com.damitrya.mimio.data.ScreenData
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.data.StepData
import com.damitrya.mimio.data.TimeData
import com.damitrya.mimio.data.TurnDirection
import com.damitrya.mimio.data.WeatherData
import kotlin.random.Random

/**
 * Finite state machine that determines the companion's current state.
 * Manages automatic BLINK transitions while in CALM state, short FIDGET breaks while dancing
 * to MUSIC, and falls asleep after a long period without interaction.
 *
 * Priorities: ALERT > PET > interaction one-shots (TAP, BOUNCE, ANNOYED, HEARTS, SHY, WAKE) and
 * RELIEF / TRIP_START / ARRIVAL > sharp turns (TURN_LEFT/RIGHT, see [TURN_INTERRUPTIBLE]) >
 * GREETING, TRIP_PREPARE, STRETCH >
 * ambient: PHONE_CALL > MUSIC > CALM / SLEEP. Idle one-shots (BLINK, FIDGET, DROWSY, LOW_BATTERY)
 * only start from an ambient state; BLINK and dozing off also wait for an idle scene ([idleBusy]).
 * Which file each state plays is up to [AnimationDirector]; it never changes the state.
 *
 * Every one-shot is ended by force after [ONE_SHOT_TIMEOUT_MS] if its end is never reported.
 * User and car events also feed the [CharacterMood].
 *
 * The car trip (PARKED → PREPARING → DRIVING → ARRIVED) is followed by [TripTracker]: getting into the
 * car plays TRIP_PREPARE, the end of a long trip ARRIVAL; both end at once when the car starts moving.
 *
 * Hiding by the user plays FAREWELL first ([startFarewell]); any other state that takes over ends it early.
 */
class StateMachine(
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: Scheduler = HandlerScheduler(),
    private val random: Random = Random.Default,
    private val mood: CharacterMood? = null
) {

    /** Delayed tasks; the default runs them on the main thread, tests drive them by hand. */
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    class HandlerScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun postDelayed(task: Runnable, delayMs: Long) {
            handler.postDelayed(task, delayMs)
        }
        override fun cancel(task: Runnable) = handler.removeCallbacks(task)
    }

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
        fun onWeatherModifierChanged(modifier: WeatherModifier)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.CALM
    private var currentWeatherModifier: WeatherModifier = WeatherModifier.NONE

    /**
     * The last looping (ambient) state: CALM, MUSIC or PHONE_CALL.
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

    /** Times of recent taps, used to detect tap spam (→ ANNOYED) and double taps (→ BOUNCE). */
    private val recentTaps = ArrayDeque<Long>()

    /** Times of recent pettings (finger released), used to detect a petting series (→ SHY). */
    private val recentPets = ArrayDeque<Long>()

    /** True while a phone call is active; PHONE_CALL is then the ambient state. */
    private var callActive: Boolean = false

    /** True while music is playing and music reactions are enabled (remembered during a call). */
    private var musicPlaying: Boolean = false

    /** When DROWSY was last played; it is shown at most once per idle period. */
    private var drowsyAt: Long = 0L

    /** Last sharp-turn reaction and the previous turning flag (reaction fires on the rising edge). */
    private var turnAt: Long = 0L
    private var wasTurning: Boolean = false

    /** Next time LOW_BATTERY may play (0 = as soon as the battery is low). */
    private var nextLowBatteryAt: Long = 0L

    /** Last morning STRETCH; the time provider may report the same first unlock more than once. */
    private var stretchAt: Long = 0L

    /** Last tap, long press, alert, music change or trip start. Drives falling asleep. */
    private var lastActivityAt: Long = clock()

    /** Latest GPS speed and when it arrived; the companion does not fall asleep while the car is moving. */
    private var lastSpeedKmh: Float = 0f
    private var lastSpeedAt: Long = 0L

    /** New usable GPS fixes only (sensor events repeat the last one); same rule as the service. */
    private val fixGate = GpsFixGate()

    /** When the car came to a stop (0 = moving or unknown). */
    private var stoppedSince: Long = 0L

    /** True once the car has been standing long enough for the next departure to count as a trip start. */
    private var parked: Boolean = false

    /**
     * True until the first GPS fix after a reset. Standing still at that moment also counts as
     * parked: the usual case is "get in, start the app (or the car's Bluetooth connects), drive off".
     */
    private var awaitingFirstFix: Boolean = true

    /** When the car left the parking spot; TRIP_START blocked by ALERT/PET is retried until [TRIP_START_RETRY_MS]. */
    private var departedAt: Long = 0L

    /** Last tap or long press by the user (for friendlier reactions shortly afterwards). */
    private var lastInteractionAt: Long = 0L

    /** Active profile: car animations only play in AUTO. */
    private var profile: AppProfile = AppProfile.DAILY

    /** The car trip, from the profile switch and the GPS (see [TripTracker]). */
    private val trip = TripTracker(clock)

    /** When an ARRIVAL blocked by ALERT/PET became due; retried until [ARRIVAL_RETRY_MS] (0 = none). */
    private var arrivalDueAt: Long = 0L

    /**
     * True while the character plays a self-started idle scene (see [AnimationDirector.isSceneRunning]).
     * Blinking and dozing off wait for it; user and car reactions do not.
     */
    var idleBusy: () -> Boolean = { false }

    /** True while a scene or an active animation plays (see [AnimationDirector.isDistractingSceneRunning]). */
    var distractingScene: () -> Boolean = { false }

    /** Asks the view to replace the running CALM variant at once (the car started moving). */
    var onSceneCut: () -> Unit = {}

    /** Confirmed movement at the previous GPS update (a scene is cut on the rising edge). */
    private var wasMoving: Boolean = false

    private val blinkRunnable = Runnable { triggerBlink() }

    /** Hides the overlay once FAREWELL is over (null: no farewell running). Called at most once. */
    private var farewellDone: (() -> Unit)? = null

    /** The farewell never takes longer than [FAREWELL_TIMEOUT_MS], even if its end is never reported. */
    private val farewellTimeout = Runnable { endFarewell() }

    /** One-shot that never reported its end (e.g. the overlay was hidden meanwhile). */
    private var watchedOneShot: CompanionState? = null
    private val oneShotWatchdog = Runnable {
        watchedOneShot?.let { if (it == currentState) onOneShotCompleted(it) }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    /** Context for [AnimationDirector]: driving, music, recent interaction. */
    fun playContext(): PlayContext = PlayContext(
        moving = isConfirmedMoving() || isShortStop(),
        music = musicPlaying,
        sinceInteractionMs = if (lastInteractionAt == 0L) Long.MAX_VALUE else clock() - lastInteractionAt,
        car = profile == AppProfile.AUTO,
        // A long stop in the middle of a trip (jam, level crossing) is not a parked car.
        parked = profile == AppProfile.AUTO && trip.phase != TripPhase.DRIVING && trip.standingFor(SHORT_STOP_MS),
        trip = trip.phase
    )

    /** Phase of the car trip. */
    fun tripPhase(): TripPhase = trip.phase

    /**
     * Records the profile restored at start-up without a switch (no reset, no TRIP_PREPARE):
     * the service may restart in the middle of a drive.
     */
    fun syncProfile(profile: AppProfile) {
        this.profile = profile
    }

    /**
     * A real profile switch (getting into / out of the car): the trip and the departure tracking start
     * over. Showing the overlay again is not a switch — see [resetInteraction].
     */
    fun setProfile(profile: AppProfile) {
        this.profile = profile
        // Bluetooth / a manual switch is not movement: the trip is only getting ready.
        if (profile == AppProfile.AUTO) trip.onEnteredCar() else trip.onLeftCar()
        arrivalDueAt = 0L
        lastSpeedKmh = 0f
        lastSpeedAt = 0L
        stoppedSince = 0L
        parked = false
        awaitingFirstFix = true
        departedAt = 0L
        wasTurning = false
        wasMoving = false
        resetInteraction()
    }

    /**
     * Starts the character over (profile switch, overlay shown again) without touching the car:
     * the trip, its driving time and the GPS state are kept.
     */
    fun resetInteraction() {
        // Forced reset: ALERT/PET may be cut short here, and their completion callbacks
        // will no longer match the current state, so clear their guards explicitly.
        alertActive = false
        petActive = false
        tapQueued = false
        recentTaps.clear()
        recentPets.clear()
        lastActivityAt = clock()
        // A call or music outlives a profile switch: go straight back to it.
        transitionTo(ambientState())
    }

    /** Called when the overlay appears: plays GREETING, then returns to CALM. */
    fun onOverlayShown() {
        if (alertActive || petActive) return
        lastActivityAt = clock()
        mood?.onEvent(MoodEvent.GREETING)
        rememberBaseState()
        transitionTo(CompanionState.GREETING)
    }

    /**
     * Getting into the car (switch to AUTO with the overlay shown): TRIP_PREPARE instead of GREETING —
     * the fox buckles up or checks the map. Nothing starts moving because of it.
     */
    fun onCarEntered() {
        if (alertActive || petActive) return
        lastActivityAt = clock()
        mood?.onEvent(MoodEvent.GREETING)
        rememberBaseState()
        transitionTo(CompanionState.TRIP_PREPARE)
    }

    /**
     * The car's Bluetooth disconnected (engine off). With a long trip behind and the GPS showing the
     * car standing, the trip is over: ARRIVAL. A disconnect on its own changes nothing.
     */
    fun onCarDisconnected() {
        if (profile != AppProfile.AUTO) return
        if (trip.onEngineOff() == TripEvent.ARRIVED) {
            arrivalDueAt = clock()
            playArrival()
        }
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
            CompanionState.FAREWELL -> endFarewell()
            CompanionState.FALL_ASLEEP -> {
                // Music or a call started while dozing off: wake up (from the pose it dozed off in),
                // then WAKE returns to MUSIC / PHONE_CALL.
                transitionTo(
                    if (baseLoopingState != CompanionState.CALM) CompanionState.WAKE
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
        if (farewellDone != null) return      // already saying goodbye

        petActive = true
        tapQueued = false
        lastActivityAt = clock()
        lastInteractionAt = lastActivityAt
        cancelBlinkTimer()
        rememberBaseState()

        transitionTo(CompanionState.PET)
    }

    /**
     * Triggered when the finger is released after a long press.
     * Plays HEARTS — or SHY after [SHY_PET_COUNT] pettings within [SHY_WINDOW_MS] —
     * then returns to the saved looping state.
     */
    fun onLongPressReleased() {
        if (currentState != CompanionState.PET) return
        petActive = false
        val now = clock()
        mood?.onEvent(MoodEvent.PET)
        recentPets.addLast(now)
        while (recentPets.first() < now - SHY_WINDOW_MS) recentPets.removeFirst()
        if (recentPets.size >= SHY_PET_COUNT) {
            recentPets.clear()
            transitionTo(CompanionState.SHY)
        } else {
            transitionTo(CompanionState.HEARTS)
        }
    }

    /**
     * Triggered by a short tap on the character overlay.
     *
     * - ALERT or PET is active → ignore (high-priority states block taps).
     * - ≥ [ANNOY_TAP_COUNT] taps within [ANNOY_WINDOW_MS] → ANNOYED (interrupts TAP).
     * - Second tap within [DOUBLE_TAP_MS] while TAP plays → BOUNCE (replaces TAP).
     * - Sleeping or falling asleep → WAKE.
     * - A looping state (CALM, MUSIC, PHONE_CALL) is active → play TAP immediately.
     * - TAP, BOUNCE, ANNOYED or WAKE is playing → ignore (the tap still counts towards ANNOYED).
     * - Another one-shot is active (BLINK, GREETING, HEARTS…) → queue TAP.
     */
    fun onTap() {
        if (alertActive || petActive || farewellDone != null) return

        val now = clock()
        val previousTap = recentTaps.lastOrNull()
        lastActivityAt = now
        lastInteractionAt = now
        mood?.onEvent(MoodEvent.TAP)
        recentTaps.addLast(now)
        while (recentTaps.first() < now - ANNOY_WINDOW_MS) recentTaps.removeFirst()

        if (recentTaps.size >= ANNOY_TAP_COUNT && currentState != CompanionState.ANNOYED) {
            recentTaps.clear()
            tapQueued = false
            rememberBaseState()
            transitionTo(CompanionState.ANNOYED)
            return
        }

        if (currentState == CompanionState.TAP && previousTap != null && now - previousTap <= DOUBLE_TAP_MS) {
            transitionTo(CompanionState.BOUNCE)
            return
        }

        when (currentState) {
            CompanionState.SLEEP, CompanionState.FALL_ASLEEP -> transitionTo(CompanionState.WAKE)
            CompanionState.TAP, CompanionState.BOUNCE, CompanionState.ANNOYED, CompanionState.WAKE -> {}
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

    /** True while ALERT or PET plays: a profile switch now would cut it short. */
    fun isBusy(): Boolean = alertActive || petActive

    /**
     * The user hides the character: it turns into a heart (FAREWELL) and [onDone] hides the overlay when
     * that is over — at the latest after [FAREWELL_TIMEOUT_MS]. Returns false, and plays nothing, during a
     * critical reaction (braking, a sharp turn) or while asleep: the caller then hides at once.
     * Any other state taking over (braking, a profile switch…) ends the farewell early and hides.
     */
    fun startFarewell(onDone: () -> Unit): Boolean {
        if (farewellDone != null) return true
        if (alertActive || currentState in NO_FAREWELL) return false
        petActive = false
        tapQueued = false
        rememberBaseState()
        farewellDone = onDone
        transitionTo(CompanionState.FAREWELL)
        scheduler.postDelayed(farewellTimeout, FAREWELL_TIMEOUT_MS)
        return true
    }

    fun isFarewellRunning(): Boolean = farewellDone != null

    /** Forgets a running farewell without hiding (shown again, hidden at once, or the service stops). */
    fun cancelFarewell() {
        farewellDone = null
        scheduler.cancel(farewellTimeout)
    }

    /** Hides (once) and lets the character rest in its ambient state behind the hidden overlay. */
    private fun endFarewell() {
        finishFarewell()
        if (currentState == CompanionState.FAREWELL) {
            tapQueued = false
            transitionTo(ambientState())
        }
    }

    private fun finishFarewell() {
        val done = farewellDone ?: return
        cancelFarewell()
        done()
    }

    fun destroy() {
        cancelBlinkTimer()
        cancelFarewell()
        scheduler.cancel(oneShotWatchdog)
    }

    // Auto profile updates

    /**
     * Detects the start of a trip: the car stood still for at least [PARKED_MS] (or was standing
     * at the first GPS fix after start), then reached [MOVING_KMH]. Short stops (traffic lights)
     * do not count.
     * If ALERT or PET is playing at that moment, TRIP_START is retried on the next updates
     * for up to [TRIP_START_RETRY_MS] after departure.
     */
    fun updateDrivingData(data: DrivingData) {
        if (!data.hasGpsFix) return
        val now = clock()
        // Braking / turn events repeat the last fix: they must not make an old speed look fresh.
        val usable = fixGate.isNewUsableFix(data)
        if (usable) {
            lastSpeedKmh = data.speedKmh
            lastSpeedAt = now - data.fixAgeMs
        }

        if (data.isTurning && !wasTurning) onSharpTurn(data.turnDirection)
        wasTurning = data.isTurning
        if (!usable) return

        val moving = isConfirmedMoving()
        if (moving && !wasMoving) onStartedMoving()
        wasMoving = moving

        val firstFix = awaitingFirstFix
        awaitingFirstFix = false

        if (profile == AppProfile.AUTO && trip.onGps(data.speedKmh, lastSpeedAt) == TripEvent.ARRIVED) arrivalDueAt = now
        playArrival()

        if (data.speedKmh < STOPPED_KMH) {
            if (stoppedSince == 0L) stoppedSince = now
            if (firstFix || now - stoppedSince >= PARKED_MS) {
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

    /**
     * Sharp turn: the character is thrown to the outside of the turn. Needs real driving speed
     * ([TURN_MIN_KMH]) so that turning the phone in the hand does not count. Only interrupts the
     * states in [TURN_INTERRUPTIBLE]; a skipped turn does not start the cooldown.
     */
    private fun onSharpTurn(direction: TurnDirection) {
        if (direction == TurnDirection.NONE) return
        if (currentState !in TURN_INTERRUPTIBLE) return
        val now = clock()
        if (now - turnAt < TURN_COOLDOWN_MS) return
        if (lastSpeedKmh < TURN_MIN_KMH || now - lastSpeedAt > GPS_STALE_MS) return
        turnAt = now
        lastActivityAt = now
        rememberBaseState()
        transitionTo(
            if (direction == TurnDirection.LEFT) CompanionState.TURN_LEFT else CompanionState.TURN_RIGHT
        )
    }

    /**
     * The car started moving: a scene or active animation that began while standing (butterfly, tail
     * chasing…) stops at once. A FIDGET break ends (back to MUSIC); in CALM the view swaps the variant
     * for a calm one. Reactions and other states are left alone.
     */
    private fun onStartedMoving() {
        when (currentState) {
            // Car scenes for a standing car: buckling up, the flag, parking are over once it moves.
            CompanionState.TRIP_PREPARE, CompanionState.ARRIVAL -> returnToBaseState()
            CompanionState.FIDGET -> if (distractingScene()) returnToBaseState()
            CompanionState.CALM, CompanionState.MUSIC -> if (distractingScene()) onSceneCut()
            else -> {}
        }
    }

    /**
     * Plays a due ARRIVAL. Blocked by ALERT/PET it waits (retried on the next GPS updates) for up to
     * [ARRIVAL_RETRY_MS]; if the car moves again first, it is dropped.
     */
    private fun playArrival() {
        val due = arrivalDueAt
        if (due == 0L) return
        if (clock() - due > ARRIVAL_RETRY_MS || isConfirmedMoving()) {
            arrivalDueAt = 0L
            return
        }
        if (alertActive || petActive) return
        arrivalDueAt = 0L
        lastActivityAt = clock()
        mood?.onEvent(MoodEvent.ARRIVAL)
        tapQueued = false
        rememberBaseState()
        transitionTo(CompanionState.ARRIVAL)
    }

    /** Plays TRIP_START; returns false if a higher-priority state (ALERT, PET) blocked it. */
    private fun onTripStarted(): Boolean {
        if (alertActive || petActive) return false
        lastActivityAt = clock()
        mood?.onEvent(MoodEvent.TRIP_START)
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

    /** Standing for less than [SHORT_STOP_MS] (traffic light, jam): the car will move off again soon. */
    private fun isShortStop(): Boolean =
        stoppedSince != 0L && !parked && clock() - stoppedSince < SHORT_STOP_MS &&
            clock() - lastSpeedAt <= GPS_STALE_MS

    fun updateMediaData(data: MediaData) {
        musicPlaying = data.isPlaying && settings.musicReactionEnabled
        // During a call PHONE_CALL wins; the call end picks MUSIC or CALM from musicPlaying.
        if (callActive) return

        if (!settings.musicReactionEnabled) {
            if (currentState == CompanionState.MUSIC) {
                transitionTo(CompanionState.CALM)
            } else if (baseLoopingState == CompanionState.MUSIC) {
                baseLoopingState = CompanionState.CALM
            }
            return
        }

        if (data.isPlaying && currentState != CompanionState.MUSIC) {
            if (baseLoopingState != CompanionState.MUSIC) {
                lastActivityAt = clock()
                mood?.onEvent(MoodEvent.MUSIC_START)
            }
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

    /**
     * Low battery (not charging): LOW_BATTERY from CALM or MUSIC, repeated every
     * [LOW_BATTERY_REPEAT_MS] while it stays low. Battery updates are frequent, so a moment
     * when the character is busy is simply retried on the next one.
     */
    fun updateBatteryData(data: BatteryData) {
        if (!data.isLow || data.isCharging) {
            nextLowBatteryAt = 0L
            return
        }
        val now = clock()
        if (now < nextLowBatteryAt || !isAmbientAwake()) return
        nextLowBatteryAt = now + LOW_BATTERY_REPEAT_MS
        rememberBaseState()
        transitionTo(CompanionState.LOW_BATTERY)
    }

    /** First unlock of the day in the morning: STRETCH (also straight out of SLEEP). */
    fun updateTimeData(data: TimeData) {
        if (!data.isFirstUnlockOfDay) return
        val now = clock()
        if (stretchAt != 0L && now - stretchAt < STRETCH_DEDUP_MS) return
        if (!isAmbientAwake() && currentState != CompanionState.SLEEP) return
        stretchAt = now
        lastActivityAt = now
        rememberBaseState()
        transitionTo(CompanionState.STRETCH)
    }

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

    /**
     * Phone call start/end. PHONE_CALL becomes the ambient state: it replaces CALM/MUSIC at once
     * (after WAKE if sleeping); one-shots and PET finish first and then return to it.
     */
    fun updatePhoneCallData(data: PhoneCallData) {
        if (data.isInCall == callActive) return
        callActive = data.isInCall
        lastActivityAt = clock()

        if (callActive) {
            when (currentState) {
                CompanionState.CALM, CompanionState.MUSIC -> transitionTo(CompanionState.PHONE_CALL)
                CompanionState.SLEEP -> {
                    baseLoopingState = CompanionState.PHONE_CALL
                    transitionTo(CompanionState.WAKE)
                }
                else -> baseLoopingState = CompanionState.PHONE_CALL
            }
        } else {
            if (currentState == CompanionState.PHONE_CALL) {
                transitionTo(ambientState())
            } else if (baseLoopingState == CompanionState.PHONE_CALL) {
                baseLoopingState = ambientState()
            }
        }
    }

    /** The looping state the character should rest in right now. */
    private fun ambientState(): CompanionState = when {
        callActive -> CompanionState.PHONE_CALL
        musicPlaying -> CompanionState.MUSIC
        else -> CompanionState.CALM
    }

    /** True in CALM or MUSIC: free for idle one-shots that must not interrupt anything. */
    private fun isAmbientAwake(): Boolean =
        currentState == CompanionState.CALM || currentState == CompanionState.MUSIC

    /**
     * Saves the current state as the return target if it is a true ambient state (CALM/MUSIC/PHONE_CALL).
     * For one-shots, PET and SLEEP, baseLoopingState already holds the correct target.
     */
    private fun rememberBaseState() {
        if (currentState == CompanionState.CALM || currentState == CompanionState.MUSIC ||
            currentState == CompanionState.PHONE_CALL
        ) {
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
        // Something else took over the farewell (braking, a profile switch…): hide now, before it shows.
        if (previous == CompanionState.FAREWELL && newState != CompanionState.FAREWELL) finishFarewell()

        // If a one-shot was cut short by a looping state, the queued tap is stale.
        if (!previous.isLooping && newState.isLooping) {
            tapQueued = false
        }

        currentState = newState
        mood?.setAsleep(newState == CompanionState.SLEEP)
        listener?.onStateChanged(newState, previous)

        scheduler.cancel(oneShotWatchdog)
        if (!newState.isLooping) {
            watchedOneShot = newState
            scheduler.postDelayed(oneShotWatchdog, ONE_SHOT_TIMEOUT_MS)
        }

        when (newState) {
            CompanionState.CALM -> {
                baseLoopingState = CompanionState.CALM
                scheduleNextBlink()
            }
            CompanionState.MUSIC -> {
                baseLoopingState = CompanionState.MUSIC
                scheduleNextBlink()
            }
            CompanionState.PHONE_CALL -> {
                baseLoopingState = CompanionState.PHONE_CALL
                cancelBlinkTimer()
            }
            // BLINK: the timer has already fired; others don't blink.
            else -> cancelBlinkTimer()
        }
    }

    /**
     * Blink timer tick in CALM: blink; after a long period without activity first get DROWSY
     * (once), then doze off. Neither happens while the car is moving.
     */
    private fun triggerBlink() {
        if (currentState == CompanionState.MUSIC && idleBusy()) {
            // A show (guitar, microphone) is on: the dance break waits until it is over.
            scheduler.cancel(blinkRunnable)
            scheduler.postDelayed(blinkRunnable, SCENE_RETRY_MS)
            return
        }
        if (currentState == CompanionState.MUSIC) {
            // Music never ends in the car — break up the dancing with other idle reactions.
            transitionTo(CompanionState.FIDGET)
            return
        }
        if (currentState != CompanionState.CALM) return
        if (idleBusy()) {
            // Let the scene finish; blinking (or dozing off) right after it.
            scheduler.cancel(blinkRunnable)
            scheduler.postDelayed(blinkRunnable, SCENE_RETRY_MS)
            return
        }
        val now = clock()
        val idleFor = now - lastActivityAt
        val moving = isConfirmedMoving() || isShortStop()     // not at a traffic light either
        if (idleFor >= SLEEP_AFTER_MS && !moving) {
            transitionTo(CompanionState.FALL_ASLEEP)
        } else if (idleFor >= DROWSY_AFTER_MS && drowsyAt < lastActivityAt && !moving) {
            drowsyAt = now
            transitionTo(CompanionState.DROWSY)
        } else {
            transitionTo(CompanionState.BLINK)
        }
    }

    /** Next blink in CALM (3–7 s) or the next FIDGET break in MUSIC (20–40 s). */
    private fun scheduleNextBlink() {
        cancelBlinkTimer()
        val delayMs = if (currentState == CompanionState.MUSIC) {
            20_000L + random.nextLong(20_001L)
        } else {
            3000L + random.nextLong(4001L)
        }
        scheduler.postDelayed(blinkRunnable, delayMs)
    }

    private fun cancelBlinkTimer() {
        scheduler.cancel(blinkRunnable)
    }

    companion object {
        private const val ALERT_COOLDOWN_MS = 3000L

        /** A one-shot that has not finished after this long is ended by force (no stuck states). */
        const val ONE_SHOT_TIMEOUT_MS = 15_000L

        /** The overlay is hidden at the latest this long after the farewell began (the heart takes ≈4.6 s). */
        const val FAREWELL_TIMEOUT_MS = 6_000L

        /** No farewell during critical car reactions or while asleep: hiding is immediate then. */
        private val NO_FAREWELL = setOf(
            CompanionState.ALERT, CompanionState.TURN_LEFT, CompanionState.TURN_RIGHT,
            CompanionState.SLEEP, CompanionState.FALL_ASLEEP
        )

        /**
         * A stop shorter than this still counts as driving for the choice of animations (no scenes);
         * a longer one with fresh GPS is a confirmed standstill (car scenes for a parked car).
         */
        private const val SHORT_STOP_MS = 60_000L

        /** How long after the trip ended an ARRIVAL blocked by ALERT/PET may still be played. */
        private const val ARRIVAL_RETRY_MS = 30_000L

        /** While an idle scene plays, the blink timer checks again after this delay. */
        private const val SCENE_RETRY_MS = 1000L

        /** Taps within [ANNOY_WINDOW_MS] that make the companion ANNOYED. */
        private const val ANNOY_TAP_COUNT = 5
        private const val ANNOY_WINDOW_MS = 3000L

        /** Second tap within this time of the first one while TAP plays → BOUNCE. */
        private const val DOUBLE_TAP_MS = 350L

        /** Pettings within [SHY_WINDOW_MS] that make the companion SHY instead of HEARTS. */
        private const val SHY_PET_COUNT = 3
        private const val SHY_WINDOW_MS = 20_000L

        /** Time without any activity in CALM before falling asleep. */
        private const val SLEEP_AFTER_MS = 3 * 60_000L

        /** Time without any activity in CALM before DROWSY (once, ahead of falling asleep). */
        private const val DROWSY_AFTER_MS = 2 * 60_000L

        /**
         * States a sharp turn may interrupt: ambient states and one-shots nobody asked for.
         * Reactions to the user (TAP, BOUNCE, ANNOYED, PET, HEARTS, SHY, WAKE), ALERT/RELIEF,
         * TRIP_START and a running turn are left alone.
         */
        private val TURN_INTERRUPTIBLE = setOf(
            CompanionState.CALM, CompanionState.MUSIC, CompanionState.PHONE_CALL, CompanionState.SLEEP,
            CompanionState.BLINK, CompanionState.FIDGET, CompanionState.DROWSY, CompanionState.LOW_BATTERY,
            CompanionState.STRETCH, CompanionState.GREETING, CompanionState.FALL_ASLEEP,
            CompanionState.TRIP_PREPARE
        )

        /** Minimum speed and pause between sharp-turn reactions. */
        private const val TURN_MIN_KMH = 20f
        private const val TURN_COOLDOWN_MS = 4000L

        /** How often LOW_BATTERY repeats while the battery stays low and is not charging. */
        private const val LOW_BATTERY_REPEAT_MS = 10 * 60_000L

        /** A repeated first-unlock report within this time does not stretch again. */
        private const val STRETCH_DEDUP_MS = 60_000L

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
