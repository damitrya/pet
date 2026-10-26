package com.damitrya.mimio.state

/**
 * Is the car moving, from usable GPS fixes, with hysteresis: moving from [START_KMH], stopped only after
 * [STOP_FIXES] fixes in a row below [STOP_KMH]; in between the previous answer holds. The drive of 26 October
 * crawled at ≈3 km/h in a jam and the old single 3 km/h limit flipped MOVEMENT_START / MOVEMENT_STOP on almost
 * every fix.
 *
 * Only the state machine's own "moving" changes (waking up, cutting scenes, no dozing off while driving, the
 * animation context); TRIP_START / ARRIVAL keep their own rules ([TripTracker], the parking check).
 */
class MovementStabilizer {

    var isMoving: Boolean = false
        private set

    private var below = 0

    fun reset() {
        isMoving = false
        below = 0
    }

    /** A new usable fix; returns true when [isMoving] changed. */
    fun onFix(kmh: Float): Boolean {
        when {
            kmh >= START_KMH -> {
                below = 0
                if (!isMoving) {
                    isMoving = true
                    return true
                }
            }
            kmh < STOP_KMH -> {
                below++
                if (isMoving && below >= STOP_FIXES) {
                    isMoving = false
                    return true
                }
            }
            else -> below = 0
        }
        return false
    }

    companion object {
        const val START_KMH = 5f
        const val STOP_KMH = 2f
        const val STOP_FIXES = 2
    }
}
