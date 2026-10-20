package com.damitrya.mimio.overlay

import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.state.FakeClock
import com.damitrya.mimio.state.InMemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHapticsTest {

    private val clock = FakeClock()
    private val settings = SettingsRepository(InMemoryPrefs())
    private val played = mutableListOf<TouchHaptics.Effect>()
    private val haptics = TouchHaptics({ settings.hapticsEnabled }, clock) { played += it; true }

    @Test
    fun onByDefault() {
        assertTrue(settings.hapticsEnabled)
        assertTrue(haptics.play(TouchHaptics.Effect.TAP))
        assertEquals(listOf(TouchHaptics.Effect.TAP), played)
    }

    @Test
    fun switchedOffInTheSettingsStaysSilent() {
        settings.hapticsEnabled = false
        for (e in TouchHaptics.Effect.values()) {
            clock.advance(1_000)
            assertFalse(haptics.play(e))
        }
        assertTrue(played.isEmpty())
        settings.hapticsEnabled = true          // takes effect at once, no restart
        assertTrue(haptics.play(TouchHaptics.Effect.HOLD))
    }

    @Test
    fun rapidTapsGiveAtMostOneTickEach() {
        // 5 taps within 3 s (tap spam): one tick per tap, none doubled.
        repeat(5) {
            haptics.play(TouchHaptics.Effect.TAP)
            haptics.play(TouchHaptics.Effect.TAP)    // the same tap reported twice
            clock.advance(400)
        }
        assertEquals(5, played.size)
    }

    @Test
    fun ticksNeverBuzz() {
        repeat(100) {
            haptics.play(TouchHaptics.Effect.TAP)
            clock.advance(10)
        }
        assertTrue("${played.size}", played.size <= 1000 / TouchHaptics.MIN_GAP_MS.toInt() + 1)
    }
}
