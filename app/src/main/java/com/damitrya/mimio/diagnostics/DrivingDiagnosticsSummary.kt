package com.damitrya.mimio.diagnostics

import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/** Where the recording is. */
enum class DiagnosticsStatus {
    /** Nothing recorded yet (or cleared). */
    NONE,

    RECORDING,

    /** Switched on, but the companion service is not running: recording continues when it starts. */
    WAITING,

    /** Stopped by the user; the report stays until cleared or a new recording starts. */
    STOPPED,

    /** The journal reached its size limit; recording stopped by itself. */
    LIMIT_REACHED
}

/**
 * Aggregated statistics of one recording. Counters only — no coordinates, no addresses, no routes.
 * Persisted as [toText] / [fromText] (java.util.Properties) next to the journal.
 */
class DrivingDiagnosticsSummary {

    var status = DiagnosticsStatus.NONE
    var startedAt = 0L          // wall clock, ms
    var endedAt = 0L
    var profile = ""
    val profiles = linkedSetOf<String>()

    /** "SensorDataProvider/GYROSCOPE" → registered; availability per sensor type. */
    val registrations = linkedMapOf<String, Boolean>()
    val sensorAvailable = linkedMapOf<String, Boolean>()

    // GPS
    var gpsFixes = 0L
    var gpsAccepted = 0L
    var gpsRejected = 0L
    val gpsRejectReasons = linkedMapOf<String, Long>()
    var gpsWithoutSpeed = 0L
    var gpsWithSpeedAccuracy = 0L
    var gpsLost = 0L
    var lastFixAtWall = 0L
    var lastSpeedKmh = Float.NaN
    var lastSpeedAccuracyMps = Float.NaN
    var lastAccuracyM = Float.NaN
    var bestAccuracyM = Float.NaN
    var worstAccuracyM = Float.NaN
    var accuracySum = 0.0
    var accuracyCount = 0L
    var maxSpeedKmh = 0f

    // Movement recognition
    var movementStarts = 0L
    var movementStops = 0L
    var tripDepartures = 0L
    var tripArrivals = 0L
    var brakeCandidates = 0L
    var brakeDetected = 0L
    var brakeConfirmedByGps = 0L
    val brakeReasons = linkedMapOf<String, Long>()
    var turnCandidates = 0L
    var turnDetected = 0L
    var turnLeft = 0L
    var turnRight = 0L
    val turnReasons = linkedMapOf<String, Long>()
    var accelCandidates = 0L
    var accelDetected = 0L
    val accelReasons = linkedMapOf<String, Long>()
    var calibration = ""
    var learnSamples = 0
    var peakBrakeAxis = 0f       // most negative acceleration along the learned axis, m/s²
    var peakLateral = 0f         // m/s²
    var peakYawRate = 0f         // rad/s, absolute

    // Car physics (wave 7)
    /** State of the car's forward axis ([com.damitrya.mimio.data.CalibrationState]) and its last reason. */
    var vehicleCalibration = ""
    var vehicleCalibrationReason = ""
    var vehicleQuality = Float.NaN
    /** "READY -> SUSPENDED PHONE_MOVING" → count. */
    val calibrationTransitions = linkedMapOf<String, Long>()
    var phoneMovingEpisodes = 0L
    /** Manoeuvres by "BRAKE SOFT": seen (accepted or not), accepted, and "BRAKE PHONE_MOVING" rejections. */
    val inertiaCandidates = linkedMapOf<String, Long>()
    val inertiaAccepted = linkedMapOf<String, Long>()
    val inertiaReasons = linkedMapOf<String, Long>()
    /** Leans that started on screen, by file ("inertia_right") → count. */
    val inertiaPlayed = linkedMapOf<String, Long>()
    var peakLongAccel = 0f       // smoothed, along the car's axis, m/s²
    var peakLongDecel = 0f       // most negative smoothed value, m/s²
    var peakLateralAccel = 0f    // accelerometer, across the car's axis, absolute

    // Animations
    /** "ALERT STARTED" / "TURN_LEFT REJECTED COOLDOWN_ACTIVE" → count. */
    val requests = linkedMapOf<String, Long>()
    var reactionsPlayed = 0L
    var reactionsHidden = 0L
    val stateChanges = linkedMapOf<String, Long>()
    var playbackMissing = 0L

