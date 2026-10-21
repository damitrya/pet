package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.PhoneCallData
import com.damitrya.mimio.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Wave 6: activity modes, the time-based [ActivityScheduler] and its place in the director and the state machine. */
class Wave6Test {

    private val calm = CompanionActivityMode.CALM
    private val lively = CompanionActivityMode.LIVELY
    private val playful = CompanionActivityMode.PLAYFUL

    private val energetic = MoodSnapshot(95f, 50f, 50f)
    private val tired = MoodSnapshot(10f, 50f, 50f)

    // ---- 1, 2: the setting

    @Test
    fun livelyIsTheDefault() {
        assertEquals(lively, CompanionActivityMode.DEFAULT)
        assertEquals("a fresh install / an update without the key", lively, SettingsRepository(InMemoryPrefs()).activityMode)
        val prefs = InMemoryPrefs().apply { values[SettingsRepository.KEY_ACTIVITY_MODE] = "SOMETHING_OLD" }
        assertEquals("an unknown value", lively, SettingsRepository(prefs).activityMode)
    }

    @Test
    fun theModeIsSavedAndChanged() {
        val prefs = InMemoryPrefs()
        SettingsRepository(prefs).activityMode = playful
        assertEquals("PLAYFUL", prefs.values[SettingsRepository.KEY_ACTIVITY_MODE])
        assertEquals(playful, SettingsRepository(prefs).activityMode)
        SettingsRepository(prefs).activityMode = calm
        assertEquals(calm, SettingsRepository(prefs).activityMode)
    }

    @Test
    fun aNewModeAppliesToTheRunningCharacterAtOnce() {
        val s = ActivitySession(playful, seed = 4)
        s.runWithUser(20 * MIN)
        val switchAt = s.clock.now
        s.settings.activityMode = calm                  // what SettingsActivity does; no restart, no reset
        s.runWithUser(20 * MIN)
        val before = s.calmPicks().count { it.category != ActivityCategory.AMBIENT } - s.calmPicks(switchAt).count { it.category != ActivityCategory.AMBIENT }
        val after = s.calmPicks(switchAt).count { it.category != ActivityCategory.AMBIENT }
        assertTrue("playful $before vs calm $after", before > after * 2)
        assertTrue(s.activityLog.toString(), s.activityLog.any { it == "mode PLAYFUL → CALM" })
        assertEquals(calm, s.director.activityStats().mode)
    }

    // ---- 3: the three modes

    @Test
    fun theModesDifferInFrequency() {
        val stats = listOf(calm, lively, playful).map { mode ->
            ActivitySession(mode, seed = 5).apply { runWithUser(60 * MIN) }.director.activityStats()
        }
        val (c, l, p) = stats
        assertTrue("micro $stats", c.micro < l.micro && l.micro < p.micro)
        assertTrue("scenes $stats", c.scenes < l.scenes && l.scenes < p.scenes)
        assertTrue("active share $stats", c.activeShare < l.activeShare && l.activeShare < p.activeShare)
    }

    @Test
    fun windowsStayInsideTheModeWhateverTheMood() {
        for (mode in CompanionActivityMode.values()) for (m in listOf(energetic, tired, MoodSnapshot(100f, 100f, 100f), MoodSnapshot(0f, 0f, 0f))) {
            val a = ActivityScheduler({ mode }, Random(3))
            repeat(500) {
                assertTrue(a.interval(mode.microMinMs, mode.microMaxMs, m) in mode.microMinMs..mode.microMaxMs)
                assertTrue(a.interval(mode.sceneMinMs, mode.sceneMaxMs, m) in mode.sceneMinMs..mode.sceneMaxMs)
            }
        }
    }

    // ---- 4: categories

