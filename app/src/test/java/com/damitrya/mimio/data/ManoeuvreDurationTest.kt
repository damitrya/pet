package com.damitrya.mimio.data

import com.damitrya.mimio.diagnostics.DiagnosticReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Wave 8: how long a manoeuvre lasted, and which duration the 300 ms check uses. The drive of 7 November had 158
 * rejections EVENT_TOO_SHORT, 147 of them with a recorded duration of 300 ms or more, for example
 * `BRAKE SOFT peak=1.2 m/s2 duration=2018 ms reason=EVENT_TOO_SHORT`.
 */
class ManoeuvreDurationTest {

    private class Run(var gateReason: DiagnosticReason? = null) {
        val config = CarMotionConfig.DEFAULT
        val channel = ManoeuvreChannel(InertiaKind.ACCELERATE, InertiaKind.BRAKE, 1.2f, 2.5f, 1.2f, 2.5f, config)
        val events = mutableListOf<CarMotionEvent>()
        val outcomes = mutableListOf<InertiaOutcome>()
        var t = 0L

        fun feed(value: Float, ms: Long) {
            val end = t + ms
            while (t < end) {
                channel.onSample(value, t, 40f, { _, _ -> gateReason }, { outcomes += it }, { events += it })
                t += 20
            }
        }

        /** [high] for [highMs], then [low] for [lowMs], [times] times. */
        fun hover(high: Float, highMs: Long, low: Float, lowMs: Long, times: Int) =
            repeat(times) { feed(high, highMs); feed(low, lowMs) }
    }