    // Journal
    var lastEvent = ""
    var lastReason = ""
    var events = 0L
    var bytes = 0L

    fun copy(): DrivingDiagnosticsSummary = fromText(toText())

    fun toText(): String {
        val p = Properties()
        fun put(k: String, v: Any) = p.setProperty(k, v.toString())
        fun putMap(prefix: String, m: Map<String, *>) = m.forEach { (k, v) -> put("$prefix.$k", v!!) }
        put("status", status.name)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("profile", profile)
        put("profiles", profiles.joinToString(","))
        putMap("registration", registrations)
        putMap("sensor", sensorAvailable)
        put("gpsFixes", gpsFixes)
        put("gpsAccepted", gpsAccepted)
        put("gpsRejected", gpsRejected)
        putMap("gpsReject", gpsRejectReasons)
        put("gpsWithoutSpeed", gpsWithoutSpeed)
        put("gpsWithSpeedAccuracy", gpsWithSpeedAccuracy)
        put("gpsLost", gpsLost)
        put("lastFixAtWall", lastFixAtWall)
        put("lastSpeedKmh", lastSpeedKmh)
        put("lastSpeedAccuracyMps", lastSpeedAccuracyMps)
        put("lastAccuracyM", lastAccuracyM)
        put("bestAccuracyM", bestAccuracyM)
        put("worstAccuracyM", worstAccuracyM)
        put("accuracySum", accuracySum)
        put("accuracyCount", accuracyCount)
        put("maxSpeedKmh", maxSpeedKmh)
        put("movementStarts", movementStarts)
        put("movementStops", movementStops)
        put("tripDepartures", tripDepartures)
        put("tripArrivals", tripArrivals)
        put("brakeCandidates", brakeCandidates)
        put("brakeDetected", brakeDetected)
        put("brakeConfirmedByGps", brakeConfirmedByGps)
        putMap("brakeReason", brakeReasons)
        put("turnCandidates", turnCandidates)
        put("turnDetected", turnDetected)
        put("turnLeft", turnLeft)
        put("turnRight", turnRight)
        putMap("turnReason", turnReasons)
        put("accelCandidates", accelCandidates)
        put("accelDetected", accelDetected)
        putMap("accelReason", accelReasons)
        put("calibration", calibration)
        put("learnSamples", learnSamples)
        put("peakBrakeAxis", peakBrakeAxis)
        put("peakLateral", peakLateral)
        put("peakYawRate", peakYawRate)
        put("vehicleCalibration", vehicleCalibration)
        put("vehicleCalibrationReason", vehicleCalibrationReason)
        put("vehicleQuality", vehicleQuality)
        putMap("calibrationTransition", calibrationTransitions)
        put("phoneMovingEpisodes", phoneMovingEpisodes)
        putMap("inertiaCandidate", inertiaCandidates)
        putMap("inertiaAccepted", inertiaAccepted)
        putMap("inertiaReason", inertiaReasons)
        putMap("inertiaPlayed", inertiaPlayed)
        put("peakLongAccel", peakLongAccel)
        put("peakLongDecel", peakLongDecel)
        put("peakLateralAccel", peakLateralAccel)
        putMap("request", requests)
        put("reactionsPlayed", reactionsPlayed)
        put("reactionsHidden", reactionsHidden)
        putMap("state", stateChanges)
        put("playbackMissing", playbackMissing)
        put("lastEvent", lastEvent)
        put("lastReason", lastReason)
        put("events", events)
        put("bytes", bytes)
        val w = StringWriter()
        p.store(w, null)
        return w.toString()
    }

