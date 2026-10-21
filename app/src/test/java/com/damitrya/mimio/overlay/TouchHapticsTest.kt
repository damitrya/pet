package com.damitrya.mimio.overlay

import com.damitrya.mimio.data.HapticMode
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.state.FakeClock
import com.damitrya.mimio.state.InMemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHapticsTest {

    private val clock = FakeClock()
    private val prefs = InMemoryPrefs()
    private val settings = SettingsRepository(prefs)
    private val played = mutableListOf<Pair<TouchHaptics.Effect, HapticMode>>()
    private val haptics = TouchHaptics({ settings.hapticMode }, clock) { e, m -> played += e to m; true }

    @Test
    fun softByDefault() {
        assertEquals(HapticMode.SOFT, settings.hapticMode)
        assertTrue(haptics.play(TouchHaptics.Effect.TAP))
        assertEquals(listOf(TouchHaptics.Effect.TAP to HapticMode.SOFT), played)
    }

    @Test
    fun theOldSwitchOffStaysOff() {
        // Before wave 5 "haptics_enabled" was a switch: a user who turned it off keeps it off.
        prefs.edit().putBoolean(SettingsRepository.KEY_HAPTICS, false).apply()
        assertEquals(HapticMode.OFF, settings.hapticMode)
        prefs.edit().putBoolean(SettingsRepository.KEY_HAPTICS, true).apply()
        assertEquals(HapticMode.SOFT, settings.hapticMode)
        settings.hapticMode = HapticMode.EXPRESSIVE
        prefs.edit().putBoolean(SettingsRepository.KEY_HAPTICS, false).apply()
        assertEquals("the new choice wins", HapticMode.EXPRESSIVE, settings.hapticMode)
    }

    @Test
    fun switchedOffInTheSettingsStaysSilent() {
        settings.hapticMode = HapticMode.OFF
        for (e in TouchHaptics.Effect.values()) {
            clock.advance(1_000)
            assertFalse(haptics.play(e))
        }
        assertTrue(played.isEmpty())
        settings.hapticMode = HapticMode.EXPRESSIVE      // takes effect at once, no restart
        assertTrue(haptics.play(TouchHaptics.Effect.HOLD))
        assertEquals(listOf(TouchHaptics.Effect.HOLD to HapticMode.EXPRESSIVE), played)
    }

    @Test
    fun rapidTapsGiveAtMostOneResponseEach() {
        // 5 taps within 3 s (tap spam): one response per tap, none doubled.
        repeat(5) {
            haptics.play(TouchHaptics.Effect.TAP)
            haptics.play(TouchHaptics.Effect.TAP)    // the same tap reported twice
            clock.advance(400)
        }
        assertEquals(5, played.size)
    }

    @Test
    fun responsesNeverBuzz() {
        repeat(100) {
            haptics.play(TouchHaptics.Effect.TAP)
            clock.advance(10)
        }
        assertTrue("${played.size}", played.size <= 1000 / TouchHaptics.MIN_GAP_MS.toInt() + 1)
    }

    @Test
    fun patternsMatchTheDesign() {
        for (mode in listOf(HapticMode.SOFT, HapticMode.EXPRESSIVE)) {
            val tap = HapticPatterns.of(TouchHaptics.Effect.TAP, mode)
            assertEquals("tap: one 15 ms pulse", listOf(15L), tap.pulses)
            val hold = HapticPatterns.of(TouchHaptics.Effect.HOLD, mode)
            assertEquals("petting: two 20 ms pulses", listOf(20L, 20L), hold.pulses)
            assertEquals(hold.strengths[0], hold.strengths[1])
            val heart = HapticPatterns.of(TouchHaptics.Effect.HEART, mode)
            assertEquals("heart: three pulses", 3, heart.pulses.size)
            assertTrue("growing stronger: ${heart.strengths}", heart.strengths.zipWithNext().all { (a, b) -> b > a })
            for (e in TouchHaptics.Effect.values()) {
                val p = HapticPatterns.of(e, mode)
                assertTrue("$e $mode is short: ${p.totalMs}", p.totalMs <= HapticPatterns.MAX_TOTAL_MS)
                assertTrue(p.strengths.all { it in 1..255 })
                assertEquals("starts at once", 0L, p.timings[0])
            }
        }
        for (e in TouchHaptics.Effect.values()) {
            val soft = HapticPatterns.of(e, HapticMode.SOFT).strengths
            val expressive = HapticPatterns.of(e, HapticMode.EXPRESSIVE).strengths
            assertTrue("$e: expressive is stronger", soft.zip(expressive).all { (s, x) -> x > s })
        }
    }

    @Test
    fun withoutAmplitudeControlTheStrengthBecomesLength() {
        val heart = HapticPatterns.of(TouchHaptics.Effect.HEART, HapticMode.EXPRESSIVE).withoutAmplitude()
        assertTrue(heart.strengths.all { it == HapticPattern.DEFAULT_AMPLITUDE })
        assertTrue("still rising: ${heart.pulses}", heart.pulses.zipWithNext().all { (a, b) -> b > a })
        val hold = HapticPatterns.of(TouchHaptics.Effect.HOLD, HapticMode.SOFT).withoutAmplitude()
        assertEquals(2, hold.pulses.size)
        assertTrue(hold.pulses.all { it >= 8 })
    }

    @Test
    fun routeFollowsTheSystemAndTheDevice() {
        assertEquals(HapticRoute.VIBRATOR, HapticRoute.choose(hasVibrator = true, hasPermission = true, systemTouchFeedback = true))
        assertEquals(HapticRoute.VIBRATOR, HapticRoute.choose(true, true, null))
        assertEquals("system touch feedback off", HapticRoute.SILENT, HapticRoute.choose(true, true, false))
        assertEquals("no motor access: the standard feedback", HapticRoute.VIEW_FEEDBACK, HapticRoute.choose(false, true, true))
        assertEquals(HapticRoute.VIEW_FEEDBACK, HapticRoute.choose(true, false, true))
    }
}
