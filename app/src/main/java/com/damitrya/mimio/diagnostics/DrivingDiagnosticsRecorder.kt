package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.GpsFixGate
import com.damitrya.mimio.data.TurnDirection
import com.damitrya.mimio.state.CompanionState
import com.damitrya.mimio.state.TripEvent
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Records what the car pipeline measured and decided, without changing any of it: GPS fixes and the
 * [GpsFixGate] verdict, sensor registrations, braking / turn / acceleration candidates, movement and trip
 * events, reaction requests in the [com.damitrya.mimio.state.StateMachine] and the character's state changes.
 *
 * The hooks in the car code call it through [DrivingDiagnostics.sink], which is null unless a recording runs —
 * so nothing at all happens while diagnostics are off. Sensor samples are only aggregated (peaks per
 * [STATS_WINDOW_MS] window and candidate episodes), never written one by one. Accepted GPS fixes are written at
 * most every [ACCEPTED_FIX_LOG_MS]; every rejected fix is written. All counters go to the summary.
 *
 * Main thread only. Files are written on [io] (a single background thread), in order; the journal stops at
 * [limitBytes] with a LIMIT_REACHED entry. No coordinates ever reach it: [DrivingData] carries none.
 */
class DrivingDiagnosticsRecorder(
    private val storage: DiagnosticsStorage,
    private val io: Executor,
    /** Monotonic time (elapsedRealtime) for windows and episodes. */
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val limitBytes: Long = LIMIT_BYTES,
    /** The journal is full and the recording stopped (the user's switch should go off). */
    private val onLimitReached: () -> Unit = {}
) {

    private var summary = DrivingDiagnosticsSummary()
    private val timeFormat = DrivingDiagnosticsEvent.newTimeFormat()

    var isRunning = false
        private set

    private val brakeTracker = BrakeCandidateTracker()
    private val turnTracker = TurnCandidateTracker()
    private val accelTracker = AccelCandidateTracker()

    private var lastFixAt = 0L
    private var lastVerdict: GpsFixGate.Verdict? = null
    private var lastAcceptedLogAt = 0L
    private var lastHasGps = false
    private var lastSummaryAt = 0L
    private var lastRequestKey = ""
    private var lastRequestAt = 0L

    // Current sensor window
    private var windowStart = 0L
    private var winAccelSamples = 0
    private var winGyroSamples = 0
    private var winCheck: BrakeDetector.Check? = null
    private var winBrakeValue = Float.NaN
    private var winLateral = 0f
    private var winYaw = 0f
    private var winSpeed = 0f

    /**
     * Starts recording. A [previous] summary of an interrupted recording (the service stopped meanwhile) is
     * continued; anything else starts a new recording and clears the old files.
     */
    fun begin(previous: DrivingDiagnosticsSummary?, profile: String, brakeThreshold: Float) {
        val resume = previous != null && previous.startedAt != 0L &&
            (previous.status == DiagnosticsStatus.RECORDING || previous.status == DiagnosticsStatus.WAITING)
        if (resume) {
            summary = previous!!
        } else {
            summary = DrivingDiagnosticsSummary()
            summary.startedAt = wallClock()
            io.execute { storage.clear() }
        }
        summary.status = DiagnosticsStatus.RECORDING
        summary.endedAt = 0L
        isRunning = true
        lastSummaryAt = clock()
        windowStart = clock()
        emit(
            DrivingDiagnosticsEvent(
                if (resume) DrivingDiagnosticsEvent.Type.SESSION_RESUMED else DrivingDiagnosticsEvent.Type.SESSION_START,
                wallClock(),
                listOf(
                    "profile" to profile,
                    "gpsMaxAccuracy" to "${GpsFixGate.MAX_ACCURACY_M.f1()} m",
                    "gpsMaxAge" to "${GpsFixGate.MAX_AGE_MS} ms",
                    "brakeThreshold" to "${brakeThreshold.f1()} m/s2",
                    "brakeMinSpeed" to "${BrakeDetector.MIN_SPEED_KMH.f1()} km/h",
                    "candidateLevel" to "${(START_FRACTION * 100).toInt()}% of threshold",
                    "journalLimit" to "${limitBytes / 1024} KB"
                )
            )
        )
        onProfile(profile)
        persistSummary()
    }

    /** The service stops: the recording waits for it to start again ([begin] continues it). */
    fun pause() {
        if (!isRunning) return
        flushWindow()
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.SESSION_PAUSED, wallClock(), listOf("cause" to "SERVICE_STOPPED")))
        summary.status = DiagnosticsStatus.WAITING
        isRunning = false
        persistSummary()
    }

    /** The user switched recording off. */
    fun stop() {
        if (!isRunning) return
        flushWindow()
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.SESSION_END, wallClock(), listOf("cause" to "USER")))
        summary.status = DiagnosticsStatus.STOPPED
        summary.endedAt = wallClock()
        isRunning = false
        persistSummary()
    }

    /** A copy of the live statistics, for the screen and the export. */
    fun snapshot(): DrivingDiagnosticsSummary = summary.copy()

    /** How long ago the last GPS fix arrived (ms), or null if none in this run. */
    fun lastFixAgeMs(): Long? = if (lastFixAt == 0L) null else clock() - lastFixAt

    // --- Hooks ---

    fun onProfile(profile: String) {
        if (!isRunning) return
        summary.profile = profile
        summary.profiles += profile
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.PROFILE, wallClock(), listOf("profile" to profile)))
    }

    fun onSensorRegistration(reg: SensorRegistration) {
        if (!isRunning) return
        summary.registrations["${reg.owner}/${reg.sensor}"] = reg.registered
        summary.sensorAvailable[reg.sensor] = reg.available
        val ok = reg.available && reg.registered
        emit(
            DrivingDiagnosticsEvent(
                DrivingDiagnosticsEvent.Type.SENSOR_REGISTRATION, wallClock(),
                listOf("owner" to reg.owner, "sensor" to reg.sensor, "available" to "${reg.available}", "registered" to "${reg.registered}"),
                accepted = ok, reason = if (ok) null else DiagnosticReason.SENSOR_UNAVAILABLE
            )
        )
    }

    /** Every new fix and the verdict of the service's [GpsFixGate] (the state machine applies the same rule). */
    fun onGpsVerdict(data: DrivingData, verdict: GpsFixGate.Verdict) {
        if (!isRunning) return
        val now = clock()
        val s = summary
        val accepted = verdict == GpsFixGate.Verdict.USABLE
        val reason = when (verdict) {
            GpsFixGate.Verdict.USABLE -> null
            GpsFixGate.Verdict.STALE -> DiagnosticReason.GPS_STALE
            GpsFixGate.Verdict.INACCURATE -> DiagnosticReason.LOCATION_INACCURATE
        }
        s.gpsFixes++
        if (accepted) s.gpsAccepted++ else {
            s.gpsRejected++
            s.gpsRejectReasons.inc(reason!!.name)
        }
        if (!data.hasSpeed) s.gpsWithoutSpeed++
        if (!data.speedAccuracyMps.isNaN()) s.gpsWithSpeedAccuracy++
        lastFixAt = now
        s.lastFixAtWall = wallClock()
        s.lastSpeedKmh = data.speedKmh
        s.lastSpeedAccuracyMps = data.speedAccuracyMps
        s.lastAccuracyM = if (data.accuracyM > 0f) data.accuracyM else Float.NaN
        if (data.accuracyM > 0f) {
            if (s.bestAccuracyM.isNaN() || data.accuracyM < s.bestAccuracyM) s.bestAccuracyM = data.accuracyM
            if (s.worstAccuracyM.isNaN() || data.accuracyM > s.worstAccuracyM) s.worstAccuracyM = data.accuracyM
            s.accuracySum += data.accuracyM
            s.accuracyCount++
        }
        if (data.speedKmh > s.maxSpeedKmh) s.maxSpeedKmh = data.speedKmh

        val log = !accepted || verdict != lastVerdict || !data.hasSpeed || now - lastAcceptedLogAt >= ACCEPTED_FIX_LOG_MS
        lastVerdict = verdict
        if (!log) return
        if (accepted) lastAcceptedLogAt = now
        val fields = mutableListOf(
            "fixSeq" to "${data.fixSeq}",
            "age" to "${data.fixAgeMs} ms",
            "speed" to "${data.speedKmh.f1()} km/h",
            "rawSpeed" to "${data.rawSpeedKmh.f1()} km/h",
            "hasSpeed" to "${data.hasSpeed}",
            "speedAccuracy" to if (data.speedAccuracyMps.isNaN()) "n/a" else "${data.speedAccuracyMps.f1()} m/s",
            "horizontalAccuracy" to if (data.accuracyM > 0f) "${data.accuracyM.f1()} m" else "unknown",
            "provider" to data.provider.ifEmpty { "?" }
        )
        // The code does not check hasSpeed: such a fix passes with speed 0.
        if (accepted && !data.hasSpeed) fields += "warning" to DiagnosticReason.SPEED_UNAVAILABLE.name
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.GPS_FIX, wallClock(), fields, accepted, reason))
    }

    /** Every [DrivingData] the service receives: notices the GPS provider going away. */
    fun onDrivingData(data: DrivingData) {
        if (!isRunning) return
        if (lastHasGps && !data.hasGpsFix) {
            summary.gpsLost++
            emit(
                DrivingDiagnosticsEvent(
                    DrivingDiagnosticsEvent.Type.GPS_LOST, wallClock(), listOf("cause" to "provider disabled"),
                    accepted = false, reason = DiagnosticReason.GPS_MISSING
                )
            )
        }
        lastHasGps = data.hasGpsFix
    }

    /** One linear-acceleration sample after [BrakeDetector.onLinearAcceleration] returned [fired]. */
    fun onBrakeSample(detector: BrakeDetector, fired: Boolean) = onBrakeSample(
        detector.lastCheck, detector.lastValue, detector.threshold, detector.speedKmh,
        detector.calibration, detector.learnSamples, fired
    )

    fun onBrakeSample(
        check: BrakeDetector.Check, value: Float, threshold: Float, speedKmh: Float,
        calibration: BrakeDetector.Calibration, learnSamples: Int, fired: Boolean
    ) {
        if (!isRunning) return
        val now = clock()
        noteCalibration(calibration, learnSamples)
        winAccelSamples++
        if (winCheck != check) {
            winCheck = check
            winBrakeValue = Float.NaN
        }
        // Keep the value closest to firing: lowest for LEGACY / AXIS, highest for a JOLT magnitude.
        winBrakeValue = when {
            winBrakeValue.isNaN() -> value
            check == BrakeDetector.Check.JOLT -> maxOf(winBrakeValue, value)
            else -> minOf(winBrakeValue, value)
        }
        if (check == BrakeDetector.Check.AXIS && value < summary.peakBrakeAxis) summary.peakBrakeAxis = value
        brakeTracker.onSample(check, value, threshold, speedKmh, calibration, learnSamples, fired, now)?.let { emitBrake(it) }
        maybeFlushWindow(now)
    }

    /** After [BrakeDetector.onSpeed]: learning progress, and a jolt [confirmed] by GPS as braking. */
    fun onBrakeSpeed(detector: BrakeDetector, confirmed: Boolean) {
        if (!isRunning) return
        noteCalibration(detector.calibration, detector.learnSamples)
        if (confirmed) {
            emitBrake(
                BrakeCandidateTracker.Outcome(
                    true, null, BrakeDetector.Check.JOLT, Float.NaN, detector.threshold, detector.speedKmh,
                    detector.calibration, detector.learnSamples, confirmedByGps = true
                )
            )
        }
    }

    /** One gyroscope sample of SensorDataProvider: filtered yaw rate, lateral acceleration and the turn flag. */
    fun onTurnSample(yawRate: Float, lateral: Float, speedKmh: Float, threshold: Float, turning: Boolean, hasGps: Boolean) {
        if (!isRunning) return
        val now = clock()
        winGyroSamples++
        winLateral = maxOf(winLateral, lateral)
        if (abs(yawRate) > abs(winYaw)) winYaw = yawRate
        winSpeed = speedKmh
        if (lateral > summary.peakLateral) summary.peakLateral = lateral
        if (abs(yawRate) > summary.peakYawRate) summary.peakYawRate = abs(yawRate)
        turnTracker.onSample(yawRate, lateral, speedKmh, threshold, turning, hasGps, now)?.let { o ->
            val s = summary
            s.turnCandidates++
            if (o.accepted) {
                s.turnDetected++
                if (o.direction == TurnDirection.LEFT) s.turnLeft++ else s.turnRight++
            } else {
                s.turnReasons.inc(o.reason!!.name)
            }
            emit(
                DrivingDiagnosticsEvent(
                    DrivingDiagnosticsEvent.Type.TURN_CANDIDATE, wallClock(),
                    listOf(
                        "direction" to o.direction.name,
                        "lateralAcceleration" to "${o.lateral.f1()} m/s2",
                        "threshold" to "${o.threshold.f1()} m/s2",
                        "yawRate" to "${o.yawRate.f2()} rad/s",
                        "speed" to "${o.speedKmh.f1()} km/h",
                        "aboveThreshold" to "${o.aboveMs} ms"
                    ),
                    o.accepted, o.reason
                )
            )
        }
        maybeFlushWindow(now)
    }

    /** One accelerometer sample of SensorDataProvider: phone-Y value and its rapid-acceleration flag. */
    fun onLongitudinalSample(phoneY: Float, threshold: Float, rapid: Boolean) {
        if (!isRunning) return
        accelTracker.onSample(phoneY, threshold, rapid, clock())?.let { o ->
            summary.accelCandidates++
            if (o.accepted) summary.accelDetected++ else summary.accelReasons.inc(o.reason!!.name)
            emit(
                DrivingDiagnosticsEvent(
                    DrivingDiagnosticsEvent.Type.ACCEL_CANDIDATE, wallClock(),
                    listOf(
                        "acceleration" to "${o.acceleration.f1()} m/s2",
                        "threshold" to "${o.threshold.f1()} m/s2",
                        "axis" to "phone Y"
                    ),
                    o.accepted, o.reason
                )
            )
            // DrivingData.isRapidAcceleration is computed, but nothing in the state machine reacts to it.
            if (o.accepted) {
                onAnimationRequest(
                    "HARD_ACCELERATION", RequestResult.REJECTED, DiagnosticReason.ANIMATION_NOT_CONFIGURED,
                    "no reaction to DrivingData.isRapidAcceleration"
                )
            }
        }
    }

    fun onMovement(started: Boolean, speedKmh: Float, parked: Boolean) {
        if (!isRunning) return
        if (started) summary.movementStarts++ else summary.movementStops++
        val fields = mutableListOf("speed" to "${speedKmh.f1()} km/h")
        if (started) fields += "parkedBefore" to "$parked"
        emit(
            DrivingDiagnosticsEvent(
                if (started) DrivingDiagnosticsEvent.Type.MOVEMENT_START else DrivingDiagnosticsEvent.Type.MOVEMENT_STOP,
                wallClock(), fields
            )
        )
    }

    fun onTripEvent(event: TripEvent) {
        if (!isRunning) return
        val type = when (event) {
            TripEvent.NONE -> return
            TripEvent.DEPARTED -> { summary.tripDepartures++; DrivingDiagnosticsEvent.Type.TRIP_DEPARTED }
            TripEvent.ARRIVED -> { summary.tripArrivals++; DrivingDiagnosticsEvent.Type.TRIP_ARRIVED }
        }
        emit(DrivingDiagnosticsEvent(type, wallClock()))
    }

    /**
     * A car reaction was asked for in the state machine. Repeats of the same outcome within [REQUEST_DEDUP_MS]
     * (a blocked reaction retried on every GPS update) are counted but written once.
     */
    fun onAnimationRequest(reaction: String, result: RequestResult, reason: DiagnosticReason?, detail: String? = null) {
        if (!isRunning) return
        val key = listOfNotNull(reaction, result.name, reason?.name).joinToString(" ")
        summary.requests.inc(key)
        val now = clock()
        if (key == lastRequestKey && now - lastRequestAt < REQUEST_DEDUP_MS) return
        lastRequestKey = key
        lastRequestAt = now
        val fields = mutableListOf("state" to reaction, "result" to result.name)
        detail?.let { fields += "detail" to it }
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.ANIMATION_REQUEST, wallClock(), fields, reason = reason))
    }

    /** The state machine changed the character's state; [shown] = the overlay with the character is on screen. */
    fun onStateChanged(newState: CompanionState, previous: CompanionState, shown: Boolean) {
        if (!isRunning) return
        summary.stateChanges.inc(newState.name)
        if (newState in REACTIONS) if (shown) summary.reactionsPlayed++ else summary.reactionsHidden++
        if (newState !in LOGGED_STATES) return
        emit(
            DrivingDiagnosticsEvent(
                DrivingDiagnosticsEvent.Type.STATE_CHANGE, wallClock(),
                listOf("from" to previous.name, "to" to newState.name, "overlay" to if (shown) "shown" else "hidden"),
                reason = if (shown) null else DiagnosticReason.OVERLAY_HIDDEN
            )
        )
    }

    /** CharacterView picked [resName] for [state] (null: no file, the fallback drawable is shown). */
    fun onPlayback(state: CompanionState, resName: String?) {
        // Looping states rotate variants on their own: only one-shots are written.
        if (!isRunning || state.isLooping || state !in LOGGED_STATES) return
        if (resName == null) summary.playbackMissing++
        emit(
            DrivingDiagnosticsEvent(
                DrivingDiagnosticsEvent.Type.PLAYBACK, wallClock(),
                listOf("state" to state.name, "file" to (resName ?: "none")),
                accepted = resName != null,
                reason = if (resName == null) DiagnosticReason.ANIMATION_NOT_CONFIGURED else null
            )
        )
    }

    // --- Internals ---

    private fun emitBrake(o: BrakeCandidateTracker.Outcome) {
        val s = summary
        s.brakeCandidates++
        if (o.accepted) {
            s.brakeDetected++
            if (o.confirmedByGps) s.brakeConfirmedByGps++
        } else {
            s.brakeReasons.inc(o.reason?.name ?: "ALREADY_BRAKING")
        }
        val fields = mutableListOf("check" to o.check.name)
        if (!o.value.isNaN()) {
            fields += (if (o.check == BrakeDetector.Check.JOLT) "jolt" else "acceleration") to "${o.value.f1()} m/s2"
        }
        fields += "threshold" to "${o.threshold.f1()} m/s2"
        fields += "speed" to if (o.speedKmh < 0f) "n/a" else "${o.speedKmh.f1()} km/h"
        fields += "calibration" to calibrationText(o.calibration, o.learnSamples)
        if (o.confirmedByGps) fields += "confirmedBy" to "GPS"
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.BRAKE_CANDIDATE, wallClock(), fields, o.accepted, o.reason))
    }

    private fun noteCalibration(calibration: BrakeDetector.Calibration, samples: Int) {
        val text = calibrationText(calibration, samples)
        if (text == summary.calibration) return
        summary.calibration = text
        summary.learnSamples = samples
        emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.CALIBRATION, wallClock(), listOf("calibration" to text)))
    }

    private fun calibrationText(c: BrakeDetector.Calibration, samples: Int) = when (c) {
        BrakeDetector.Calibration.LEARNING -> "LEARNING ($samples/${BrakeDetector.MIN_LEARN_SAMPLES})"
        else -> c.name
    }

    private fun maybeFlushWindow(now: Long) {
        if (now - windowStart >= STATS_WINDOW_MS) flushWindow()
    }

    private fun flushWindow() {
        val now = clock()
        if (winAccelSamples > 0 || winGyroSamples > 0) {
            val fields = mutableListOf("window" to "${(now - windowStart) / 1000} s")
            if (winAccelSamples > 0) {
                val check = winCheck ?: BrakeDetector.Check.LEGACY
                fields += "brakeCheck" to check.name
                fields += (if (check == BrakeDetector.Check.JOLT) "joltMax" else "longitudinalMin") to "${winBrakeValue.f1()} m/s2"
            } else {
                fields += "linearAcceleration" to "no samples"
            }
            if (winGyroSamples > 0) {
                fields += "lateralMax" to "${winLateral.f1()} m/s2"
                fields += "yawRateMax" to "${winYaw.f2()} rad/s"
                fields += "speed" to "${winSpeed.f1()} km/h"
            } else {
                fields += "gyroscope" to "no samples"
            }
            fields += "calibration" to summary.calibration.ifEmpty { "?" }
            emit(DrivingDiagnosticsEvent(DrivingDiagnosticsEvent.Type.SENSOR_STATS, wallClock(), fields), significant = false)
        }
        windowStart = now
        winAccelSamples = 0
        winGyroSamples = 0
        winCheck = null
        winBrakeValue = Float.NaN
        winLateral = 0f
        winYaw = 0f
    }

    /** Writes [event] (on [io]); [significant] events become the screen's "last event". */
    private fun emit(event: DrivingDiagnosticsEvent, significant: Boolean = true) {
        if (!isRunning) return
        val text = event.format(timeFormat)
        val size = text.toByteArray(Charsets.UTF_8).size
        if (summary.bytes + size > limitBytes - LIMIT_RESERVE_BYTES) {
            reachLimit()
            return
        }
        write(text, size)
        if (significant) {
            summary.lastEvent = event.shortText(timeFormat)
            summary.lastReason = event.reason?.name ?: ""
        }
        if (clock() - lastSummaryAt >= SUMMARY_EVERY_MS) persistSummary()
    }

    private fun write(text: String, size: Int) {
        summary.bytes += size
        summary.events++
        io.execute { storage.appendJournal(text) }
    }

    private fun reachLimit() {
        val event = DrivingDiagnosticsEvent(
            DrivingDiagnosticsEvent.Type.LIMIT_REACHED, wallClock(), listOf("limit" to "${limitBytes / 1024} KB")
        )
        val text = event.format(timeFormat)
        write(text, text.toByteArray(Charsets.UTF_8).size)     // the reserve keeps room for it
        summary.lastEvent = event.shortText(timeFormat)
        summary.lastReason = ""
        summary.status = DiagnosticsStatus.LIMIT_REACHED
        summary.endedAt = wallClock()
        isRunning = false
        persistSummary()
        onLimitReached()
    }

    private fun persistSummary() {
        lastSummaryAt = clock()
        val text = summary.toText()
        io.execute { storage.writeSummary(text) }
    }

    companion object {
        /** Journal size limit of one recording. */
        const val LIMIT_BYTES = 5L * 1024 * 1024

        /** Kept free for the LIMIT_REACHED entry. */
        private const val LIMIT_RESERVE_BYTES = 512L

        const val STATS_WINDOW_MS = 10_000L
        const val ACCEPTED_FIX_LOG_MS = 10_000L
        const val SUMMARY_EVERY_MS = 15_000L
        const val REQUEST_DEDUP_MS = 5_000L

        /** Car reactions: entering one of these with the overlay shown counts as a played reaction. */
        val REACTIONS = setOf(
            CompanionState.ALERT, CompanionState.TURN_LEFT, CompanionState.TURN_RIGHT,
            CompanionState.TRIP_START, CompanionState.ARRIVAL
        )

        /** State changes written to the journal; all others are only counted (blinks would flood it). */
        val LOGGED_STATES = REACTIONS + setOf(
            CompanionState.RELIEF, CompanionState.TRIP_PREPARE, CompanionState.WAKE,
            CompanionState.FALL_ASLEEP, CompanionState.SLEEP
        )
    }
}
