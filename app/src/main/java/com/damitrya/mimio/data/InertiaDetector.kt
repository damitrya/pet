package com.damitrya.mimio.data

import com.damitrya.mimio.diagnostics.DiagnosticReason
import kotlin.math.abs

/** What the car did. */
enum class InertiaKind { TURN_LEFT, TURN_RIGHT, BRAKE, ACCELERATE }

/** How strongly: an ordinary manoeuvre or an intensive one (a sharp turn, hard braking, a fast start). */
enum class InertiaLevel { SOFT, STRONG }

/** A manoeuvre the character may lean with. [upgrade]: the manoeuvre already played its SOFT level. */
data class CarMotionEvent(
    val kind: InertiaKind,
    val level: InertiaLevel,
    /** Peak acceleration so far (m/s², magnitude). */
    val peak: Float,
    val speedKmh: Float,
    val upgrade: Boolean = false
)

/** What became of a manoeuvre (diagnostics): accepted at [level], or why not. */
data class InertiaOutcome(
    val kind: InertiaKind,
    val level: InertiaLevel,
    val accepted: Boolean,
    val reason: DiagnosticReason?,
    val peak: Float,
    val durationMs: Long,
    val speedKmh: Float
)

/**
 * One axis of manoeuvres (longitudinal: braking / speeding up, or lateral: left / right turns) with hysteresis:
 *
 * - a manoeuvre starts once the value stays at or above its SOFT threshold for [CarMotionConfig.sustainMs]
 *   (shorter: [DiagnosticReason.EVENT_TOO_SHORT]), and reaches STRONG once it stays above the STRONG threshold
 *   that long;
 * - it ends only after the value stays below the release level ([CarMotionConfig.releaseFraction] of the soft
 *   threshold) for [CarMotionConfig.endMs], or when a manoeuvre in the opposite direction starts — so a long
 *   bend or a long braking is one manoeuvre and never fires twice;
 * - each level is reported once per manoeuvre, through [gate], which says whether it may play now;
 * - a value that shoots past the STRONG threshold right away is one STRONG manoeuvre, not a SOFT one followed by
 *   a STRONG one a moment later (no double reaction on a quick sharp turn);
 * - a manoeuvre that starts too slowly ([DiagnosticReason.SPEED_BELOW_THRESHOLD]: starting off from a standstill)
 *   is checked again while it lasts, so the fox leans once the car has picked up speed; only when it ends still
 *   too slow is the rejection reported.
 *
 * Positive values are [positive] (speeding up / turning left), negative ones [negative]. Pure logic.
 */
