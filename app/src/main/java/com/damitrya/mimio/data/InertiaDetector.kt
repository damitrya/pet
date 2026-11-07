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

/**
 * What became of a manoeuvre (diagnostics): accepted at [level], or why not.
 *
 * Wave 8 tells the durations apart (before, [durationMs] was the whole episode including the 400 ms release wait, so
 * a rejection "EVENT_TOO_SHORT duration=2018 ms" looked like a contradiction):
 * - [durationMs]: the event — from the first sample at the threshold to the last one (dips included);
 * - [aboveMs]: the longest run above the threshold, dips up to [CarMotionConfig.dipToleranceMs] included — the value
 *   the [CarMotionConfig.sustainMs] check uses;
 * - [episodeMs]: the event plus the wait below the release level that ended it.
 */
data class InertiaOutcome(
    val kind: InertiaKind,
    val level: InertiaLevel,
    val accepted: Boolean,
    val reason: DiagnosticReason?,
    /** Peak of the value the thresholds are compared with (smoothed), m/s². */
    val peak: Float,
    val durationMs: Long,
    val speedKmh: Float,
    val aboveMs: Long = durationMs,
    /** The threshold of [level] for this direction (m/s²). */
    val threshold: Float = Float.NaN,
    /** Peak of the unsmoothed value (m/s²), NaN when not known. */
    val rawPeak: Float = Float.NaN,
    val episodeMs: Long = durationMs,
    /** How a rejected candidate ended: [END_RELEASED], [END_OPPOSITE] or [END_INTERRUPTED]; null when decided while it lasted. */
    val endedBy: String? = null,
    /** Calibration at the decision (filled in by [CarMotionTracker]). */
    val calibration: CalibrationState? = null,
    val calibrationQuality: Float = Float.NaN,
    val phoneMoving: Boolean = false
) {
    companion object {
        const val END_RELEASED = "RELEASED"
        const val END_OPPOSITE = "OPPOSITE_DIRECTION"
        const val END_INTERRUPTED = "INTERRUPTED"
    }
}

