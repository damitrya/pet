package com.damitrya.mimio.data

import com.damitrya.mimio.diagnostics.DiagnosticReason
import com.damitrya.mimio.diagnostics.DrivingDiagnostics
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The car physics of the AUTO profile (wave 7): turns the samples of the sensors that [SensorDataProvider] already
 * registers (linear acceleration, gyroscope, gravity) and the usable GPS speeds into manoeuvres the character
 * leans with.
 *
 * - [frame] knows the car's axes in phone coordinates ([VehicleFrameEstimator]): longitudinal acceleration is
 *   the horizontal acceleration along the learned forward axis, smoothed over [CarMotionConfig.longitudinalTauMs].
 * - Turns need no calibration: the lateral acceleration is the GPS speed × the yaw rate (the rotation around the
 *   gravity vector), positive to the left — whatever the mount.
 * - [phone] ([PhoneMotionDetector]) pauses everything while the phone is probably being handled; the frame is
 *   suspended, not erased.
 * - Two [ManoeuvreChannel]s give one reaction per manoeuvre, with hysteresis; [gate] checks the speed, the GPS,
 *   the calibration and the phone at the moment a level is reached, and names the reason when it may not play.
 *
 * Emergency braking (ALERT) stays with [BrakeDetector], which uses the same [frame].
 *
 * Main thread; no allocations per sample beyond the diagnostics' own; times in ms of one monotonic clock.
 */