    companion object {
        fun fromText(text: String): DrivingDiagnosticsSummary {
            val p = Properties()
            p.load(StringReader(text))
            val s = DrivingDiagnosticsSummary()
            fun str(k: String) = p.getProperty(k) ?: ""
            fun long(k: String) = p.getProperty(k)?.toLongOrNull() ?: 0L
            fun float(k: String, def: Float = 0f) = p.getProperty(k)?.toFloatOrNull() ?: def
            fun <T> map(prefix: String, into: MutableMap<String, T>, parse: (String) -> T?) {
                p.stringPropertyNames().filter { it.startsWith("$prefix.") }.sorted().forEach { key ->
                    parse(p.getProperty(key))?.let { into[key.removePrefix("$prefix.")] = it }
                }
            }
            s.status = DiagnosticsStatus.values().firstOrNull { it.name == str("status") } ?: DiagnosticsStatus.NONE
            s.startedAt = long("startedAt")
            s.endedAt = long("endedAt")
            s.profile = str("profile")
            str("profiles").split(',').filter { it.isNotEmpty() }.forEach { s.profiles += it }
            map("registration", s.registrations) { it.toBooleanStrictOrNull() }
            map("sensor", s.sensorAvailable) { it.toBooleanStrictOrNull() }
            s.gpsFixes = long("gpsFixes")
            s.gpsAccepted = long("gpsAccepted")
            s.gpsRejected = long("gpsRejected")
            map("gpsReject", s.gpsRejectReasons) { it.toLongOrNull() }
            s.gpsWithoutSpeed = long("gpsWithoutSpeed")
            s.gpsWithSpeedAccuracy = long("gpsWithSpeedAccuracy")
            s.gpsLost = long("gpsLost")
            s.lastFixAtWall = long("lastFixAtWall")
            s.lastSpeedKmh = float("lastSpeedKmh", Float.NaN)
            s.lastSpeedAccuracyMps = float("lastSpeedAccuracyMps", Float.NaN)
            s.lastAccuracyM = float("lastAccuracyM", Float.NaN)
            s.bestAccuracyM = float("bestAccuracyM", Float.NaN)
            s.worstAccuracyM = float("worstAccuracyM", Float.NaN)
            s.accuracySum = p.getProperty("accuracySum")?.toDoubleOrNull() ?: 0.0
            s.accuracyCount = long("accuracyCount")
            s.maxSpeedKmh = float("maxSpeedKmh")
            s.movementStarts = long("movementStarts")
            s.movementStops = long("movementStops")
            s.tripDepartures = long("tripDepartures")
            s.tripArrivals = long("tripArrivals")
            s.brakeCandidates = long("brakeCandidates")
            s.brakeDetected = long("brakeDetected")
            s.brakeConfirmedByGps = long("brakeConfirmedByGps")
            map("brakeReason", s.brakeReasons) { it.toLongOrNull() }
            s.turnCandidates = long("turnCandidates")
            s.turnDetected = long("turnDetected")
            s.turnLeft = long("turnLeft")
            s.turnRight = long("turnRight")
            map("turnReason", s.turnReasons) { it.toLongOrNull() }
            s.accelCandidates = long("accelCandidates")
            s.accelDetected = long("accelDetected")
            map("accelReason", s.accelReasons) { it.toLongOrNull() }
            s.calibration = str("calibration")
            s.learnSamples = long("learnSamples").toInt()
            s.peakBrakeAxis = float("peakBrakeAxis")
            s.peakLateral = float("peakLateral")
            s.peakYawRate = float("peakYawRate")
            s.vehicleCalibration = str("vehicleCalibration")
            s.vehicleCalibrationReason = str("vehicleCalibrationReason")
            s.vehicleQuality = float("vehicleQuality", Float.NaN)
            map("calibrationTransition", s.calibrationTransitions) { it.toLongOrNull() }
            s.phoneMovingEpisodes = long("phoneMovingEpisodes")
            map("inertiaCandidate", s.inertiaCandidates) { it.toLongOrNull() }
            map("inertiaAccepted", s.inertiaAccepted) { it.toLongOrNull() }
            map("inertiaReason", s.inertiaReasons) { it.toLongOrNull() }
            map("inertiaPlayed", s.inertiaPlayed) { it.toLongOrNull() }
            s.peakLongAccel = float("peakLongAccel")
            s.peakLongDecel = float("peakLongDecel")
            s.peakLateralAccel = float("peakLateralAccel")
            map("request", s.requests) { it.toLongOrNull() }
            s.reactionsPlayed = long("reactionsPlayed")
            s.reactionsHidden = long("reactionsHidden")
            map("state", s.stateChanges) { it.toLongOrNull() }
            s.playbackMissing = long("playbackMissing")
            s.lastEvent = str("lastEvent")
            s.lastReason = str("lastReason")
            s.events = long("events")
            s.bytes = long("bytes")
            return s
        }
    }
}

internal fun MutableMap<String, Long>.inc(key: String) {
    this[key] = (this[key] ?: 0L) + 1
}