/**
 * One axis of manoeuvres (longitudinal: braking / speeding up, or lateral: left / right turns) with hysteresis:
 *
 * - a manoeuvre starts once the value stays at or above its SOFT threshold for [CarMotionConfig.sustainMs], and
 *   reaches STRONG once it stays above the STRONG threshold that long. Dips below the threshold of at most
 *   [CarMotionConfig.dipToleranceMs] do not break the run (wave 8: the smoothed value of a real braking hovers around
 *   its threshold). A candidate that never held that long is rejected when it ends: [DiagnosticReason.EVENT_TOO_SHORT]
 *   when the whole event was shorter than [CarMotionConfig.sustainMs], [DiagnosticReason.THRESHOLD_NOT_SUSTAINED]
 *   when it lasted longer but kept falling below the threshold;
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

    /** Current run above the soft threshold (0: none), its last sample, and the longest run of the episode. */
    private var aboveSince = 0L
    private var lastAboveAt = 0L
    private var longestAboveMs = 0L

    /** The same for the strong threshold. */
    private var strongSince = 0L
    private var lastStrongAt = 0L

    private var belowSince = 0L
    private var peak = 0f
    private var rawPeak = Float.NaN
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
     * Ends the episode in progress because the data cannot be trusted any more ([reason]: the phone is being moved,
     * the car's axis was lost). A candidate still waiting for its decision is reported with [reason] (it might have
     * been a real manoeuvre: the diagnostics must say why it was not shown); a decided manoeuvre was reported already.
     */
    fun abandon(reason: DiagnosticReason, now: Long, speedKmh: Float, onOutcome: (InertiaOutcome) -> Unit) {
        if (phase == Phase.CANDIDATE || (phase == Phase.ACTIVE && pending)) {
            onOutcome(
                InertiaOutcome(
                    kindOf(sign), if (pending) pendingLevel else InertiaLevel.SOFT, false, reason, peak,
                    eventMs(), speedKmh, longestAboveMs, soft(sign), rawPeak, now - startedAt, InertiaOutcome.END_INTERRUPTED
                )
            )
        }
        reset()
    }

    /**
     * One sample. [gate] decides whether a level that has just been reached may play (null) or not (the reason).
     * [onOutcome] hears every decision, [onEvent] only the allowed ones. [raw]: the unsmoothed value (diagnostics).
     */
    fun onSample(
        value: Float, now: Long, speedKmh: Float,
        gate: (InertiaKind, InertiaLevel) -> DiagnosticReason?,
        onOutcome: (InertiaOutcome) -> Unit,
        onEvent: (CarMotionEvent) -> Unit,
        raw: Float = Float.NaN
    ) {
        val s = if (value >= 0f) 1 else -1
        val a = abs(value)

        // A manoeuvre in the opposite direction ends the current one at once (S-bend, braking after a start).
        if (phase != Phase.IDLE && s != sign && a >= soft(s)) {
            endEpisode(now, speedKmh, onOutcome, InertiaOutcome.END_OPPOSITE)
        }

        when (phase) {
            Phase.IDLE -> {
                if (a >= soft(s)) begin(s, a, raw, now)
                return
            }
            Phase.CANDIDATE -> {
                val v = value * sign
                track(v, raw, now)
                if (v >= soft(sign)) belowSince = 0L
                // Past the strong threshold but not for long enough yet: wait for it rather than play SOFT first
                // (while the strong run lasts; a dip of up to dipToleranceMs keeps it).
                val strongBuilding = strongSince != 0L && !strongHeld(now) && now - lastStrongAt <= config.dipToleranceMs
                if (longestAboveMs >= config.sustainMs && !strongBuilding) {
                    phase = Phase.ACTIVE
                    level = if (strongHeld(now)) InertiaLevel.STRONG else InertiaLevel.SOFT
                    decide(level, now, speedKmh, gate, onOutcome, onEvent)
                } else if (v < soft(sign) && endedBelowRelease(v, now)) {
                    phase = Phase.IDLE
                    onOutcome(rejection(now, speedKmh, InertiaOutcome.END_RELEASED))
                }
            }
            Phase.ACTIVE -> {
                val v = value * sign
                track(v, raw, now)
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
                else if (endedBelowRelease(v, now)) endEpisode(now, speedKmh, onOutcome, InertiaOutcome.END_RELEASED)
            }
        }
    }

    private fun begin(s: Int, a: Float, raw: Float, now: Long) {
        phase = Phase.CANDIDATE
        sign = s
        startedAt = now
        aboveSince = now
        lastAboveAt = now
        longestAboveMs = 0L
        strongSince = 0L
        lastStrongAt = 0L
        belowSince = 0L
        peak = a
        rawPeak = if (raw.isNaN()) Float.NaN else raw * s
        level = InertiaLevel.SOFT
        softAccepted = false
        strongAccepted = false
        strongReported = false
        pending = false
        trackStrong(a, now)
    }

    /** Peaks and the runs above the soft and the strong threshold (a dip up to dipToleranceMs keeps a run going). */
    private fun track(v: Float, raw: Float, now: Long) {
        if (v > peak) peak = v
        if (!raw.isNaN()) {
            val r = raw * sign
            if (rawPeak.isNaN() || r > rawPeak) rawPeak = r
        }
        if (v >= soft(sign)) {
            if (aboveSince == 0L || now - lastAboveAt > config.dipToleranceMs) aboveSince = now
            lastAboveAt = now
            if (now - aboveSince > longestAboveMs) longestAboveMs = now - aboveSince
        }
        trackStrong(v, now)
    }

    /** First to last sample at the threshold. */
    private fun eventMs(): Long = lastAboveAt - startedAt

    /** A candidate that never held its threshold long enough, with the condition that really failed. */
    private fun rejection(now: Long, speedKmh: Float, endedBy: String): InertiaOutcome {
        val reason = if (eventMs() < config.sustainMs) DiagnosticReason.EVENT_TOO_SHORT else DiagnosticReason.THRESHOLD_NOT_SUSTAINED
        return InertiaOutcome(
            kindOf(sign), InertiaLevel.SOFT, false, reason, peak, eventMs(), speedKmh,
            longestAboveMs, soft(sign), rawPeak, now - startedAt, endedBy
        )
    }

    /** Ends the manoeuvre in progress; an unfinished candidate is rejected (see [rejection]), a waiting one as too slow. */
    private fun endEpisode(now: Long, speedKmh: Float, onOutcome: (InertiaOutcome) -> Unit, endedBy: String) {
        if (phase == Phase.CANDIDATE) {
            onOutcome(rejection(now, speedKmh, endedBy))
        } else if (phase == Phase.ACTIVE && pending) {
            onOutcome(
                InertiaOutcome(
                    kindOf(sign), pendingLevel, false, DiagnosticReason.SPEED_BELOW_THRESHOLD, peak, eventMs(), speedKmh,
                    longestAboveMs, threshold(pendingLevel), rawPeak, now - startedAt, endedBy
                )
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
        val above = if (lvl == InertiaLevel.STRONG && strongSince != 0L) lastStrongAt - strongSince else longestAboveMs
        onOutcome(
            InertiaOutcome(
                kind, lvl, ok, reason, peak, eventMs(), speedKmh, above, threshold(lvl), rawPeak, now - startedAt
            )
        )
        if (ok) onEvent(CarMotionEvent(kind, lvl, peak, speedKmh, upgrade = lvl == InertiaLevel.STRONG && softAccepted))
    }

    private fun trackStrong(v: Float, now: Long) {
        if (v >= strong(sign)) {
            if (strongSince == 0L || now - lastStrongAt > config.dipToleranceMs) strongSince = now
            lastStrongAt = now
        } else if (strongSince != 0L && now - lastStrongAt > config.dipToleranceMs) {
            strongSince = 0L
        }
    }

    private fun strongHeld(now: Long) =
        strongSince != 0L && now - lastStrongAt <= config.dipToleranceMs && lastStrongAt - strongSince >= config.sustainMs

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
    private fun threshold(lvl: InertiaLevel) = if (lvl == InertiaLevel.STRONG) strong(sign) else soft(sign)
}
