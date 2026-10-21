package com.damitrya.mimio.state

/** A battery reaction that is due (see [BatteryWatcher]). */
enum class BatteryEvent {
    NONE,

    /** At or below the user's "low battery" threshold, still above [BatteryWatcher.CRITICAL_LEVEL]: LOW_BATTERY. */
    LOW,

    /** At or below [BatteryWatcher.CRITICAL_LEVEL] %: BATTERY_CRITICAL (the fox holds an almost empty battery). */
    CRITICAL,

    /** Just dropped to [BatteryWatcher.EMPTY_LEVEL] %: BATTERY_SHUTDOWN if the user enabled auto-hiding. */
    EMPTY
}

/**
 * Decides when the battery is worth a reaction. Pure logic (no Android), fed by every battery update:
 *
 * - reactions only while discharging; plugging the charger in drops whatever is due;
 * - LOW (the user's threshold, default 15 %) and CRITICAL (fixed [CRITICAL_LEVEL] %) play once, and again only
 *   after [REPEAT_MS] while the battery stays that low — never on every update. A threshold at or below
 *   [CRITICAL_LEVEL] is covered by CRITICAL (one warning per level, not two). Recharged [REARM_MARGIN] above
 *   a threshold, its warning is armed again;
 * - EMPTY needs a real drop to [EMPTY_LEVEL] while running (the previous discharging level was higher):
 *   starting the app or unplugging at 1 % does not hide it again — the user just asked for it;
 * - an unknown level (no battery, a broken broadcast: outside 0…100) is ignored.
 *
 * [update] / [due] say what should play now; the state machine calls [played] once it has really played it,
 * so a busy character keeps the reaction due.
 */
class BatteryWatcher(private val clock: () -> Long) {

    private var level = -1
    private var charging = false
    private var lowAt = NEVER
    private var criticalAt = NEVER
    private var emptyDue = false

    /** Feeds a battery update; returns the reaction due now. */
    fun update(level: Int, charging: Boolean, lowThreshold: Int): BatteryEvent {
        if (level in 0..100) {
            if (level > lowThreshold + REARM_MARGIN) lowAt = NEVER
            if (level > CRITICAL_LEVEL + REARM_MARGIN) criticalAt = NEVER
            if (charging) emptyDue = false
            else if (level <= EMPTY_LEVEL && this.level > EMPTY_LEVEL && !this.charging) emptyDue = true
            this.level = level
        }
        this.charging = charging
        if (charging) emptyDue = false
        return due(lowThreshold)
    }

    /** The reaction due for the last known level (a retry while the character was busy). */
    fun due(lowThreshold: Int): BatteryEvent {
        if (charging || level !in 0..100) return BatteryEvent.NONE
        val now = clock()
        return when {
            emptyDue -> BatteryEvent.EMPTY
            level <= CRITICAL_LEVEL ->
                if (now - criticalAt >= REPEAT_MS) BatteryEvent.CRITICAL else BatteryEvent.NONE
            level <= lowThreshold ->
                if (now - lowAt >= REPEAT_MS) BatteryEvent.LOW else BatteryEvent.NONE
            else -> BatteryEvent.NONE
        }
    }

    /** [event] was played (EMPTY: handled): not due again until [REPEAT_MS] / the next real drop. */
    fun played(event: BatteryEvent) {
        when (event) {
            BatteryEvent.LOW -> lowAt = clock()
            BatteryEvent.CRITICAL -> criticalAt = clock()
            BatteryEvent.EMPTY -> emptyDue = false
            BatteryEvent.NONE -> {}
        }
    }

    companion object {
        /** "Almost empty": the sad fox with the battery. */
        const val CRITICAL_LEVEL = 10

        /** The fox curls into a cloud and Mimio stops (only with the setting on). */
        const val EMPTY_LEVEL = 1

        /** A warning repeats at most this often while the battery stays low. */
        const val REPEAT_MS = 30 * 60_000L

        /** Charged this far above a threshold, its warning is armed again. */
        const val REARM_MARGIN = 5

        private const val NEVER = Long.MIN_VALUE / 2

        /**
         * A sticky restart by the system (no intent) after Mimio stopped itself at 1 %, or while still at 1 % with
         * auto-hiding on, must keep it stopped. [level] < 0: unknown. Starting it by hand is always allowed.
         */
        fun staysStopped(stoppedForLowBattery: Boolean, autoHide: Boolean, level: Int, plugged: Boolean): Boolean =
            stoppedForLowBattery || (autoHide && !plugged && level in 0..EMPTY_LEVEL)
    }
}
