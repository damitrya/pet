package com.damitrya.mimio.overlay

import android.os.SystemClock

/**
 * Short haptic ticks for the user's own touches: a tap, a recognised long press, and the moment the fox
 * becomes a heart (a petting finale or the farewell, both started by the user). Automatic reactions
 * (braking, turns, GPS) never call this.
 *
 * One tick per recognised action, and never closer than [MIN_GAP_MS] to the previous one, so tap spam
 * cannot turn into a buzz. [perform] plays the effect through the standard View haptic feedback, which
 * also follows the system "touch feedback" setting.
 */
class TouchHaptics(
    private val enabled: () -> Boolean,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val perform: (Effect) -> Boolean
) {
    enum class Effect {
        /** Light single click: a short tap. */
        TAP,
        /** Soft response when a long press is recognised (petting starts). */
        HOLD,
        /** Very short tick when the heart is complete. */
        HEART
    }

    private var lastAt = Long.MIN_VALUE / 2

    /** Plays [effect] if haptics are on in the app settings and the last tick is not too recent. */
    fun play(effect: Effect): Boolean {
        if (!enabled()) return false
        val now = clock()
        if (now - lastAt < MIN_GAP_MS) return false
        lastAt = now
        return perform(effect)
    }

    companion object {
        const val MIN_GAP_MS = 120L
    }
}