class CarMotionTracker(
    val config: CarMotionConfig = CarMotionConfig.DEFAULT,
    val frame: VehicleFrameEstimator = VehicleFrameEstimator(),
    val phone: PhoneMotionDetector = PhoneMotionDetector()
) {

    interface Listener {
        /** An ordinary or intensive manoeuvre that may play (SOFT turns, braking and starting off of any level). */
        fun onCarMotion(event: CarMotionEvent)

        /** A sharp turn (STRONG lateral manoeuvre) started or ended: the existing TURN_LEFT/RIGHT reaction. */
        fun onSharpTurnChanged(turning: Boolean, direction: TurnDirection)
    }

    var listener: Listener? = null

    /** Every manoeuvre decision (tests; the diagnostics get them through their sink). */
    internal var onOutcome: ((InertiaOutcome) -> Unit)? = null

    private val longitudinal = ManoeuvreChannel(
        InertiaKind.ACCELERATE, InertiaKind.BRAKE,
        config.softAccel, config.strongAccel, config.softBrake, config.strongBrake, config
    )
    private val lateral = ManoeuvreChannel(
        InertiaKind.TURN_LEFT, InertiaKind.TURN_RIGHT,
        config.softTurn, config.strongTurn, config.softTurn, config.strongTurn, config
    )

    private var lastSpeedKmh = -1f
    private var lastSpeedAt = 0L
    private var now = 0L

    private var yawRate = 0f
    private var lastGyroAt = 0L
    private var longSmooth = 0f
    private var latSmooth = Float.NaN
    private var lastLinearAt = 0L
    /**
     * Rotation around the vertical (rad) while the phone is handled: a swivel gravity cannot see. It starts from
     * [recentYaw], the leaky sum of the last second or so, because handling is only recognised a moment after it began.
     */
    private var handledYaw = 0f
    private var recentYaw = 0f
    private var sharpTurn = false
    private var sharpDirection = TurnDirection.NONE

    // Callbacks of the channels, made once (no allocation per sensor sample).
    private val gateFn: (InertiaKind, InertiaLevel) -> DiagnosticReason? = ::gate
    private val outcomeFn: (InertiaOutcome) -> Unit = ::outcome
    private val eventFn: (CarMotionEvent) -> Unit = ::event

    private val h = FloatArray(3)
    private val axis = FloatArray(3)
    private val left = FloatArray(3)

    /** Last values (diagnostics and tests). */
    var lastRawLongitudinal = Float.NaN
        private set
    var lastLongitudinal = Float.NaN
        private set
    var lastLateralAccel = Float.NaN
        private set
    var lastKinematicLateral = 0f
        private set
    val filteredYawRate: Float get() = yawRate

    init {
        frame.onTransition = { from, to, reason ->
            DrivingDiagnostics.sink?.onCalibrationChange(from, to, reason, frame.quality, frame.lessons)
        }
    }

    /** Profile switch or service start: everything starts over (the calibration too: maybe another car). */
    fun reset() {
        frame.reset()
        phone.reset()
        longitudinal.reset()
        lateral.reset()
        lastSpeedKmh = -1f
        lastSpeedAt = 0L
        yawRate = 0f
        lastGyroAt = 0L
        longSmooth = 0f
        latSmooth = Float.NaN
        lastLinearAt = 0L
        handledYaw = 0f
        recentYaw = 0f
        setSharpTurn(false, TurnDirection.NONE)
    }

    /** A new usable GPS fix (same rule as everywhere: [GpsFixGate]). */
    fun onSpeed(kmh: Float, at: Long) {
        now = maxOf(now, at)
        lastSpeedKmh = kmh
        lastSpeedAt = at
        frame.onSpeed(kmh, at)
    }

    /** The GPS provider went away: speeds are unknown until the next fix. */
    fun onGpsLost() {
        lastSpeedAt = 0L
        lastSpeedKmh = -1f
        frame.onGpsGap()
    }

    fun onGravity(x: Float, y: Float, z: Float, at: Long) {
        now = maxOf(now, at)
        frame.onGravity(x, y, z)
        if (frame.hasGravity && phone.onGravity(frame.up(), at)) onPhoneMotionChanged()
    }

    fun onGyro(x: Float, y: Float, z: Float, at: Long) {
        now = maxOf(now, at)
        val up = if (frame.hasGravity) frame.up() else null
        val rawYaw = if (up != null) x * up[0] + y * up[1] + z * up[2] else z
        val dt = if (lastGyroAt == 0L) 0L else at - lastGyroAt
        lastGyroAt = at
        yawRate += (rawYaw - yawRate) * smoothing(dt, config.yawTauMs)
        if (dt in 1L..200L) {
            recentYaw = (recentYaw + rawYaw * dt / 1000f) * exp(-dt / RECENT_YAW_MS)
            if (phone.isMoving) handledYaw += rawYaw * dt / 1000f
        }
        frame.onYaw(yawRate)
        if (phone.onGyro(x, y, z, up, freshSpeed(), at)) onPhoneMotionChanged()

        val speed = if (lastSpeedKmh >= 0f) lastSpeedKmh else 0f
        lastKinematicLateral = yawRate * speed / 3.6f
        lateral.onSample(lastKinematicLateral, at, speed, gateFn, outcomeFn, eventFn)
        val strong = lateral.isStrongActive
        val dir = when (lateral.currentKind) {
            InertiaKind.TURN_LEFT -> TurnDirection.LEFT
            InertiaKind.TURN_RIGHT -> TurnDirection.RIGHT
            else -> TurnDirection.NONE
        }
        setSharpTurn(strong, if (strong) dir else TurnDirection.NONE)
        DrivingDiagnostics.sink?.onTurnSample(
            yawRate, kotlin.math.abs(lastKinematicLateral), speed, config.strongTurn, lateral.isStrongDetected, lastSpeedAt != 0L
        )
    }

    fun onLinearAcceleration(x: Float, y: Float, z: Float, at: Long) {
        now = maxOf(now, at)
        phone.onLinear(sqrt(x * x + y * y + z * z))
        frame.horizontal(x, y, z, h)
        frame.onSample(h, yawRate)
        val dt = if (lastLinearAt == 0L) 0L else at - lastLinearAt
        lastLinearAt = at
        if (!frame.axis(axis)) {
            lastRawLongitudinal = Float.NaN
            lastLongitudinal = Float.NaN
            lastLateralAccel = Float.NaN
            longSmooth = 0f
            latSmooth = Float.NaN
            longitudinal.reset()          // no axis, no longitudinal manoeuvre (it starts afresh with the next axis)
            DrivingDiagnostics.sink?.onMotionSample(this, sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]))
            return
        }
        val raw = h[0] * axis[0] + h[1] * axis[1] + h[2] * axis[2]
        longSmooth += (raw - longSmooth) * smoothing(dt, config.longitudinalTauMs)
        // left = up × forward: positive lateral acceleration points into a left turn.
        val up = frame.up()
        left[0] = up[1] * axis[2] - up[2] * axis[1]
        left[1] = up[2] * axis[0] - up[0] * axis[2]
        left[2] = up[0] * axis[1] - up[1] * axis[0]
        lastRawLongitudinal = raw
        lastLongitudinal = longSmooth
        lastLateralAccel = h[0] * left[0] + h[1] * left[1] + h[2] * left[2]
        latSmooth = if (latSmooth.isNaN()) lastLateralAccel
        else latSmooth + (lastLateralAccel - latSmooth) * smoothing(dt, config.longitudinalTauMs)
        val speed = if (lastSpeedKmh >= 0f) lastSpeedKmh else 0f
        longitudinal.onSample(longSmooth, at, speed, gateFn, outcomeFn, eventFn)
        DrivingDiagnostics.sink?.onMotionSample(this, sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]))
        DrivingDiagnostics.sink?.onLongitudinalSample(longSmooth, config.strongAccel, isStrongAcceleration)
    }

    /** An intensive start is in progress (detected; whether it may play is in the INERTIA_CANDIDATE outcome). */
    val isStrongAcceleration: Boolean
        get() = longitudinal.isStrongDetected && longitudinal.currentKind == InertiaKind.ACCELERATE

    /** Fresh GPS speed, or -1 when there is none. */
    fun freshSpeed(): Float =
        if (lastSpeedAt != 0L && now - lastSpeedAt <= config.speedFreshMs) lastSpeedKmh else -1f

    /** Whether a manoeuvre of [kind] may play now; the reason when not. */
    internal fun gate(kind: InertiaKind, level: InertiaLevel): DiagnosticReason? {
        if (phone.isMoving || frame.isPhoneMoving) return DiagnosticReason.PHONE_MOVING
        if (lastSpeedAt == 0L) return DiagnosticReason.GPS_MISSING
        if (now - lastSpeedAt > config.speedFreshMs) return DiagnosticReason.GPS_STALE
        if (lastSpeedKmh < config.minSpeedKmh) return DiagnosticReason.SPEED_BELOW_THRESHOLD
        if (kind == InertiaKind.TURN_LEFT || kind == InertiaKind.TURN_RIGHT) {
            // The car turns around the vertical only, and no faster than its speed allows: a turn with the phone
            // tilting, or quicker than any car, is the hand (decided now, before the phone detector is sure).
            if (kotlin.math.abs(yawRate) > PhoneMotionDetector.plausibleYaw(lastSpeedKmh) ||
                phone.tilt >= PhoneMotionDetector.TURN_TILT_RAD
            ) return DiagnosticReason.PHONE_MOVING
            // With a trusted axis the accelerometer must feel the turn too (centripetal acceleration into the
            // bend): a phone turned around the vertical in the hand has the yaw rate, but not the acceleration.
            if (frame.isReady && !latSmooth.isNaN()) {
                val felt = latSmooth * kotlin.math.sign(lastKinematicLateral)
                if (felt < kotlin.math.abs(lastKinematicLateral) * MIN_FELT_SHARE) return DiagnosticReason.SENSOR_DATA_INCONSISTENT
            }
            return null
        }
        if (kind == InertiaKind.BRAKE || kind == InertiaKind.ACCELERATE) {
            return when (frame.state) {
                CalibrationState.READY -> null
                CalibrationState.SUSPENDED ->
                    if (frame.suspendReason == CalibrationReason.PHONE_MOVING) DiagnosticReason.PHONE_MOVING
                    else DiagnosticReason.SENSOR_DATA_INCONSISTENT
                else -> DiagnosticReason.CALIBRATION_UNRELIABLE
            }
        }
        return null
    }

    private fun outcome(o: InertiaOutcome) {
        onOutcome?.invoke(o)
        DrivingDiagnostics.sink?.onInertiaCandidate(o)
    }

    private fun event(e: CarMotionEvent) {
        // A sharp turn plays the existing TURN_LEFT/RIGHT through onSharpTurnChanged, not a lean.
        if (e.level == InertiaLevel.STRONG && (e.kind == InertiaKind.TURN_LEFT || e.kind == InertiaKind.TURN_RIGHT)) return
        listener?.onCarMotion(e)
    }

    private fun onPhoneMotionChanged() {
        if (phone.isMoving) {
            handledYaw = recentYaw
            frame.suspendForPhoneMotion()
            // Whatever was building up is the hand, not the car.
            longitudinal.reset()
            lateral.reset()
            setSharpTurn(false, TurnDirection.NONE)
        } else {
            // Turned around the vertical while in the hand (or the car turned meanwhile): learn the axis anew.
            frame.onPhoneStable(turnedAroundVertical = kotlin.math.abs(handledYaw) > HANDLED_YAW_RAD)
        }
        DrivingDiagnostics.sink?.onPhoneMotion(phone.isMoving, phone.cause)
    }

    private fun setSharpTurn(turning: Boolean, direction: TurnDirection) {
        if (turning == sharpTurn && direction == sharpDirection) return
        sharpTurn = turning
        sharpDirection = direction
        listener?.onSharpTurnChanged(turning, direction)
    }

    /** Exponential smoothing factor for a step of [dtMs] with time constant [tauMs]. */
    private fun smoothing(dtMs: Long, tauMs: Long): Float =
        if (dtMs <= 0L) FIRST_STEP else 1 - exp(-dtMs.toFloat() / tauMs)

    private companion object {
        /** Share of the turn's lateral acceleration the accelerometer must feel (axis errors, GPS lag allowed for). */
        const val MIN_FELT_SHARE = 0.35f

        /** More rotation around the vertical than this (≈ 20°) while the phone was handled drops the axis. */
        val HANDLED_YAW_RAD = Math.toRadians(20.0).toFloat()
        const val RECENT_YAW_MS = 1_500f

        /** Smoothing step without a usable time difference (the first sample, a repeated timestamp). */
        const val FIRST_STEP = 0.2f
    }
}
