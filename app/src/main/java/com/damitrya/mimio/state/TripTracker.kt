package com.damitrya.mimio.state

/** Where the car trip is: PARKED → PREPARING → DRIVING → ARRIVED (→ DRIVING again, or PARKED). */
enum class TripPhase {
    /** Standing, nothing going on (or the companion is out of the car). */
    PARKED,

    /** Just got into the car (AUTO profile): getting ready, the car has not moved yet. */
    PREPARING,

    /** The GPS showed real driving speed; short stops (traffic lights, jams) stay in this phase. */
    DRIVING,

    /** A long enough trip ended with a confirmed stop. */
    ARRIVED
}

/** What a GPS update or the car switching off meant for the trip. */
enum class TripEvent { NONE, DEPARTED, ARRIVED }

/**
 * Lifecycle of a car trip, from the profile switch and the GPS speed only. Pure logic: the
 * [StateMachine] feeds it and decides what to play.
 *
 * - Getting into the car (AUTO profile) only means PREPARING: a Bluetooth connection is not movement.
 * - DRIVING needs a fresh GPS speed of at least [MOVING_KMH].
 * - ARRIVED needs at least [MIN_TRIP_MS] of driving and then a confirmation that the car is parked:
 *   the car switching off (its Bluetooth gone, see [onEngineOff]) after the GPS showed it standing for
 *   [ENGINE_OFF_STOP_MS] — the usual way — or, without Bluetooth, a standstill of [ARRIVAL_STOP_MS]
 *   with GPS fixes all along. That is deliberately long: traffic lights, level crossings and most jams
 *   are shorter, and celebrating in the middle of the route would be worse than celebrating late.
 *   Without a route, the end of a trip can never be told for sure. A short trip simply ends PARKED.
 * - A gap in the GPS fixes (tunnel, underground car park) restarts the standstill count: the car may
 *   have moved meanwhile. Without GPS nothing happens at all — no trip events are made up.
 */
class TripTracker(private val clock: () -> Long) {

    private var current: TripPhase = TripPhase.PARKED
    private var preparingSince = 0L

    /** Time spent moving during the current trip (stops excluded). */
    private var drivingMs = 0L

    private var lastFixAt = 0L
    private var lastSpeedKmh = 0f

    /** When the current standstill began (0 = moving or unknown). */
    private var stoppedSince = 0L

    /** Current phase; PREPARING without departure turns back into PARKED after [PREPARING_MS]. */
    val phase: TripPhase
        get() {
            if (current == TripPhase.PREPARING && clock() - preparingSince > PREPARING_MS) current = TripPhase.PARKED
            return current
        }

    /** The AUTO profile became active (car Bluetooth or a manual switch). */
    fun onEnteredCar() {
        if (current == TripPhase.DRIVING) return
        current = TripPhase.PREPARING
        preparingSince = clock()
        drivingMs = 0L
    }

    /** Back to the DAILY profile: whatever the trip was, it is over without a celebration. */
    fun onLeftCar() {
        current = TripPhase.PARKED
        drivingMs = 0L
        lastFixAt = 0L
        stoppedSince = 0L
    }

    /**
     * A new GPS fix with [speedKmh], taken at [at] (only real fixes: sensor events that repeat the
     * last speed must not come here). Returns [TripEvent.DEPARTED] or [TripEvent.ARRIVED] when the
     * phase changes so.
     */
    fun onGps(speedKmh: Float, at: Long = clock()): TripEvent {
        val now = at
        val fresh = lastFixAt != 0L && now - lastFixAt <= GPS_STALE_MS
        if (fresh && current == TripPhase.DRIVING && lastSpeedKmh >= STOPPED_KMH) drivingMs += now - lastFixAt
        if (!fresh) stoppedSince = 0L
        lastFixAt = now
        lastSpeedKmh = speedKmh
        if (speedKmh < STOPPED_KMH) {
            if (stoppedSince == 0L) stoppedSince = now
        } else {
            stoppedSince = 0L
        }

        return if (phase == TripPhase.DRIVING) {
            if (stoppedSince != 0L && now - stoppedSince >= ARRIVAL_STOP_MS) endTrip() else TripEvent.NONE
        } else if (speedKmh >= MOVING_KMH) {
            current = TripPhase.DRIVING
            drivingMs = 0L
            TripEvent.DEPARTED
        } else {
            TripEvent.NONE
        }
    }

    /**
     * The car's Bluetooth disconnected (the engine was switched off). Ends the trip only if the GPS
     * confirms the car is standing; on its own a disconnect proves nothing.
     */
    fun onEngineOff(): TripEvent {
        if (phase != TripPhase.DRIVING) return TripEvent.NONE
        val now = clock()
        val standing = stoppedSince != 0L && now - lastFixAt <= GPS_STALE_MS && now - stoppedSince >= ENGINE_OFF_STOP_MS
        return if (standing) endTrip() else TripEvent.NONE
    }

    /** The GPS shows the car standing (fresh fixes) for at least [ms]. */
    fun standingFor(ms: Long): Boolean {
        val now = clock()
        return stoppedSince != 0L && now - lastFixAt <= GPS_STALE_MS && now - stoppedSince >= ms
    }

    /** Driving time of the trip so far. Exposed for tests. */
    internal fun drivingTimeMs(): Long = drivingMs

    private fun endTrip(): TripEvent {
        val long = drivingMs >= MIN_TRIP_MS
        current = if (long) TripPhase.ARRIVED else TripPhase.PARKED
        drivingMs = 0L
        return if (long) TripEvent.ARRIVED else TripEvent.NONE
    }

    companion object {
        /** Below this speed the car stands still. */
        const val STOPPED_KMH = 3f

        /** Speed that counts as really driving (as for TRIP_START). */
        const val MOVING_KMH = 15f

        /** GPS speed older than this says nothing about the car. */
        const val GPS_STALE_MS = 10_000L

        /** Driving time that makes a trip worth celebrating at its end. */
        const val MIN_TRIP_MS = 10 * 60_000L

        /** Without the car switching off, a standstill this long ends the trip (longer than a level crossing). */
        const val ARRIVAL_STOP_MS = 8 * 60_000L

        /** With the car switched off, a standstill this long is enough. */
        const val ENGINE_OFF_STOP_MS = 10_000L

        /** Getting ready without driving off for this long: just parked. */
        const val PREPARING_MS = 5 * 60_000L
    }
}
