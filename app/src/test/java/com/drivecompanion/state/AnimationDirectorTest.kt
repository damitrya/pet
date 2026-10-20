package com.drivecompanion.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnimationDirectorTest {

    private val clock = FakeClock()
    private var ctx = PlayContext()

    private fun moodOf(energy: Float = 65f, affection: Float = 50f, curiosity: Float = 50f) =
        CharacterMood(MemoryMoodStore(StoredMood(MoodSnapshot(energy, affection, curiosity), clock.now)), clock)

    private fun director(mood: CharacterMood = moodOf(), seed: Int = 7) =
        AnimationDirector(mood, { ctx }, Random(seed), clock)

    private fun share(weights: Map<AnimationVariant, Double>, name: String): Double =
        weights.filterKeys { it.resName == name }.values.sum() / weights.values.sum()

    private fun calmWeights(d: AnimationDirector) =
        d.weightsFor(AnimationPools.create().getValue(CompanionState.CALM), FOX)

    @Test
    fun lowEnergyFavoursSleepyAnimations() {
        val tired = share(calmWeights(director(moodOf(energy = 10f))), "yawn")
        val rested = share(calmWeights(director(moodOf(energy = 95f))), "yawn")
        assertTrue("tired $tired vs rested $rested", tired > rested * 2)
    }

    @Test
    fun lowEnergyMakesActivePlayRarer() {
        val tired = share(calmWeights(director(moodOf(energy = 10f))), "shake")
        val rested = share(calmWeights(director(moodOf(energy = 95f))), "shake")
        assertTrue(rested > tired * 2)
    }

    @Test
    fun highCuriosityFavoursExploring() {
        val curious = share(calmWeights(director(moodOf(curiosity = 95f))), "listen")
        val bored = share(calmWeights(director(moodOf(curiosity = 5f))), "listen")
        assertTrue("curious $curious vs bored $bored", curious > bored * 2)
    }

    @Test
    fun highAffectionFavoursFriendlyReactions() {
        val tap = AnimationPools.create().getValue(CompanionState.TAP)
        val fond = share(director(moodOf(affection = 95f)).weightsFor(tap, FOX), "tease")
        val distant = share(director(moodOf(affection = 5f)).weightsFor(tap, FOX), "tease")
        assertTrue(fond > distant * 2)
        // ...and right after an interaction
        ctx = PlayContext(sinceInteractionMs = 10_000L)
        val justPlayed = share(director(moodOf(affection = 5f)).weightsFor(tap, FOX), "tease")
        assertTrue(justPlayed > distant)
    }

    @Test
    fun calmBaseKeepsItsShareWhateverTheMood() {
        for (m in listOf(moodOf(10f, 95f, 95f), moodOf(95f, 95f, 95f), moodOf(50f, 50f, 50f))) {
            val w = calmWeights(director(m))
            val base = w.filterKeys { AnimTag.BASE in it.tags }.values.sum() / w.values.sum()
            assertTrue("base share $base", base >= AnimationDirector.MIN_BASE_SHARE - 1e-9)
        }
    }

    @Test
    fun whileDrivingNoScenesOrActiveAnimations() {
        ctx = PlayContext(moving = true)
        val d = director(moodOf(energy = 95f, curiosity = 95f))
        repeat(2000) {
            clock.advance(2500)
            val v = d.pick(CompanionState.CALM, FOX)!!
            assertFalse(v.resName, AnimTag.SCENE in v.tags || AnimTag.ACTIVE in v.tags)
            val m = d.pick(CompanionState.MUSIC, FOX)!!
            assertNotEquals("music_spin", m.resName)
            val f = d.pick(CompanionState.FIDGET, FOX)!!
            assertFalse(f.resName, AnimTag.SCENE in f.tags || AnimTag.ACTIVE in f.tags)
        }
    }

    @Test
    fun rareScenesAreSpacedOutAndDoNotRepeat() {
        val d = director(moodOf(energy = 90f, affection = 90f, curiosity = 90f), seed = 11)
        val sceneTimes = mutableListOf<Long>()
        val byName = HashMap<String, MutableList<Long>>()
        var previous: String? = null
        val scenesSeen = HashSet<String>()
        // Two hours of a calm character, a new variant roughly every 2.5 s.
        repeat(2 * 3600 / 2) {
            clock.advance(2_000L + (it % 3) * 500L)
            val v = d.pick(CompanionState.CALM, FOX)!!
            byName.getOrPut(v.resName) { mutableListOf() } += clock.now
            if (AnimTag.SCENE in v.tags) {
                sceneTimes += clock.now
                scenesSeen += v.resName
            }
            if (AnimTag.BASE !in v.tags) assertNotEquals("${v.resName} twice in a row", previous, v.resName)
            previous = v.resName
        }
        sceneTimes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= AnimationDirector.SCENE_GAP_MS) }
        val pools = AnimationPools.create().getValue(CompanionState.CALM).variants
        for (v in pools.filter { it.minGapMs > 0 }) {
            byName[v.resName]?.zipWithNext()?.forEach { (a, b) -> assertTrue(v.resName, b - a >= v.minGapMs) }
        }
        assertTrue("scenes seen: $scenesSeen", scenesSeen.size >= 3)
        val total = byName.values.sumOf { it.size }
        val base = byName.filterKeys { it == "idle_small" || it == "idle2" }.values.sumOf { it.size }
        assertTrue("calm base share ${base.toDouble() / total}", base.toDouble() / total >= 0.5)
    }

    @Test
    fun recentlyPlayedVariantsLoseWeight() {
        val d = director()
        val pool = AnimationPools.create().getValue(CompanionState.CALM)
        val before = d.weightsFor(pool, FOX).entries.first { it.key.resName == "listen" }.value
        // Make the director play "listen" by offering only it.
        d.pick(CompanionState.CALM) { it.resName == "listen" }
        val after = d.weightsFor(pool, FOX).entries.first { it.key.resName == "listen" }.value
        assertTrue(after < before)
    }

    @Test
    fun slimePlaysOnlyItsOwnFilesAndNeverGetsStuck() {
        val d = director()
        repeat(500) {
            clock.advance(3000)
            val calm = d.pick(CompanionState.CALM, SLIME)!!
            assertTrue(calm.resName, calm.resName in SLIME_FILES)
            for (state in CompanionState.values()) {
                val v = d.pick(state, SLIME)
                assertNotNull("$state has nothing to play for the slime", v)
                assertTrue("$state → ${v!!.resName}", SLIME(v))
            }
        }
    }

    @Test
    fun everyStateHasSomethingForTheFox() {
        val d = director()
        for (state in CompanionState.values()) assertNotNull(state.name, d.pick(state, FOX))
    }

    @Test
    fun curlUpLeadsToCurledSleepAndWake() {
        val d = director(moodOf(energy = 5f))
        curledUp(d)
        assertEquals("sleep_curled", d.pick(CompanionState.SLEEP, FOX)!!.resName)
        assertEquals("wake_curled", d.pick(CompanionState.WAKE, FOX)!!.resName)
        // The sequence is over: the next wake-up is the usual one.
        assertEquals("wake", d.pick(CompanionState.WAKE, FOX)!!.resName)
    }

    @Test
    fun interruptedSequenceIsDropped() {
        val d = director(moodOf(energy = 5f))
        curledUp(d)
        d.pick(CompanionState.ALERT, FOX)          // braking while dozing off
        assertEquals("sleep", d.pick(CompanionState.SLEEP, FOX)!!.resName)
    }

    @Test
    fun sequenceFallsBackWhenTheCharacterChanges() {
        val d = director(moodOf(energy = 5f))
        curledUp(d)
        // Switched to the slime in the settings meanwhile: it has no sleep_curled.
        assertEquals("sleep", d.pick(CompanionState.SLEEP, SLIME)!!.resName)
        assertEquals("wake", d.pick(CompanionState.WAKE, SLIME)!!.resName)
    }

    @Test
    fun resetForgetsTheSequence() {
        val d = director(moodOf(energy = 5f))
        curledUp(d)
        d.reset()
        assertEquals("sleep", d.pick(CompanionState.SLEEP, FOX)!!.resName)
    }

    @Test
    fun curlUpCutShortDoesNotContinueTheSequence() {
        val d = director(moodOf(energy = 5f))
        pickUntil(d, CompanionState.FALL_ASLEEP, "curl_up")
        // Tapped while still lying down: the curled-up wake would start from a pose it never reached.
        assertEquals("wake", d.pick(CompanionState.WAKE, FOX)!!.resName)
        assertEquals("sleep", d.pick(CompanionState.SLEEP, FOX)!!.resName)
    }

    @Test
    fun finishedEventOfAnotherVariantDoesNotArmTheSequence() {
        val d = director(moodOf(energy = 5f))
        val curl = pickUntil(d, CompanionState.FALL_ASLEEP, "curl_up")
        d.pick(CompanionState.ALERT, FOX)
        d.onFinished(CompanionState.FALL_ASLEEP, curl)     // late callback of the interrupted animation
        assertEquals("sleep", d.pick(CompanionState.SLEEP, FOX)!!.resName)
    }

    @Test
    fun distractingSceneIsReportedForScenesAndActiveOnly() {
        val d = director()
        d.pick(CompanionState.CALM) { it.resName == "butterfly" }
        assertTrue(d.isDistractingSceneRunning())
        clock.advance(AnimationDirector.MAX_SCENE_MS)
        assertFalse(d.isDistractingSceneRunning())
        d.pick(CompanionState.CALM) { it.resName == "listen" }
        assertFalse("listening is calm", d.isDistractingSceneRunning())
        d.pick(CompanionState.FIDGET) { it.resName == "shake" }
        assertTrue(d.isDistractingSceneRunning())
        d.pick(CompanionState.TAP, FOX)
        assertFalse("reactions are not background scenes", d.isDistractingSceneRunning())
    }

    @Test
    fun sceneRunningUntilFinishedOrTimedOut() {
        val d = director()
        val scene = d.pick(CompanionState.CALM) { it.resName == "grooming" }!!
        assertTrue(d.isSceneRunning())
        d.onFinished(CompanionState.CALM, scene)
        assertFalse(d.isSceneRunning())

        clock.advance(AnimationDirector.SCENE_GAP_MS + 1)
        d.pick(CompanionState.CALM) { it.resName == "yawn" }
        assertTrue(d.isSceneRunning())
        clock.advance(AnimationDirector.MAX_SCENE_MS)
        assertFalse("a lost end callback must not block forever", d.isSceneRunning())

        d.pick(CompanionState.CALM) { it.resName == "idle_small" }
        assertFalse("basic calm is not a scene", d.isSceneRunning())
        d.pick(CompanionState.CALM) { it.resName == "listen" }
        d.pick(CompanionState.TAP, FOX)
        assertFalse("a reaction replaced the scene", d.isSceneRunning())
    }

    @Test
    fun exploringSatisfiesCuriosity() {
        val mood = moodOf(curiosity = 80f)
        val d = director(mood)
        d.pick(CompanionState.CALM) { it.resName == "listen" }
        assertTrue(mood.curiosity < 80f)
    }

    @Test
    fun sameSeedSameChoices() {
        fun run(): List<String> {
            val c = FakeClock()
            val d = AnimationDirector(
                CharacterMood(MemoryMoodStore(), c), { PlayContext() }, Random(42), c
            )
            return (1..200).map { c.advance(2500); d.pick(CompanionState.CALM, FOX)!!.resName }
        }
        assertEquals(run(), run())
    }

    private fun pickUntil(d: AnimationDirector, state: CompanionState, name: String): AnimationVariant {
        repeat(200) {
            val v = d.pick(state, FOX)!!
            if (v.resName == name) return v
        }
        error("$name never picked")
    }

    /** curl_up played to its last frame: the fox now lies curled up. */
    private fun curledUp(d: AnimationDirector) =
        d.onFinished(CompanionState.FALL_ASLEEP, pickUntil(d, CompanionState.FALL_ASLEEP, "curl_up"))

    companion object {
        val FOX: (AnimationVariant) -> Boolean = { true }

        /** Files the slime has (unprefixed originals in res/raw, checked by AnimationResourcesTest). */
        val SLIME_FILES = setOf("alert", "blink", "idle2", "idle_small", "music", "music2", "pet", "tap")

        /** CharacterView's rule: own file, else the fallback, else unavailable. */
        val SLIME: (AnimationVariant) -> Boolean = { it.resName in SLIME_FILES || it.fallback in SLIME_FILES }
    }
}
