package com.damitrya.mimio.data

/**
 * One rule for every consumer of [DrivingData] speed: is this a new, usable GPS fix?
 *
 * [SensorDataProvider] also sends data on braking / turn changes, repeating the last fix; only a
 * larger [DrivingData.fixSeq] is new. A usable fix is at most [MAX_AGE_MS] old (the system may
 * deliver cached locations) and at least [MAX_ACCURACY_M] accurate (0 = accuracy unknown).
 *
 * Keeps the last fix number it saw, so give each consumer its own gate. Not thread-safe.
 */
class GpsFixGate {

    private var lastSeq = 0L

    /** True once per new usable fix; repeats, old and inaccurate fixes are false. */
    fun isNewUsableFix(data: DrivingData): Boolean {
        if (!data.hasGpsFix || data.fixSeq <= lastSeq) return false
        lastSeq = data.fixSeq
        return isUsable(data.fixAgeMs, data.accuracyM)
    }

    companion object {
        /** A fix older than this says nothing about the car any more. */
        const val MAX_AGE_MS = 10_000L

        /** A fix less accurate than this says too little about the speed. */
        const val MAX_ACCURACY_M = 50f

        fun isUsable(ageMs: Long, accuracyM: Float): Boolean = ageMs in 0..MAX_AGE_MS && accuracyM <= MAX_ACCURACY_M
    }
}
