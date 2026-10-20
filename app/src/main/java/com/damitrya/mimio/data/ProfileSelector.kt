package com.damitrya.mimio.data

/**
 * Decides which profile should be active, from the mode chosen in settings, the car's Bluetooth
 * and a temporary manual switch (notification button).
 *
 * - "manual_auto" / "manual_daily": that profile, always.
 * - "auto_bluetooth": AUTO while the car is connected. Leaving the car switches back only after
 *   [LEAVE_DELAY_MS] (a stop at a petrol station or a short Bluetooth drop does not count).
 *   A manual switch overrides this until the next car connect or disconnect (a manual DAILY
 *   stays when the car disconnects, so it does not flip back to AUTO for the leave delay).
 *
 * Pure logic; CompanionService calls [resolve] again after [msUntilChange] when a leave is pending.
 */
class ProfileSelector(private val clock: () -> Long = System::currentTimeMillis) {

    private var carConnected = false

    /** When the car disconnected (null = no pending leave). */
    private var leftAt: Long? = null

    /** Temporary manual choice in "auto_bluetooth" mode; cleared by the next car event (see [onCarBluetooth]). */
    var override: AppProfile? = null

    fun onCarBluetooth(connected: Boolean) {
        // "Out of the car" chosen by hand survives the car switching off; anything else is reset.
        if (connected || override != AppProfile.DAILY) override = null
        if (connected) {
            carConnected = true
            leftAt = null
        } else if (carConnected && leftAt == null) {
            leftAt = clock()
        }
    }

    /** Forget the car connection (e.g. another car device was chosen); a fresh check follows. */
    fun forgetCar() {
        carConnected = false
        leftAt = null
    }

    fun resolve(mode: String): AppProfile {
        val left = leftAt
        if (left != null && clock() - left >= LEAVE_DELAY_MS) {
            carConnected = false
            leftAt = null
        }
        return when (mode) {
            MODE_MANUAL_AUTO -> AppProfile.AUTO
            MODE_MANUAL_DAILY -> AppProfile.DAILY
            else -> override ?: if (carConnected) AppProfile.AUTO else AppProfile.DAILY
        }
    }

    /** Time until a pending leave takes effect, or null if nothing is pending. */
    fun msUntilChange(): Long? =
        leftAt?.let { (LEAVE_DELAY_MS - (clock() - it)).coerceAtLeast(0L) }

    companion object {
        const val MODE_MANUAL_AUTO = "manual_auto"
        const val MODE_MANUAL_DAILY = "manual_daily"
        const val MODE_AUTO_BLUETOOTH = "auto_bluetooth"

        /** How long the car must stay disconnected before the profile goes back to DAILY. */
        const val LEAVE_DELAY_MS = 2 * 60_000L
    }
}
