package com.damitrya.mimio.overlay

import com.damitrya.mimio.data.HapticMode

/**
 * One vibration as VibrationEffect.createWaveform takes it: [timings] alternate off / on (ms, starting with an
 * off segment), [amplitudes] 1…255 for the on segments, 0 for the off ones; [DEFAULT_AMPLITUDE] means "the
 * motor's own strength" (a vibrator without amplitude control).
 */
class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {

    init {
        require(timings.size == amplitudes.size && timings.size % 2 == 0)
    }

    val totalMs: Long get() = timings.sum()

    /** Lengths of the pulses (the on segments). */
    val pulses: List<Long> get() = timings.filterIndexed { i, _ -> i % 2 == 1 }

    /** Strengths of the pulses. */
    val strengths: List<Int> get() = amplitudes.filterIndexed { i, _ -> i % 2 == 1 }

    /**
     * The same rhythm for a vibrator that cannot change its strength: a weaker pulse becomes a shorter one
     * (down to [MIN_SHARE] of its length), so a rising pattern still rises.
     */
    fun withoutAmplitude(): HapticPattern {
        val t = timings.copyOf()
        val a = IntArray(amplitudes.size)
        for (i in t.indices) {
            if (i % 2 == 1) {
                t[i] = maxOf(MIN_PULSE_MS, Math.round(t[i] * (MIN_SHARE + (1 - MIN_SHARE) * amplitudes[i] / 255.0)))
                a[i] = DEFAULT_AMPLITUDE
            }
        }
        return HapticPattern(t, a)
    }

    override fun toString(): String = pulses.zip(strengths).joinToString(" · ") { (ms, a) ->
        if (a == DEFAULT_AMPLITUDE) "$ms мс" else "$ms мс/$a"
    }

    companion object {
        /** VibrationEffect.DEFAULT_AMPLITUDE. */
        const val DEFAULT_AMPLITUDE = -1
        private const val MIN_SHARE = 0.55
        private const val MIN_PULSE_MS = 8L
    }
}

/**
 * What each [TouchHaptics.Effect] feels like:
 *
 * | Effect | Soft | Expressive |
 * |---|---|---|
 * | TAP — a short tap | one 15 ms pulse, light | one 15 ms pulse, firm |
 * | HOLD — petting starts | two 20 ms pulses | two firmer 20 ms pulses |
 * | HEART — the fox is a heart | three short pulses, growing stronger | the same, stronger and longer |
 *
 * Nothing longer than [MAX_TOTAL_MS]: these are touches, not alerts; holding the finger never vibrates on.
 */
object HapticPatterns {

    const val MAX_TOTAL_MS = 400L

    fun of(effect: TouchHaptics.Effect, mode: HapticMode): HapticPattern {
        val expressive = mode == HapticMode.EXPRESSIVE
        return when (effect) {
            TouchHaptics.Effect.TAP ->
                pattern(longArrayOf(0, 15), intArrayOf(0, if (expressive) 150 else 70))
            TouchHaptics.Effect.HOLD -> if (expressive) {
                pattern(longArrayOf(0, 20, 60, 20), intArrayOf(0, 170, 0, 170))
            } else {
                pattern(longArrayOf(0, 20, 70, 20), intArrayOf(0, 80, 0, 80))
            }
            TouchHaptics.Effect.HEART -> if (expressive) {
                pattern(longArrayOf(0, 20, 80, 26, 80, 36), intArrayOf(0, 110, 0, 190, 0, 255))
            } else {
                pattern(longArrayOf(0, 18, 90, 22, 90, 30), intArrayOf(0, 60, 0, 110, 0, 170))
            }
        }
    }

    private fun pattern(timings: LongArray, amplitudes: IntArray) = HapticPattern(timings, amplitudes)
}

/** How a response reaches the user's hand ([HapticPlayer]). */
enum class HapticRoute {
    /** VibrationEffect patterns through VibratorManager (Android 12+) or Vibrator. */
    VIBRATOR,

    /** Fallback: the standard View.performHapticFeedback (no amplitude, no own patterns). */
    VIEW_FEEDBACK,

    /** The system's touch feedback is switched off: nothing is played. */
    SILENT;

    companion object {
        /**
         * [systemTouchFeedback]: the system setting "touch feedback / vibrate on touch" (null if unreadable).
         * The user switched it off → [SILENT]. A vibrator we may use → [VIBRATOR]; no vibrator access
         * (no motor, the permission missing) → [VIEW_FEEDBACK].
         */
        fun choose(hasVibrator: Boolean, hasPermission: Boolean, systemTouchFeedback: Boolean?): HapticRoute = when {
            systemTouchFeedback == false -> SILENT
            hasVibrator && hasPermission -> VIBRATOR
            else -> VIEW_FEEDBACK
        }
    }
}