    @Test
    fun variantsFallIntoTheirCategory() {
        val calmPool = AnimationPools.create().getValue(CompanionState.CALM).variants.associateBy { it.resName }
        assertEquals(ActivityCategory.AMBIENT, calmPool.getValue("idle_small").category)
        assertEquals(ActivityCategory.AMBIENT, calmPool.getValue("idle2").category)
        for (name in listOf("tail_wag", "ear_scratch", "head_tilt", "stretch", "listen", "sneeze", "yawn")) {
            assertEquals(name, ActivityCategory.MICRO, calmPool.getValue(name).category)
        }
        for (name in listOf("butterfly", "tail_play", "tail_hide", "tail_back", "grooming")) {
            assertEquals(name, ActivityCategory.SCENE, calmPool.getValue(name).category)
        }
        assertTrue("no music in CALM", calmPool.keys.none { it.startsWith("music") || it in AnimationPools.MUSIC_SHOWS_WAVE5 })
        for (name in AnimationPools.INITIATIVE) assertTrue(name, name in calmPool)
    }

    @Test
    fun theSchedulerOpensItsWindowsByTime() {
        val mode = lively
        val a = ActivityScheduler({ mode }, Random(1))
        val m = MoodSnapshot.NEUTRAL
        val ctx = PlayContext(sinceInteractionMs = 0L)
        var now = 1_000_000L
        assertEquals(ActivityCategory.AMBIENT, a.decide(now, ctx, m).category)
        now += mode.microMaxMs
        val micro = a.decide(now, ctx, m)
        assertEquals(ActivityCategory.MICRO, micro.category)
        a.onPicked("tail_wag", ActivityCategory.MICRO, micro, now, ctx, m)
        assertEquals("the window was used", ActivityCategory.AMBIENT, a.decide(now + 1_000, ctx, m).category)
        now += mode.sceneMaxMs
        val scene = a.decide(now, ctx, m)
        assertEquals(ActivityCategory.SCENE, scene.category)
        a.onPicked("grooming", ActivityCategory.SCENE, scene, now, ctx, m)
        repeat(ActivityScheduler.CALM_PICKS_AFTER_SCENE) {
            assertEquals("calm after a scene", ActivityCategory.AMBIENT, a.decide(now + 60 * MIN, ctx, m).category)
        }
        assertNotEquals(ActivityCategory.AMBIENT, a.decide(now + 60 * MIN, ctx, m).category)
    }

    // ---- 5: mood

    @Test
    fun energyMakesTheFoxLivelierWithinItsMode() {
        val e = ActivitySession(lively, energetic, seed = 6).apply { runWithUser(30 * MIN) }.director.activityStats()
        val t = ActivitySession(lively, tired, seed = 6).apply { runWithUser(30 * MIN) }.director.activityStats()
        assertTrue("energetic $e vs tired $t", e.micro + e.scenes > (t.micro + t.scenes) * 1.1)
        // ...but the mode stays the main control: a tired playful fox is livelier than an energetic calm one.
        val tiredPlayful = ActivitySession(playful, tired, seed = 6).apply { runWithUser(30 * MIN) }.director.activityStats()
        val energeticCalm = ActivitySession(calm, energetic, seed = 6).apply { runWithUser(30 * MIN) }.director.activityStats()
        assertTrue(tiredPlayful.micro + tiredPlayful.scenes > (energeticCalm.micro + energeticCalm.scenes) * 1.5)
    }

    @Test
    fun theMoodChoosesWhatTheFoxDoes() {
        fun share(mood: MoodSnapshot, names: Set<String>): Double {
            val picks = ActivitySession(lively, mood, seed = 8).apply { runWithUser(60 * MIN) }
                .calmPicks().filter { it.category != ActivityCategory.AMBIENT }
            return picks.count { it.resName in names }.toDouble() / picks.size
        }
        val sleepy = setOf("yawn", "stretch")
        assertTrue("tired yawn and stretch", share(tired, sleepy) > share(energetic, sleepy) * 1.8)
        val active = setOf("shake", "surprise", "tail_play", "butterfly")
        assertTrue("energetic play", share(energetic, active) > share(tired, active) * 1.8)
        val explore = setOf("listen", "head_tilt", "confused", "butterfly")
        assertTrue("curious looking around", share(MoodSnapshot(65f, 50f, 95f), explore) > share(MoodSnapshot(65f, 50f, 5f), explore) * 1.4)
        val friendly = setOf("tail_wag", "paw_wave", "tail_hide", "tail_back")
        assertTrue("fond and friendly", share(MoodSnapshot(65f, 95f, 50f), friendly) > share(MoodSnapshot(65f, 5f, 50f), friendly) * 1.4)
    }