    @Test
    fun theReportedBrakingHoveringAroundItsThresholdIsAccepted() {
        // The case of the report: a 2 s soft braking whose smoothed value hovers around 1.2 m/s² (peak 1.2–1.3).
        // Every 20–60 ms dip below 1.2 used to restart the 300 ms count, then 400 ms below 0.72 ended the
        // "too short" candidate with duration = the whole episode.
        val r = Run()
        r.hover(-1.26f, 100, -1.1f, 60, 13)
        r.feed(0f, 1_000)
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT), r.events.map { it.kind to it.level })
        val o = r.outcomes.single()
        assertTrue(o.accepted)
        assertTrue("held ${o.aboveMs} ms", o.aboveMs >= 300L)
        assertEquals(1.2f, o.threshold, 0.001f)
    }

    @Test
    fun aValueThatKeepsDroppingBelowItsThresholdIsNotSustainedRatherThanTooShort() {
        val r = Run()
        r.hover(-1.3f, 100, -0.9f, 240, 6)                  // 2 s, but never more than 100 ms in a row above 1.2
        r.feed(0f, 1_000)
        assertTrue(r.events.isEmpty())
        val o = r.outcomes.single()
        assertEquals(DiagnosticReason.THRESHOLD_NOT_SUSTAINED, o.reason)
        assertTrue("the event lasted ${o.durationMs} ms", o.durationMs >= 300L)
        assertTrue("above the threshold ${o.aboveMs} ms", o.aboveMs < 300L)
        assertTrue("the episode includes the release wait", o.episodeMs >= o.durationMs + CarMotionConfig.DEFAULT.endMs)
        assertEquals(InertiaOutcome.END_RELEASED, o.endedBy)
    }

    @Test
    fun aShortSpikeIsTooShortAndItsDurationSaysSo() {
        val r = Run()
        r.feed(0f, 200)
        r.feed(-1.8f, 200)
        r.feed(0f, 1_000)
        assertTrue(r.events.isEmpty())
        val o = r.outcomes.single()
        assertEquals(DiagnosticReason.EVENT_TOO_SHORT, o.reason)
        assertTrue("duration ${o.durationMs} ms", o.durationMs < 300L)
        assertTrue("episode ${o.episodeMs} ms", o.episodeMs >= 400L)
    }

    @Test
    fun shortNoiseBelowTheConfirmationNeverPlays() {
        // Potholes and road joints: spikes of 40–120 ms with long quiet gaps between them.
        val r = Run()
        val random = Random(5)
        repeat(60) {
            r.feed((if (random.nextBoolean()) 1f else -1f) * (1.3f + random.nextFloat()), 40L + random.nextInt(4) * 20L)
            r.feed(random.nextFloat() * 0.5f, 300L + random.nextInt(10) * 20L)
        }
        assertTrue(r.events.toString(), r.events.isEmpty())
        assertTrue(r.outcomes.all { !it.accepted })
    }

    @Test
    fun everyRejectionNamesTheConditionThatReallyFailed() {
        // Random smoothed signals: whatever the reason, it matches the recorded durations.
        val random = Random(11)
        val r = Run()
        var v = 0f
        repeat(20_000) {
            v += (random.nextFloat() * 2 - 1) * 0.5f - v * 0.08f
            r.feed(v.coerceIn(-3f, 3f), 20)
        }
        r.feed(0f, 1_000)
        val sustain = CarMotionConfig.DEFAULT.sustainMs
        assertTrue(r.outcomes.size > 20)
        for (o in r.outcomes) {
            when (o.reason) {
                DiagnosticReason.EVENT_TOO_SHORT -> assertTrue(o.toString(), o.durationMs < sustain && o.aboveMs < sustain)
                DiagnosticReason.THRESHOLD_NOT_SUSTAINED -> assertTrue(o.toString(), o.durationMs >= sustain && o.aboveMs < sustain)
                null -> assertTrue(o.toString(), o.accepted && o.aboveMs >= sustain)
                else -> throw AssertionError("unexpected $o")
            }
            assertTrue(o.toString(), o.episodeMs >= o.durationMs)
        }
    }

    @Test
    fun aDipLongerThanTheToleranceStillBreaksTheRun() {
        val r = Run()
        r.feed(-1.5f, 200)
        r.feed(-1.0f, 200)                                  // 200 ms below the threshold > 150 ms tolerance
        r.feed(-1.5f, 200)
        r.feed(0f, 1_000)
        assertTrue(r.events.isEmpty())
        assertEquals(DiagnosticReason.THRESHOLD_NOT_SUSTAINED, r.outcomes.single().reason)
    }

    @Test
    fun aStrongLevelHoveringAroundItsThresholdIsStrong() {
        val r = Run()
        r.hover(-2.7f, 100, -2.4f, 60, 6)
        r.feed(0f, 1_000)
        assertEquals(listOf(InertiaLevel.STRONG), r.events.map { it.level })
    }

    @Test
    fun aShortStrongPeakAtTheEndOfASoftRunDoesNotSwallowIt() {
        // 200 ms at 1.5, then 200 ms past the strong threshold, then gone: 400 ms above the soft threshold is a
        // manoeuvre; before, waiting for the strong level to hold could end it without any decision.
        val r = Run()
        r.feed(-1.5f, 200)
        r.feed(-3.0f, 200)
        r.feed(0f, 1_000)
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT), r.events.map { it.kind to it.level })
    }

    @Test
    fun aCandidateCutByTheHandIsReportedWithThatReason() {
        val r = Run()
        r.feed(-1.5f, 200)
        r.channel.abandon(DiagnosticReason.PHONE_MOVING, r.t, 40f) { r.outcomes += it }
        assertEquals(DiagnosticReason.PHONE_MOVING, r.outcomes.single().reason)
        assertEquals(InertiaOutcome.END_INTERRUPTED, r.outcomes.single().endedBy)
        r.feed(0f, 1_000)
        assertEquals("nothing more about it", 1, r.outcomes.size)
    }

    // ---- whole traces

    @Test
    fun aSoftTurnLongerThan300msLeansOnce() {
        for (seed in 1..5) {
            val tracker = CarMotionTracker()
            val s = CarSim(tracker, Mounts.UPRIGHT, seed = seed, vibration = 0.4f, vibrationGyro = 0.05f)
            s.calibrateOnStraightRoad()
            s.cruise(1_000L, 30f)
            s.turn(1_400L, 1.6f, rampMs = 300L)            // a quick lane change / a short bend
            s.cruise(2_000L, 30f)
            assertEquals("seed $seed ${s.outcomes}", listOf(InertiaKind.TURN_LEFT to InertiaLevel.SOFT), s.events.map { it.kind to it.level })
        }
    }

    @Test
    fun theDipToleranceMakesNoLeansOnARoughRoad() {
        // False alarms must not grow: a minute of cobbles and potholes per seed, at town and highway speed.
        for (seed in 1..6) {
            for (kmh in listOf(30f, 80f)) {
                val s = CarSim(CarMotionTracker(), Mounts.UPRIGHT, seed = seed)
                s.calibrateOnStraightRoad()
                s.changeSpeed(kmh, 1.5f)
                s.cruise(3_000L, kmh)
                s.events.clear()
                s.vibration = 1.6f
                s.vibrationGyro = 0.25f
                s.cruise(60_000L, kmh)
                assertTrue("seed $seed at $kmh km/h: ${s.events}", s.events.isEmpty())
            }
        }
    }

    @Test
    fun outcomesCarryTheirContext() {
        val tracker = CarMotionTracker()
        val s = CarSim(tracker, Mounts.UPRIGHT)
        s.calibrateOnStraightRoad()
        s.cruise(1_000L, 40f)
        s.changeSpeed(20f, 1.8f)
        val o = s.outcomes.first { it.kind == InertiaKind.BRAKE }
        assertTrue(o.accepted)
        assertEquals(CalibrationState.READY, o.calibration)
        assertTrue(o.calibrationQuality >= 0.8f)
        assertTrue(!o.phoneMoving)
        assertEquals(1.2f, o.threshold, 0.001f)
        assertTrue("raw peak ${o.rawPeak}", !o.rawPeak.isNaN())
    }
}
