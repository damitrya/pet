package com.damitrya.mimio.data

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic sensor traces for the car physics: a car (speed, yaw rate) with a phone in some mount, as
 * [SensorDataProvider] would deliver them — linear acceleration, gyroscope and gravity at 50 Hz, a usable GPS fix
 * once a second (the fix's own speed, like the service passes it). Ranges follow the drive of 26 October:
 * 0–83 km/h, ordinary turns 1–2.5 m/s², braking and starting off 1–2.5 m/s², phone picked up several times.
 *
 * Car frame: x right, y forward, z up. [mount] maps car vectors to phone coordinates (v_phone = M · v_car).
 * Everything goes to [tracker] and, if given, to a [BrakeDetector] (the ALERT path) with the same timestamps.
 */
class CarSim(
    val tracker: CarMotionTracker = CarMotionTracker(),
    mount: Array<FloatArray> = Mounts.UPRIGHT,
    val brake: BrakeDetector? = null,
    seed: Int = 7,
    /** Road vibration: amplitude of the linear acceleration (m/s²) and of the pitch/roll rate (rad/s). */
    var vibration: Float = 0.15f,
    var vibrationGyro: Float = 0.02f
) {
    val events = mutableListOf<CarMotionEvent>()
    val sharpTurns = mutableListOf<TurnDirection>()
    val alerts = mutableListOf<Long>()
    val outcomes = mutableListOf<InertiaOutcome>()

    private val m = Array(3) { mount[it].copyOf() }
    private val random = Random(seed)

    var now = 1_000_000L
        private set
    var speedMps = 0f

    /** Hooks for tests that drive a state machine with the same trace. */
    var onEvent: ((CarMotionEvent) -> Unit)? = null
    var onSharpTurn: ((Boolean, TurnDirection) -> Unit)? = null
    var onFix: ((Float) -> Unit)? = null
    var onStep: ((Long) -> Unit)? = null
    var onAlert: (() -> Unit)? = null

    /** GPS: on unless a test switches it off (tunnel). */
    var gps = true
    private var nextFixAt = now + 1000

    init {
        tracker.onOutcome = { outcomes += it }
        tracker.listener = object : CarMotionTracker.Listener {
            override fun onCarMotion(event: CarMotionEvent) {
                events += event
                onEvent?.invoke(event)
            }

            override fun onSharpTurnChanged(turning: Boolean, direction: TurnDirection) {
                if (turning) sharpTurns += direction
                onSharpTurn?.invoke(turning, direction)
            }
        }
    }

    /** Drives [ms] with acceleration [accel] (m/s², along the car) and yaw rate [yaw] (rad/s, + = left). */
    fun drive(ms: Long, accel: Float = 0f, yaw: Float = 0f) = run(ms) { accel to yaw }

    /** Drives at a constant speed (km/h). */
    fun cruise(ms: Long, kmh: Float, yaw: Float = 0f) {
        speedMps = kmh / 3.6f
        drive(ms, 0f, yaw)
    }

    /** A turn of [lateral] m/s² (+ = left) at the current speed, with a ramp in and out of [rampMs]. */
    fun turn(ms: Long, lateral: Float, rampMs: Long = 600L) {
        val start = now
        run(ms) {
            val t = now - start
            val k = minOf(1f, t / rampMs.toFloat(), (ms - t) / rampMs.toFloat()).coerceAtLeast(0f)
            0f to (if (speedMps > 0.5f) lateral * k / speedMps else 0f)
        }
    }

    /** Smooth speed change to [kmh] at [accel] m/s² (sign taken from the direction). */
    fun changeSpeed(kmh: Float, accel: Float) {
        val target = kmh / 3.6f
        val a = if (target > speedMps) kotlin.math.abs(accel) else -kotlin.math.abs(accel)
        val ms = ((target - speedMps) / a * 1000).toLong()
        drive(ms, a)
        speedMps = target
    }

    /**
     * The phone is handled for [ms]: it turns around its own [axis] (phone coordinates) at [rate] rad/s with jerky
     * hand accelerations, while the car keeps doing what [car] says.
     */
    fun handle(ms: Long, axis: FloatArray, rate: Float, car: () -> Pair<Float, Float> = { 0f to 0f }) {
        val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val u = FloatArray(3) { axis[it] / n }
        val start = now
        run(ms, handRate = { t ->
            // the hand starts and stops the rotation smoothly
            val k = sin(Math.PI * (t - start) / ms).toFloat()
            FloatArray(3) { u[it] * rate * k * 1.57f }
        }, handJerk = 2.5f, control = car)
    }

    /**
     * Starting off and slowing down on a straight road teaches the car's axis, as at the start of every drive; the
     * lists of what happened are cleared afterwards.
     */
    fun calibrateOnStraightRoad() {
        drive(2_000L)
        changeSpeed(45f, 1.6f)
        cruise(3_000L, 45f)
        changeSpeed(30f, 1.4f)
        cruise(3_000L, 30f)
        events.clear()
        sharpTurns.clear()
        alerts.clear()
        outcomes.clear()
    }

    /** Picks the phone up, turns it a little and puts it back where it was (same orientation, same axis). */
    fun pickUpAndPutBack() {
        handle(800L, floatArrayOf(1f, 0f, 0f), 1.2f)
        handle(800L, floatArrayOf(1f, 0f, 0f), -1.2f)
        drive(2_000L)
    }

    private fun run(
        ms: Long,
        handRate: ((Long) -> FloatArray)? = null,
        handJerk: Float = 0f,
        control: () -> Pair<Float, Float>
    ) {
        val end = now + ms
        while (now < end) {
            val (accel, yaw) = control()
            step(accel, yaw, handRate?.invoke(now), handJerk)
        }
    }

    private fun step(accel: Float, yaw: Float, hand: FloatArray?, jerk: Float) {
        val dt = DT_MS / 1000f
        speedMps = (speedMps + accel * dt).coerceAtLeast(0f)
        // car frame: forward acceleration, centripetal to the left for a left turn, vertical vibration
        val ac = floatArrayOf(
            -speedMps * yaw + noise(vibration),
            accel + noise(vibration),
            noise(vibration * 3)
        )
        val wc = floatArrayOf(noise(vibrationGyro), noise(vibrationGyro), yaw)
        val a = mul(ac)
        val w = mul(wc)
        if (hand != null) {
            for (i in 0..2) {
                w[i] += hand[i]
                a[i] += noise(jerk)
            }
            rotatePhone(hand, dt)
        }
        val g = mul(floatArrayOf(0f, 0f, G))
        tracker.onGravity(g[0], g[1], g[2], now)
        brake?.onGravity(g[0], g[1], g[2], now)
        tracker.onGyro(w[0], w[1], w[2], now)
        tracker.onLinearAcceleration(a[0], a[1], a[2], now)
        if (brake?.onLinearAcceleration(a[0], a[1], a[2], now) == true) {
            alerts += now
            onAlert?.invoke()
        }
        if (now >= nextFixAt) {
            nextFixAt += 1000
            if (gps) fix()
        }
        onStep?.invoke(now)
        now += DT_MS
    }

    /** A usable GPS fix now. */
    fun fix() {
        val kmh = speedMps * 3.6f
        tracker.onSpeed(kmh, now)
        if (brake?.onSpeed(kmh, now) == true) {
            alerts += now
            onAlert?.invoke()
        }
        onFix?.invoke(kmh)
    }

    /** Turns the mount: phone rotated by [w]·dt (phone coordinates), so car vectors turn the other way. */
    private fun rotatePhone(w: FloatArray, dt: Float) {
        val angle = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]) * dt
        if (angle < 1e-7f) return
        val r = Mounts.rotation(w, -angle)
        val out = Array(3) { FloatArray(3) }
        for (i in 0..2) for (j in 0..2) out[i][j] = (0..2).sumOf { (r[i][it] * m[it][j]).toDouble() }.toFloat()
        for (i in 0..2) out[i].copyInto(m[i])
    }

    private fun mul(v: FloatArray) = FloatArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] }

    private fun noise(amp: Float) = if (amp == 0f) 0f else (random.nextFloat() * 2 - 1) * amp

    companion object {
        const val DT_MS = 20L
        const val G = 9.81f
    }
}