    @Test
    fun smallMovementsDoNotUseUpTheMood() {
        val s = ActivitySession(playful, MoodSnapshot(80f, 50f, 60f), seed = 9)
        s.runWithUser(60 * MIN)
        // Awake drain alone is 8 energy points an hour; play adds some, but the fox is not exhausted.
        assertTrue("energy ${s.mood.energy}", s.mood.energy > 40f)
        assertTrue("curiosity ${s.mood.curiosity}", s.mood.curiosity > 25f)
    }

    // ---- 6, 7: repeats and the limits of scenes

    @Test
    fun noSmallMovementTwiceInARowAndNoPingPong() {
        val s = ActivitySession(playful, energetic, seed = 10)
        s.runWithUser(120 * MIN)
        val actions = s.calmPicks().filter { it.category != ActivityCategory.AMBIENT }.map { it.resName }
        val micros = s.calmPicks().filter { it.category == ActivityCategory.MICRO }.map { it.resName }
        micros.zipWithNext().forEach { (a, b) -> assertNotEquals("$a twice in a row", a, b) }
        actions.windowed(6).forEach { w -> assertTrue("ping-pong $w", w.toSet().size >= 3) }
        val top = actions.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
        assertTrue("$top of ${actions.size}", top.value < actions.size * 0.25)
    }

    @Test
    fun sceneLimitsAreKept() {
        for (mode in CompanionActivityMode.values()) {
            val s = ActivitySession(mode, energetic, seed = 11)
            s.runWithUser(120 * MIN)
            val pool = AnimationPools.create().getValue(CompanionState.CALM).variants
            val calmPlays = s.played.filter { it.second == CompanionState.CALM }
            for (v in pool.filter { it.minGapMs > 0 }) {
                calmPlays.filter { it.third == v.resName }.map { it.first }.zipWithNext()
                    .forEach { (a, b) -> assertTrue("$mode ${v.resName}", b - a >= v.minGapMs) }
            }
            val sceneTimes = calmPlays.filter { it.third in pool.filter { v -> v.category == ActivityCategory.SCENE }.map { v -> v.resName } }.map { it.first }
            sceneTimes.zipWithNext().forEach { (a, b) -> assertTrue("$mode scenes ${b - a} ms apart", b - a >= mode.sceneGapMs) }
            // Never two scenes without basic calm in between.
            val cats = s.calmPicks().map { it.category }
            cats.forEachIndexed { i, c ->
                if (c == ActivityCategory.SCENE) {
                    val next = cats.drop(i + 1).take(ActivityScheduler.CALM_PICKS_AFTER_SCENE)
                    assertTrue("$mode $next", next.all { it == ActivityCategory.AMBIENT })
                }
            }
        }
    }

    @Test
    fun anUnavailableSceneLeavesAnAlternativeNotABrokenCooldown() {
        // Playful and energetic: scene windows come faster than the scenes' own minimum gaps allow.
        val s = ActivitySession(playful, energetic, seed = 12)
        s.runWithUser(60 * MIN)
        val st = s.director.activityStats()
        assertTrue("$st", st.skippedScenes > 0)
        assertTrue(s.activityLog.any { it.startsWith("scene skipped") })
        assertTrue("a skipped scene becomes a small movement", s.activityLog.any { "wanted=SCENE played=MICRO" in it })
    }

    // ---- 8, 9: priorities and interruptions

    /** Runs [s] until a self-started scene is on screen in CALM; returns its name. */
    private fun untilScene(s: ActivitySession): String {
        repeat(20_000) {
            val last = s.played.lastOrNull()
            if (s.state == CompanionState.CALM && last != null && last.second == CompanionState.CALM &&
                AnimationPools.forState(CompanionState.CALM).variants.first { it.resName == last.third }.category == ActivityCategory.SCENE
            ) return last.third
            s.run(100)
        }
        error("no scene")
    }

