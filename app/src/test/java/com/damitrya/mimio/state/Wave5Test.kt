package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.BatteryData
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.state.AnimationDirectorTest.Companion.FOX
import com.damitrya.mimio.state.AnimationDirectorTest.Companion.SLIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Wave 5: the guaranteed heart (and SHY), the battery at 10 % / 1 %, the new music shows and the preview —
 * state machine and director together, as the service and CharacterView drive them.
 */
class Wave5Test {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val mood = CharacterMood(MemoryMoodStore(), clock)
    private val settings = SettingsRepository(InMemoryPrefs())
    private var ctx = PlayContext()
    private val log = mutableListOf<String>()
    private lateinit var sm: StateMachine
    private lateinit var director: AnimationDirector

    /** (state, file) in the order the view started them. */
    private val played = mutableListOf<Pair<CompanionState, String>>()
    private var shutdowns = 0

    /** One-shots end after this long, like the view reports them; null = they never report an end. */
    private var oneShotMs: Long? = 2_000L
    private var available: (AnimationVariant) -> Boolean = FOX

    @Before
    fun setUp() {
        setUp(seed = 17)
    }

    private fun setUp(seed: Int) {
        played.clear()
        sm = StateMachine(settings, clock, scheduler, Random(5), mood)
        director = AnimationDirector(mood, { ctx }, Random(seed), clock) { log += it }
        sm.heartDue = { director.isHeartDue() }
        sm.idleBusy = { director.isSceneRunning() }
        sm.distractingScene = { director.isDistractingSceneRunning() }
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                val v = director.pick(newState, available) ?: return
                played += newState to v.resName
                val ms = oneShotMs
                if (!newState.isLooping && ms != null) {
                    scheduler.postDelayed(Runnable {
                        director.onFinished(newState, v)
                        sm.onOneShotCompleted(newState)
                    }, ms)
                }
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
            override fun onLowBatteryShutdown() {
                shutdowns++
            }
        })
        sm.setProfile(AppProfile.DAILY)
    }

    private val state get() = sm.getCurrentState()

    /** One petting: hold, release; returns the finale (state, file). */
    private fun pet(): Pair<CompanionState, String> {
        sm.onLongPress()
        scheduler.advance(1_500)
        sm.onLongPressReleased()
        return played.last()
    }

    private fun isHeart(f: Pair<CompanionState, String>) = f.second == AnimationPools.HEART_TRANSFORM

    // ---- 1: the heart after petting

    @Test
    fun everyFifthPettingWithoutAHeartIsTheHeart() {
        for (seed in 1..20) {
            setUp(seed)
            val finales = (1..40).map { pet().also { scheduler.advance(3 * 60_000L) } }   // no shy series
            var since = 0
            for (f in finales) {
                since = if (isHeart(f)) 0 else since + 1
                assertTrue("seed $seed: $finales", since < AnimationDirector.HEART_EVERY_PETS)
            }
        }
    }

    @Test
    fun theGuaranteeIgnoresTheCooldownAndTheMinimumGap() {
        // Petting every 15 s: after a heart the random one is blocked for 2 minutes, yet the 5th is a heart.
        repeat(60) {
            val f = pet()
            scheduler.advance(15_000L)
            if (isHeart(f)) return@repeat
        }
        val start = played.indexOfLast(::isHeart)
        assertTrue("a first heart within 5 pettings: $played", start >= 0)
        val after = mutableListOf<Pair<CompanionState, String>>()
        repeat(5) { after += pet(); scheduler.advance(15_000L) }
        assertTrue("the 5th petting after a heart, 75 s later: $after", isHeart(after[4]))
        assertTrue(after.take(4).none(::isHeart))
        assertTrue(log.any { "guaranteed" in it })
    }

    @Test
    fun shyNeverKeepsTheHeartAway() {
        // Petting every 3 s: SHY comes every third time, but the 5th finale without a heart is HEARTS → the heart.
        val finales = (1..30).map { pet().also { scheduler.advance(3_000L) } }
        assertTrue(finales.any { it.first == CompanionState.SHY })
        var since = 0
        for (f in finales) {
            since = if (isHeart(f)) 0 else since + 1
            assertTrue("$finales", since < AnimationDirector.HEART_EVERY_PETS)
        }
        // SHY counts as a petting finale towards the heart
        val shyBeforeHeart = finales.zipWithNext().any { (a, b) -> a.first == CompanionState.SHY && isHeart(b) } ||
            finales.windowed(5).any { w -> isHeart(w[4]) && w.take(4).any { it.first == CompanionState.SHY } }
        assertTrue(shyBeforeHeart)
    }

    @Test
    fun theHeartReplacesShyWhenDue() {
        // Four finales without a heart; the 5th is the third quick petting, so SHY would come: the heart instead.
        available = { it.resName != AnimationPools.HEART_TRANSFORM }
        repeat(2) { pet(); scheduler.advance(30_000L) }
        repeat(2) { pet(); scheduler.advance(3_000L) }
        available = FOX
        val fifth = pet()
        scheduler.advance(3_000L)
        assertEquals(CompanionState.HEARTS, fifth.first)
        assertTrue(played.toString(), isHeart(fifth))
        assertTrue(log.any { "instead of SHY" in it } || log.any { "guaranteed" in it })
        // the shy series starts over with the heart, and still works after it
        pet(); scheduler.advance(3_000L)
        pet(); scheduler.advance(3_000L)
        assertEquals(CompanionState.SHY, pet().first)
    }

    @Test
    fun randomHeartsStillHappenInBetween() {
        val positions = mutableListOf<Int>()
        for (seed in 1..20) {
            setUp(seed)
            var since = 0
            repeat(30) {
                val f = pet()
                scheduler.advance(3 * 60_000L)
                if (isHeart(f)) {
                    positions += since + 1
                    since = 0
                } else since++
            }
        }
        assertTrue("some hearts come before the 5th petting: $positions", positions.count { it < 5 } > positions.size / 4)
        assertTrue(positions.all { it <= AnimationDirector.HEART_EVERY_PETS })
    }

    @Test
    fun theReasonIsLogged() {
        repeat(10) { pet(); scheduler.advance(20_000L) }
        val reasons = log.filter { it.startsWith("HEARTS") }
        assertEquals(10, reasons.size)
        assertTrue(reasons.toString(), reasons.any { "minimum gap" in it || "cooldown" in it || "chance" in it })
    }

    @Test
    fun aCharacterWithoutTheHeartIsNotStuck() {
        available = SLIME
        val finales = (1..12).map { pet().also { scheduler.advance(3_000L) } }
        assertTrue("the slime still gets shy: $finales", finales.any { it.first == CompanionState.SHY })
        assertTrue(finales.none(::isHeart))
    }

    @Test
    fun aCancelledPettingDoesNotCount() {
        repeat(4) { pet(); scheduler.advance(3 * 60_000L) }
        val before = director.isHeartDue()
        sm.onLongPress()
        sm.onLongPressCancelled()                 // turned into a drag: no finale
        assertEquals(before, director.isHeartDue())
    }

    // ---- 4: battery

    private fun battery(level: Int, charging: Boolean = false) =
        sm.updateBatteryData(BatteryData(level = level, isCharging = charging, isLow = level <= settings.lowBatteryThreshold))

    private fun count(s: CompanionState) = played.count { it.first == s }

    /** Waits [ms] with the fox kept awake (a warning waits while it sleeps, as any idle one-shot does). */
    private fun awake(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 60_000L)
            scheduler.advance(step)
            sm.onTap()
            left -= step
        }
        scheduler.advance(3_000L)
    }

    @Test
    fun tenPercentWarnsOnceThenOnlyAfterALongPause() {
        settings.lowBatteryThreshold = 8          // at or below 10 %: only the 10 % warning
        battery(12)
        battery(11)
        assertEquals(0, count(CompanionState.BATTERY_CRITICAL))
        battery(10)
        assertEquals(1, count(CompanionState.BATTERY_CRITICAL))
        assertEquals("battery_sad", played.last { it.first == CompanionState.BATTERY_CRITICAL }.second)
        repeat(20) {                              // updates keep coming: no repeats
            awake(60_000L)
            battery(if (it < 10) 10 else 9)
        }
        battery(8)
        assertEquals(1, count(CompanionState.BATTERY_CRITICAL))
        assertEquals("no extra threshold warning at 8 %", 0, count(CompanionState.LOW_BATTERY))
        awake(BatteryWatcher.REPEAT_MS)
        battery(7)
        assertEquals("again after the long pause", 2, count(CompanionState.BATTERY_CRITICAL))
    }

    @Test
    fun thresholdWarningAndTenPercentAreSeparate() {
        settings.lowBatteryThreshold = 15
        battery(16)
        battery(15)
        assertEquals(1, count(CompanionState.LOW_BATTERY))
        awake(5 * 60_000L)
        battery(12)
        battery(10)
        assertEquals(1, count(CompanionState.LOW_BATTERY))
        assertEquals("10 % is worse: it plays even soon after the first warning", 1, count(CompanionState.BATTERY_CRITICAL))
    }

    @Test
    fun chargingSilencesAndRearms() {
        battery(10, charging = true)
        assertEquals(0, count(CompanionState.BATTERY_CRITICAL))
        battery(10)
        assertEquals(1, count(CompanionState.BATTERY_CRITICAL))
        battery(10, charging = true)
        awake(10 * 60_000L)
        battery(9)                                // unplugged again soon: no second warning
        assertEquals(1, count(CompanionState.BATTERY_CRITICAL))
        battery(60, charging = true)              // a real recharge re-arms it
        awake(10_000L)
        battery(10)
        assertEquals(2, count(CompanionState.BATTERY_CRITICAL))
    }

    @Test
    fun aBusyFoxWarnsWhenFree() {
        oneShotMs = null
        sm.onAlertTriggered()
        battery(10)
        assertEquals(CompanionState.ALERT, state)
        sm.onOneShotCompleted(CompanionState.ALERT)
        sm.onOneShotCompleted(CompanionState.RELIEF)
        assertEquals(CompanionState.CALM, state)
        scheduler.advance(6_000L)
        assertEquals(CompanionState.BATTERY_CRITICAL, state)
    }

    @Test
    fun onePercentDoesNothingWithTheSettingOff() {
        assertFalse(settings.autoHideOnEmptyBattery)
        battery(3)
        battery(2)
        battery(1)
        scheduler.advance(60_000L)
        assertEquals(0, count(CompanionState.BATTERY_SHUTDOWN))
        assertEquals(0, shutdowns)
    }

    @Test
    fun elevenTenOne() {
        settings.autoHideOnEmptyBattery = true
        battery(16)
        battery(15)                               // the user's threshold (15 % by default): the first warning
        assertEquals(1, count(CompanionState.LOW_BATTERY))
        awake(10_000L)
        battery(11)
        assertEquals(0, count(CompanionState.BATTERY_CRITICAL))
        battery(10)
        assertEquals(1, count(CompanionState.BATTERY_CRITICAL))
        scheduler.advance(10_000L)
        battery(2)
        battery(1)
        assertEquals(CompanionState.BATTERY_SHUTDOWN, state)
        assertEquals("cloud_farewell", played.last().second)
        assertEquals(0, shutdowns)
        sm.onTap()
        sm.onLongPress()
        assertEquals("touches do not interrupt it", CompanionState.BATTERY_SHUTDOWN, state)
        scheduler.advance(2_000L)                 // the animation ends
        assertEquals(1, shutdowns)
        scheduler.advance(StateMachine.SHUTDOWN_TIMEOUT_MS * 2)
        battery(1)
        battery(0)
        assertEquals("stopped once", 1, shutdowns)
    }

    @Test
    fun theShutdownHasASafetyTimeout() {
        settings.autoHideOnEmptyBattery = true
        oneShotMs = null                          // the end never comes (overlay hidden, decoder stalled)
        battery(2)
        battery(1)
        scheduler.advance(StateMachine.SHUTDOWN_TIMEOUT_MS - 1)
        assertEquals(0, shutdowns)
        scheduler.advance(1)
        assertEquals(1, shutdowns)
    }

    @Test
    fun chargingDuringTheCloudCancelsTheStop() {
        settings.autoHideOnEmptyBattery = true
        oneShotMs = null
        battery(2)
        battery(1)
        assertTrue(sm.isShutdownRunning())
        battery(1, charging = true)
        assertFalse(sm.isShutdownRunning())
        assertEquals("the fox comes back", CompanionState.GREETING, state)
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS * 2)
        assertEquals(0, shutdowns)
        battery(1)                                // unplugged at 1 %: no new drop, it stays
        scheduler.advance(StateMachine.SHUTDOWN_TIMEOUT_MS * 2)
        assertEquals(0, shutdowns)
    }

    @Test
    fun startingAtOnePercentKeepsMimio() {
        settings.autoHideOnEmptyBattery = true
        battery(1)                                // the first reading: the user just started it
        scheduler.advance(60_000L)
        assertEquals(0, count(CompanionState.BATTERY_SHUTDOWN))
        assertEquals(0, shutdowns)
    }

    @Test
    fun brakingDuringTheCloudStopsAtOnce() {
        settings.autoHideOnEmptyBattery = true
        oneShotMs = null
        battery(2)
        battery(1)
        sm.onAlertTriggered()
        assertEquals(1, shutdowns)
        scheduler.advance(StateMachine.SHUTDOWN_TIMEOUT_MS * 2)
        sm.onOneShotCompleted(CompanionState.BATTERY_SHUTDOWN)
        assertEquals(1, shutdowns)
    }

    @Test
    fun aPettingDelaysTheCloudUntilReleased() {
        settings.autoHideOnEmptyBattery = true
        oneShotMs = null
        battery(2)
        sm.onLongPress()
        battery(1)
        assertEquals(CompanionState.PET, state)
        sm.onLongPressReleased()
        sm.onOneShotCompleted(state)
        scheduler.advance(5_000L)
        assertEquals(CompanionState.BATTERY_SHUTDOWN, state)
    }

    @Test
    fun noRestartAfterTheStop() {
        // A sticky restart by the system after the stop at 1 %: stays stopped.
        assertTrue(BatteryWatcher.staysStopped(stoppedForLowBattery = true, autoHide = true, level = 1, plugged = false))
        assertTrue("even once charging", BatteryWatcher.staysStopped(true, autoHide = true, level = 30, plugged = true))
        // killed during the cloud, before the flag was written
        assertTrue(BatteryWatcher.staysStopped(false, autoHide = true, level = 1, plugged = false))
        assertFalse(BatteryWatcher.staysStopped(false, autoHide = true, level = 1, plugged = true))
        assertFalse(BatteryWatcher.staysStopped(false, autoHide = false, level = 1, plugged = false))
        assertFalse(BatteryWatcher.staysStopped(false, autoHide = true, level = -1, plugged = false))
        assertFalse("off by default", settings.autoHideOnEmptyBattery)
        assertFalse(settings.stoppedForLowBattery)
    }

    @Test
    fun anUnknownLevelIsIgnored() {
        settings.autoHideOnEmptyBattery = true
        battery(50)
        battery(-1)
        scheduler.advance(60_000L)
        assertEquals(0, count(CompanionState.BATTERY_CRITICAL))
        assertEquals(0, shutdowns)
    }

    // ---- 3: the music shows

    private fun musicPicks(minutes: Int): List<String> =
        (1..minutes * 60 / 3).map { clock.advance(3_000); director.pick(CompanionState.MUSIC, available)!!.resName }

    @Test
    fun newMusicShowsArePickedButStayRare() {
        val picks = musicPicks(240)
        for (name in AnimationPools.MUSIC_SHOWS_WAVE5) assertTrue(name, name in picks)
        val shows = setOf("guitar", "microphone") + AnimationPools.MUSIC_SHOWS_WAVE5
        val share = picks.count { it in shows }.toDouble() / picks.size
        assertTrue("all shows together: $share", share < 0.08)
        val usual = picks.count { it in setOf("music", "music2", "music_nod", "music_paws") }.toDouble() / picks.size
        assertTrue("the usual dances stay the norm: $usual", usual > 0.7)
    }

    @Test
    fun aShowNeverComesBackSoon() {
        val times = HashMap<String, MutableList<Long>>()
        repeat(6 * 60 * 60 / 3) {
            clock.advance(3_000)
            val v = director.pick(CompanionState.MUSIC, available)!!
            times.getOrPut(v.resName) { mutableListOf() } += clock.now
        }
        val gaps = mapOf("bayan" to 5, "balalaika" to 5, "punk" to 6)
        for ((name, minutes) in gaps) {
            val t = times.getValue(name)
            t.zipWithNext().forEach { (a, b) -> assertTrue(name, b - a >= minutes * 60_000L) }
        }
        val shows = times.filterKeys { it in setOf("guitar", "microphone") + gaps.keys }.values.flatten().sorted()
        shows.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= AnimationDirector.SCENE_GAP_MS) }
    }

    @Test
    fun noShowsWhileDriving() {
        ctx = PlayContext(moving = true, car = true, trip = TripPhase.DRIVING)
        assertTrue(musicPicks(120).none { it in AnimationPools.MUSIC_SHOWS_WAVE5 })
    }

    @Test
    fun theNewShowsAreScenesTheDanceBreakWaitsFor() {
        for (name in AnimationPools.MUSIC_SHOWS_WAVE5) {
            director.pick(CompanionState.MUSIC) { it.resName == name }
            assertTrue(name, director.isSceneRunning())
            assertTrue(name, director.isDistractingSceneRunning())
            clock.advance(AnimationDirector.MAX_SCENE_MS)
            assertFalse(director.isSceneRunning())
        }
    }

    @Test
    fun theSlimeSkipsTheNewShows() {
        available = SLIME
        assertTrue(musicPicks(60).none { it in AnimationPools.MUSIC_SHOWS_WAVE5 })
    }

    // ---- preview (debug tools)

    @Test
    fun previewPlaysTheChosenFileOnceAndChangesNothing() {
        sm.updateMediaData(MediaData(isPlaying = true))
        val dueBefore = director.isHeartDue()
        director.preview(AnimationPools.HEART_TRANSFORM)
        assertTrue(sm.startPreview())
        assertEquals(CompanionState.PREVIEW to AnimationPools.HEART_TRANSFORM, played.last())
        assertFalse("not a background scene", director.isSceneRunning())
        scheduler.advance(2_000L)
        assertEquals(CompanionState.MUSIC, state)
        assertEquals(dueBefore, director.isHeartDue())
        // the preview did not start the heart's minimum gap: a guaranteed heart is not involved here,
        // and the random heart may still come right after it
        val hearts = (1..40).count { pet().also { scheduler.advance(3 * 60_000L) }.let(::isHeart) }
        assertTrue(hearts > 0)
    }

    @Test
    fun previewOfTheLeansAndTheirStateCanReplay() {
        for (name in listOf("inertia_left", "inertia_right", "inertia_forward", "inertia_back")) {
            director.preview(name)
            assertTrue(sm.startPreview())
            assertEquals(name, played.last().second)
        }
        oneShotMs = null
        director.preview("inertia_left")
        sm.startPreview()
        director.preview("inertia_left")
        sm.startPreview()                         // pressed twice: plays again
        assertEquals(listOf("inertia_left", "inertia_left"), played.takeLast(3).filter { it.first == CompanionState.PREVIEW }.map { it.second })
    }

    @Test
    fun previewIsRefusedDuringBraking() {
        oneShotMs = null
        sm.onAlertTriggered()
        director.preview("bayan")
        assertFalse(sm.startPreview())
        assertEquals(CompanionState.ALERT, state)
    }

    @Test
    fun aMissingPreviewFileFallsBackToThePlaceholder() {
        available = { it.resName != "no_such_animation" }
        director.preview("no_such_animation")
        sm.startPreview()
        assertEquals(CompanionState.PREVIEW, played.last().first)
        assertEquals("blink", played.last().second)
    }
}