class ManoeuvreChannel(
    private val positive: InertiaKind,
    private val negative: InertiaKind,
    private val softPositive: Float,
    private val strongPositive: Float,
    private val softNegative: Float,
    private val strongNegative: Float,
    private val config: CarMotionConfig
) {
    private enum class Phase { IDLE, CANDIDATE, ACTIVE }

    private var phase = Phase.IDLE
    private var sign = 0
    private var startedAt = 0L
    private var aboveSince = 0L
    private var strongSince = 0L
    private var belowSince = 0L
    private var peak = 0f
    private var level = InertiaLevel.SOFT
    private var softAccepted = false
    private var strongAccepted = false
    private var strongReported = false

    /** The decision waits for the speed (see the class comment); [pendingLevel] is the level reached meanwhile. */
    private var pending = false
    private var pendingLevel = InertiaLevel.SOFT
    private var retryAt = 0L

    /** Kind of the manoeuvre in progress (null: none). */
    val currentKind: InertiaKind? get() = if (phase == Phase.ACTIVE) kindOf(sign) else null

    /** True while a STRONG manoeuvre is in progress, whether it was allowed to play or not. */
    val isStrongDetected: Boolean get() = phase == Phase.ACTIVE && strongReported

    /** True while a STRONG manoeuvre that was allowed to play is in progress. */
    val isStrongActive: Boolean get() = phase == Phase.ACTIVE && strongAccepted

    fun reset() {
        phase = Phase.IDLE
        sign = 0
        strongAccepted = false
        softAccepted = false
        strongReported = false
        pending = false
    }

    /**
     * One sample. [gate] decides whether a level that has just been reached may play (null) or not (the reason).
     * [onOutcome] hears every decision, [onEvent] only the allowed ones.
     */
    fun onSample(
        value: Float, now: Long, speedKmh: Float,
        gate: (InertiaKind, InertiaLevel) -> DiagnosticReason?,
        onOutcome: (InertiaOutcome) -> Unit,
        onEvent: (CarMotionEvent) -> Unit
    ) {
        val s = if (value >= 0f) 1 else -1
        val a = abs(value)

        // A manoeuvre in the opposite direction ends the current one at once (S-bend, braking after a start).
        if (phase != Phase.IDLE && s != sign && a >= soft(s)) {
            endEpisode(now, speedKmh, onOutcome)
        }

        when (phase) {
            Phase.IDLE -> {
                if (a >= soft(s)) begin(s, a, now)
                return
            }
            Phase.CANDIDATE -> {
                val v = value * sign
                if (v > peak) peak = v
                trackStrong(v, now)
                if (v >= soft(sign)) {
                    belowSince = 0L
                    if (aboveSince == 0L) aboveSince = now
                    // Past the strong threshold but not for long enough yet: wait for it rather than play SOFT first.
                    val strongBuilding = strongSince != 0L && !strongHeld(now)
                    if (now - aboveSince >= config.sustainMs && !strongBuilding) {
                        phase = Phase.ACTIVE
                        level = if (strongHeld(now)) InertiaLevel.STRONG else InertiaLevel.SOFT
                        decide(level, now, speedKmh, gate, onOutcome, onEvent)
                    }
                } else {
                    aboveSince = 0L
                    if (endedBelowRelease(v, now)) {
                        phase = Phase.IDLE
                        onOutcome(
                            InertiaOutcome(kindOf(sign), InertiaLevel.SOFT, false, DiagnosticReason.EVENT_TOO_SHORT,
                                peak, now - startedAt, speedKmh)
                        )
                    }
                }
            }
            Phase.ACTIVE -> {
                val v = value * sign
                if (v > peak) peak = v
                trackStrong(v, now)
                if (pending) {
                    if (strongHeld(now)) pendingLevel = InertiaLevel.STRONG
                    if (v >= soft(sign) && now - retryAt >= RETRY_MS) {
                        retryAt = now
                        level = pendingLevel
                        decide(pendingLevel, now, speedKmh, gate, onOutcome, onEvent)
                    }
                } else if (!strongReported && level == InertiaLevel.SOFT && strongHeld(now)) {
                    level = InertiaLevel.STRONG
                    decide(InertiaLevel.STRONG, now, speedKmh, gate, onOutcome, onEvent)
                }
                if (v >= release(sign)) belowSince = 0L
                else if (endedBelowRelease(v, now)) endEpisode(now, speedKmh, onOutcome)
            }
        }
    }

    private fun begin(s: Int, a: Float, now: Long) {
        phase = Phase.CANDIDATE
        sign = s
        startedAt = now
        aboveSince = now
        strongSince = 0L
        belowSince = 0L
        peak = a
        level = InertiaLevel.SOFT
        softAccepted = false
        strongAccepted = false
        strongReported = false
        pending = false
        trackStrong(a, now)
    }

    /** Ends the manoeuvre in progress; an unfinished candidate counts as too short, a waiting one as too slow. */
    private fun endEpisode(now: Long, speedKmh: Float, onOutcome: (InertiaOutcome) -> Unit) {
        if (phase == Phase.CANDIDATE) {
            onOutcome(
                InertiaOutcome(kindOf(sign), InertiaLevel.SOFT, false, DiagnosticReason.EVENT_TOO_SHORT,
                    peak, now - startedAt, speedKmh)
            )
        } else if (phase == Phase.ACTIVE && pending) {
            onOutcome(
                InertiaOutcome(kindOf(sign), pendingLevel, false, DiagnosticReason.SPEED_BELOW_THRESHOLD,
                    peak, now - startedAt, speedKmh)
            )
        }
        phase = Phase.IDLE
        pending = false
        strongAccepted = false
    }

    private fun decide(
        lvl: InertiaLevel, now: Long, speedKmh: Float,
        gate: (InertiaKind, InertiaLevel) -> DiagnosticReason?,
        onOutcome: (InertiaOutcome) -> Unit,
        onEvent: (CarMotionEvent) -> Unit
    ) {
        val kind = kindOf(sign)
        val reason = gate(kind, lvl)
        if (reason == DiagnosticReason.SPEED_BELOW_THRESHOLD) {
            // Starting off: decide again while the manoeuvre lasts (reported only if it ends too slow).
            if (!pending) retryAt = now
            pending = true
            pendingLevel = lvl
            return
        }
        pending = false
        val ok = reason == null
        if (lvl == InertiaLevel.STRONG) {
            strongReported = true
            strongAccepted = ok
        } else {
            softAccepted = ok
        }
        onOutcome(InertiaOutcome(kind, lvl, ok, reason, peak, now - startedAt, speedKmh))
        if (ok) onEvent(CarMotionEvent(kind, lvl, peak, speedKmh, upgrade = lvl == InertiaLevel.STRONG && softAccepted))
    }

    private fun trackStrong(v: Float, now: Long) {
        if (v >= strong(sign)) {
            if (strongSince == 0L) strongSince = now
        } else {
            strongSince = 0L
        }
    }

    private fun strongHeld(now: Long) = strongSince != 0L && now - strongSince >= config.sustainMs

    private fun endedBelowRelease(v: Float, now: Long): Boolean {
        if (v >= release(sign)) {
            belowSince = 0L
            return false
        }
        if (belowSince == 0L) belowSince = now
        return now - belowSince >= config.endMs
    }

    private companion object {
        /** A manoeuvre waiting for the speed is checked again this often. */
        const val RETRY_MS = 200L
    }

    private fun kindOf(s: Int) = if (s > 0) positive else negative
    private fun soft(s: Int) = if (s > 0) softPositive else softNegative
    private fun strong(s: Int) = if (s > 0) strongPositive else strongNegative
    private fun release(s: Int) = config.releaseOf(soft(s))
}