    @Test
    fun userAndSystemReactionsStillComeFirst() {
        val s = ActivitySession(playful, energetic, seed = 13)
        untilScene(s)
        s.sm.onTap()
        assertEquals("a tap interrupts a scene at once", CompanionState.TAP, s.state)
        s.run(5_000)
        untilScene(s)
        s.sm.onLongPress()
        assertEquals(CompanionState.PET, s.state)
        s.sm.onLongPressReleased()
        assertTrue(s.state == CompanionState.HEARTS || s.state == CompanionState.SHY)
        s.run(10_000)
        untilScene(s)
        s.sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, s.state)
        s.run(10_000)
        untilScene(s)
        s.sm.updatePhoneCallData(PhoneCallData(isInCall = true))
        assertEquals(CompanionState.PHONE_CALL, s.state)
        s.sm.updatePhoneCallData(PhoneCallData(isInCall = false))
        untilScene(s)
        s.sm.updateMediaData(MediaData(isPlaying = true))
        assertEquals(CompanionState.MUSIC, s.state)
    }

    @Test
    fun anInterruptedSceneLeavesNothingBehind() {
        val clock = FakeClock()
        val mood = CharacterMood(MemoryMoodStore(StoredMood(energetic, clock.now)), clock)
        val d = AnimationDirector(mood, { PlayContext() }, Random(14), clock, activityMode = { playful })
        val scene = d.pick(CompanionState.CALM) { it.resName == "grooming" }!!
        clock.advance(1_000)
        d.pick(CompanionState.TAP, AnimationDirectorTest.FOX)         // the user taps during the scene
        d.onFinished(CompanionState.CALM, scene)                      // a late end of the interrupted scene
        assertFalse(d.isSceneRunning())
        // Back in CALM: no scene straight away, the fox first returns to calm.
        val after = (1..6).map { clock.advance(2_400); d.pick(CompanionState.CALM, AnimationDirectorTest.FOX)!! }
        assertTrue("$after", after.none { it.category == ActivityCategory.SCENE })
    }

    // ---- 10: the car

    @Test
    fun noDistractingScenesWhileDrivingEvenWhenPlayful() {
        val s = ActivitySession(playful, MoodSnapshot(95f, 95f, 95f), seed = 15,
            ctx = PlayContext(moving = true, car = true, trip = TripPhase.DRIVING))
        s.runWithUser(30 * MIN)
        val picks = s.calmPicks()
        assertTrue(picks.none { AnimTag.SCENE in it.tags || AnimTag.ACTIVE in it.tags })
        val st = s.director.activityStats()
        assertTrue("scene windows are skipped, not saved: $st", st.skippedScenes > 0 && st.scenes == 0)
        assertEquals("no initiative while driving", 0, st.initiatives)
        // Small movements follow the calm mode while driving.
        val parked = ActivitySession(calm, MoodSnapshot(95f, 95f, 95f), seed = 15, ctx = PlayContext(car = true, parked = true))
        parked.runWithUser(30 * MIN)
        assertTrue("$st vs ${parked.director.activityStats()}", st.micro <= parked.director.activityStats().micro * 1.3)
    }

    @Test
    fun aParkedCarAllowsTheUsualCalmBehaviour() {
        val s = ActivitySession(lively, seed = 16, ctx = PlayContext(car = true, parked = true, trip = TripPhase.PARKED))
        s.runWithUser(60 * MIN)
        assertTrue(s.director.activityStats().scenes > 10)
        assertTrue(s.calmPicks().none { it.resName == "driving" })
    }

    // ---- 11: sleep

    @Test
    fun sleepComesLaterInLivelierModesAndIsNeverInterruptedForInitiative() {
        for ((mode, sleepAt) in listOf(calm to 3 * MIN, lively to 5 * MIN, playful to 8 * MIN)) {
            val s = ActivitySession(mode, seed = 17)
            s.run(sleepAt - 30_000L)
            assertFalse("$mode is still awake", s.played.any { it.second == CompanionState.FALL_ASLEEP })
            s.run(2 * MIN)
            assertEquals(mode.name, CompanionState.SLEEP, s.state)
            val asleepAt = s.played.last { it.second == CompanionState.SLEEP }.first
            s.run(30 * MIN)                                              // a long time alone
            assertEquals("not woken up for initiative", CompanionState.SLEEP, s.state)
            assertTrue(s.played.none { it.first > asleepAt && it.second == CompanionState.CALM })
        }
    }

    // ---- 12: no backlog

    @Test
    fun missedWindowsDoNotPileUp() {
        val s = ActivitySession(playful, energetic, seed = 18)
        s.run(30_000)
        val before = s.director.activityStats()
        s.sm.updateMediaData(MediaData(isPlaying = true))                // 20 minutes of music: no CALM picks
        s.run(20 * MIN)
        val during = s.director.activityStats()
        assertEquals("windows are not counted, saved or skipped meanwhile", before.scenes + before.skippedScenes, during.scenes + during.skippedScenes)
        s.sm.updateMediaData(MediaData(isPlaying = false))
        val back = s.clock.now
        s.run(2 * MIN)
        val scenes = s.calmPicks(back).filter { it.category == ActivityCategory.SCENE }
        assertTrue("no burst of saved scenes: $scenes", scenes.size <= 3)
        val first = s.played.firstOrNull { it.first >= back && it.second == CompanionState.CALM &&
            AnimationPools.forState(CompanionState.CALM).variants.first { v -> v.resName == it.third }.category == ActivityCategory.SCENE }
        assertTrue("calm first after the music", first == null || first.first - back >= ActivityScheduler.AFTER_REACTION_SCENE_MS)
    }

    @Test
    fun aHiddenOverlayStartsWithFreshWindows() {
        val clock = FakeClock()
        val d = AnimationDirector(CharacterMood(MemoryMoodStore(), clock), { PlayContext() }, Random(19), clock)
        repeat(20) { clock.advance(2_400); d.pick(CompanionState.CALM, AnimationDirectorTest.FOX) }
        clock.advance(60 * MIN)                                        // hidden: nothing is picked
        d.reset()                                                       // shown again (CompanionService.setInitialState)
        val first = (1..2).map { clock.advance(1_000); d.pick(CompanionState.CALM, AnimationDirectorTest.FOX)!! }
        assertTrue("$first", first.all { it.category == ActivityCategory.AMBIENT })
    }

    // ---- 13: timers

    @Test
    fun theSchedulerAddsNoTimersAndStopCancelsAll() {
        val s = ActivitySession(playful, energetic, seed = 20)
        var maxPending = 0
        repeat(30 * 60) {
            s.run(1_000)
            maxPending = maxOf(maxPending, s.scheduler.pending())
        }
        // Blink, one-shot watchdog and the view's end: the activity plan itself never posts anything.
        assertTrue("pending tasks $maxPending", maxPending <= 3)
        s.sm.destroy()
        s.played.clear()
        s.scheduler.advance(10 * MIN)
        assertTrue("the machine starts nothing after stop: ${s.played.take(3)}",
            s.played.none { it.second != CompanionState.CALM })
    }

    // ---- 14: variety over a long run, and the 60-minute sessions

    @Test
    fun varietyHoldsOverALongRun() {
        val s = ActivitySession(lively, seed = 21)
        s.runWithUser(180 * MIN)
        val picks = s.calmPicks()
        val actions = picks.filter { it.category != ActivityCategory.AMBIENT }.groupingBy { it.resName }.eachCount()
        assertTrue("$actions", actions.size >= 14)
        assertTrue("$actions", actions.values.max() < actions.values.sum() * 0.25)
        val ambient = picks.count { it.category == ActivityCategory.AMBIENT }.toDouble() / picks.size
        assertTrue("a good share of calm stays: $ambient", ambient in 0.5..0.85)
        val idleSmall = picks.count { it.resName == "idle_small" }.toDouble() / picks.size
        assertTrue("idle_small no longer dominates: $idleSmall", idleSmall < 0.5)
    }

    @Test
    fun aVirtualHourInEveryMode() {
        // An hour of ordinary phone use: the user taps the fox every few minutes, so it stays awake.
        for (mode in CompanionActivityMode.values()) {
            val s = ActivitySession(mode, seed = 22)
            s.runWithUser(60 * MIN)
            val st = s.director.activityStats()
            val picks = s.calmPicks()
            val names = picks.filter { it.category != ActivityCategory.AMBIENT }.groupingBy { it.resName }.eachCount()
            println("$mode: ${picks.size} CALM picks, ${st.ambient} basic, ${st.micro} small movements, ${st.scenes} scenes " +
                "(${st.skippedScenes} skipped), ${st.initiatives} initiatives, active ${(st.activeShare * 100).toInt()}%; " +
                names.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
            assertEquals(picks.size, st.ambient + st.micro + st.scenes)
            // The rhythm the user chose, give or take the waits for a pick and the reactions in between.
            val perScene = 60 * MIN / st.scenes
            assertTrue("$mode: a scene every ${perScene / 1000} s", perScene in mode.sceneMinMs..(mode.sceneMaxMs * 13 / 10))
            val perMicro = 60 * MIN / st.micro
            assertTrue("$mode: a small movement every ${perMicro / 1000.0} s", perMicro in mode.microMinMs..(mode.microMaxMs * 13 / 10))
            assertTrue("$mode: never stuck", picks.size > 400)
        }
    }

    @Test
    fun aLongHoldMusicAndProfileSwitchesKeepEverythingIntact() {
        val s = ActivitySession(lively, seed = 23)
        s.run(MIN)
        s.sm.onLongPress()
        s.run(2 * MIN)                                                   // a very long petting
        assertEquals(CompanionState.PET, s.state)
        s.sm.onLongPressReleased()
        s.run(5_000)
        assertEquals(CompanionState.CALM, s.state)
        s.sm.updateMediaData(MediaData(isPlaying = true))
        s.run(5 * MIN)
        assertTrue("music shows stay in MUSIC", s.played.filter { it.second == CompanionState.CALM }.none { it.third.startsWith("music") })
        s.sm.updateMediaData(MediaData(isPlaying = false))
        val moodBefore = s.mood.snapshot()
        val statsBefore = s.director.activityStats()
        s.director.reset()                                               // what the service does on a profile switch
        s.sm.setProfile(AppProfile.AUTO)
        s.director.reset()
        s.sm.setProfile(AppProfile.DAILY)
        assertEquals("the mood is kept", moodBefore, s.mood.snapshot())
        assertEquals("the session stats are kept", statsBefore.micro, s.director.activityStats().micro)
        s.runWithUser(20 * MIN)
        assertTrue(s.director.activityStats().scenes > statsBefore.scenes)
    }

    @Test
    fun initiativeOnlyAfterALongTimeAlone() {
        val s = ActivitySession(playful, MoodSnapshot(80f, 90f, 60f), seed = 24)
        s.runWithUser(60 * MIN, tapEveryMs = 30_000L)                   // a user who keeps playing with the fox
        assertEquals(0, s.director.activityStats().initiatives)
        val alone = ActivitySession(playful, MoodSnapshot(80f, 90f, 60f), seed = 24)
        alone.runWithUser(60 * MIN, tapEveryMs = 6 * MIN)
        val st = alone.director.activityStats()
        assertTrue("$st", st.initiatives >= 3)
        val initiatives = alone.activityLog.filter { "(initiative)" in it }
        assertTrue(initiatives.toString(), initiatives.all { line -> AnimationPools.INITIATIVE.any { " $it " in "$line " } })
    }

    @Test
    fun statisticsHoldNoPersonalData() {
        val s = ActivitySession(lively, seed = 25)
        s.runWithUser(10 * MIN)
        val st = s.director.activityStats()
        assertTrue(st.sessionMs >= 10 * MIN)
        assertTrue(st.activeMs > 0 && st.calmMs > st.activeMs)
        // Only counters, durations and the mode: nothing else can be in it.
        assertEquals(
            setOf("mode", "ambient", "micro", "scenes", "skippedScenes", "initiatives", "activeMs", "calmMs", "sessionMs", "nextMicroInMs", "nextSceneInMs"),
            ActivityStats::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        )
    }

    companion object {
        private const val MIN = 60_000L
    }
}
