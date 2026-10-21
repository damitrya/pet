package com.damitrya.mimio.overlay

import android.os.SystemClock
import com.damitrya.mimio.data.HapticMode

/**
 * Haptic responses to the user's own touches: a tap, the start of a petting (a recognised long press), and the
 * moment the fox becomes a heart (a petting finale or the farewell, both started by the user). Automatic
 * reactions (braking, turns, GPS, battery, music) never call this, and holding the finger never buzzes on:
 * one response per recognised action.
 *
 * Never closer than [MIN_GAP_MS] to the previous response, so tap spam or one touch reported twice cannot
 * turn into a buzz. [perform] plays the effect in the chosen [HapticMode] ([HapticPlayer] on the device).
 */
class TouchHaptics(
    private val mode: () -> HapticMode,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val perform: (Effect, HapticMode) -> Boolean
) {
    enum class Effect {
        /** A short tap: one very short pulse. */
        TAP,
        /** Petting starts (held 600 ms): two soft pulses. */
        HOLD,
        /** The heart is complete: three short pulses growing stronger. */
        HEART
    }

    private var lastAt = Long.MIN_VALUE / 2

    /** Plays [effect] unless haptics are off in the app settings or the last response is too recent. */
    fun play(effect: Effect): Boolean {
        val m = mode()
        if (m == HapticMode.OFF) return false
        val now = clock()
        if (now - lastAt < MIN_GAP_MS) return false
        lastAt = now
        return perform(effect, m)
    }

    companion object {
        const val MIN_GAP_MS = 120L
    }
}