/** Phone mounts: rows are phone axes, columns car axes (x right, y forward, z up). */
object Mounts {
    /** Portrait in a vent holder, screen to the driver: phone x = right, y = up, z = backward. */
    val UPRIGHT = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, -1f, 0f))

    /** Landscape in the holder (rotated 90° in the screen plane): phone x = up, y = left. */
    val LANDSCAPE = arrayOf(floatArrayOf(0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f))

    /** Flat on the console, top forward. */
    val FLAT = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))

    /** Upright, but turned sideways in the holder: the screen looks to the left of the car, the top up. */
    val SIDEWAYS = arrayOf(floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(-1f, 0f, 0f))

    /** Upright and tilted back by [deg] (dashboard holder leaning back). */
    fun tilted(base: Array<FloatArray>, deg: Double): Array<FloatArray> {
        val r = rotation(floatArrayOf(1f, 0f, 0f), Math.toRadians(deg).toFloat())
        return Array(3) { i -> FloatArray(3) { j -> (0..2).sumOf { (r[i][it] * base[it][j]).toDouble() }.toFloat() } }
    }

    /** Rotation matrix by [angle] around the unit-ish [axis]. */
    fun rotation(axis: FloatArray, angle: Float): Array<FloatArray> {
        val n = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val x = axis[0] / n; val y = axis[1] / n; val z = axis[2] / n
        val c = cos(angle); val s = sin(angle); val t = 1 - c
        return arrayOf(
            floatArrayOf(t * x * x + c, t * x * y - s * z, t * x * z + s * y),
            floatArrayOf(t * x * y + s * z, t * y * y + c, t * y * z - s * x),
            floatArrayOf(t * x * z - s * y, t * y * z + s * x, t * z * z + c)
        )
    }
}
