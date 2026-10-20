package com.damitrya.mimio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixGateTest {

    private val gate = GpsFixGate()

    private fun fix(seq: Long, ageMs: Long = 0L, accuracyM: Float = 5f, kmh: Float = 50f) =
        DrivingData(speedKmh = kmh, hasGpsFix = true, fixSeq = seq, fixAgeMs = ageMs, accuracyM = accuracyM)

    @Test
    fun aNewFixPassesOnce() {
        assertTrue(gate.isNewUsableFix(fix(1)))
        assertFalse("a sensor event repeating the fix", gate.isNewUsableFix(fix(1).copy(isBraking = true)))
        assertFalse(gate.isNewUsableFix(fix(1).copy(isTurning = true, turnDirection = TurnDirection.LEFT)))
        assertTrue(gate.isNewUsableFix(fix(2)))
    }

    @Test
    fun noGpsNoFix() {
        assertFalse(gate.isNewUsableFix(DrivingData(speedKmh = 50f, hasGpsFix = false, fixSeq = 3)))
        assertFalse("before any location", gate.isNewUsableFix(DrivingData(speedKmh = 0f, hasGpsFix = true)))
    }

    @Test
    fun oldOrInaccurateFixesAreNotUsable() {
        assertFalse(gate.isNewUsableFix(fix(1, ageMs = GpsFixGate.MAX_AGE_MS + 1)))
        assertFalse(gate.isNewUsableFix(fix(2, accuracyM = GpsFixGate.MAX_ACCURACY_M + 1)))
        assertTrue("accuracy unknown", gate.isNewUsableFix(fix(3, accuracyM = 0f)))
        assertTrue(gate.isNewUsableFix(fix(4, ageMs = 2_000, accuracyM = 20f)))
    }

    @Test
    fun anOlderFixNumberIsNotNew() {
        assertTrue(gate.isNewUsableFix(fix(5)))
        assertFalse(gate.isNewUsableFix(fix(4)))
    }
}
