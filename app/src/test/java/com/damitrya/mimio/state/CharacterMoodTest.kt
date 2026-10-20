package com.damitrya.mimio.state

import com.damitrya.mimio.data.PrefsMoodStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterMoodTest {

    private val clock = FakeClock()
    private val hour = 3_600_000L

    @Test
    fun startsNeutral() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        assertEquals(MoodSnapshot.NEUTRAL, mood.snapshot())
    }

    @Test
    fun interactionsRaiseAffectionGradually() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        var previous = mood.affection
        repeat(20) {
            clock.advance(10_000)
            mood.onEvent(MoodEvent.PET)
            val step = mood.affection - previous
            assertTrue("one petting moves affection by $step", step in 0f..5f)
            previous = mood.affection
        }
        assertTrue(mood.affection > 80f)
        assertTrue(mood.affection <= 100f)
        // Diminishing returns: the last pettings add less than the first one.
        val before = mood.affection
        mood.onEvent(MoodEvent.PET)
        assertTrue(mood.affection - before < 2.5f)
    }

    @Test
    fun longQuietReturnsToNeutralWithoutPunishment() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        repeat(30) { mood.onEvent(MoodEvent.PET) }
        assertTrue(mood.affection > 80f)
        clock.advance(48 * hour)
        val m = mood.snapshot()
        assertEquals(CharacterMood.NEUTRAL_AFFECTION, m.affection, 2f)
        // Being left alone never pushes affection below neutral.
        assertTrue(m.affection >= CharacterMood.NEUTRAL_AFFECTION - 0.01f)
    }

    @Test
    fun energyDrainsAwakeAndRecoversAsleep() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        clock.advance(3 * hour)
        val tired = mood.snapshot().energy
        assertTrue(tired < CharacterMood.NEUTRAL_ENERGY)
        assertTrue("never below the floor while just idling", tired >= 20f)
        clock.advance(20 * hour)
        assertTrue(mood.snapshot().energy >= 20f - 0.01f)
        mood.setAsleep(true)
        clock.advance(hour)
        assertTrue(mood.snapshot().energy > 50f)
    }

    @Test
    fun quietAwakeCharacterGetsCurious() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        clock.advance(2 * hour)
        assertTrue(mood.snapshot().curiosity > CharacterMood.NEUTRAL_CURIOSITY + 5f)
    }

    @Test
    fun tripStartWakesCuriosityAndEnergy() {
        val mood = CharacterMood(MemoryMoodStore(), clock)
        val before = mood.snapshot()
        mood.onEvent(MoodEvent.TRIP_START)
        assertTrue(mood.curiosity > before.curiosity)
        assertTrue(mood.energy > before.energy)
    }

    @Test
    fun savedAndRestoredBetweenLaunches() {
        val store = MemoryMoodStore()
        val mood = CharacterMood(store, clock)
        repeat(10) { mood.onEvent(MoodEvent.PET) }
        mood.onEvent(MoodEvent.TRIP_START)
        mood.flush()
        val saved = mood.snapshot()

        val restored = CharacterMood(store, clock)
        assertEquals(saved.affection, restored.affection, 0.01f)
        assertEquals(saved.curiosity, restored.curiosity, 0.01f)
        assertEquals(saved.energy, restored.energy, 0.01f)
    }

    @Test
    fun restoredAfterLongAbsenceIsCloserToNeutral() {
        val store = MemoryMoodStore(StoredMood(MoodSnapshot(10f, 95f, 90f), clock.now))
        clock.advance(24 * hour)
        val mood = CharacterMood(store, clock)
        assertTrue(mood.affection < 60f)
        assertTrue(mood.energy > 50f)
        assertTrue(mood.curiosity < 60f)
    }

    @Test
    fun preferencesStoreRoundTrip() {
        val prefs = InMemoryPrefs()
        val store = PrefsMoodStore(prefs)
        assertEquals(null, store.load())
        store.save(StoredMood(MoodSnapshot(12f, 34f, 56f), 789L))
        assertEquals(StoredMood(MoodSnapshot(12f, 34f, 56f), 789L), PrefsMoodStore(prefs).load())
    }

    @Test
    fun writesAreThrottled() {
        val store = MemoryMoodStore()
        val mood = CharacterMood(store, clock)
        // An hour of frequent taps and updates: at most one write per save interval.
        repeat(3600) {
            clock.advance(1000)
            if (it % 5 == 0) mood.onEvent(MoodEvent.TAP) else mood.advance()
        }
        val maxWrites = (hour / CharacterMood.SAVE_INTERVAL_MS).toInt()
        assertTrue("${store.saves} writes", store.saves in 1..maxWrites)
    }

    @Test
    fun flushWritesOnlyWhenChanged() {
        val store = MemoryMoodStore()
        val mood = CharacterMood(store, clock)
        mood.onEvent(MoodEvent.PET)
        mood.flush()
        assertEquals(1, store.saves)
        mood.flush()
        assertEquals(1, store.saves)
    }

    @Test
    fun valuesStayInRange() {
        val mood = CharacterMood(MemoryMoodStore(StoredMood(MoodSnapshot(250f, -40f, 1000f), clock.now)), clock)
        repeat(500) {
            mood.onEvent(MoodEvent.values()[it % MoodEvent.values().size])
            mood.onPlayed(setOf(AnimTag.ACTIVE, AnimTag.EXPLORE))
        }
        val m = mood.snapshot()
        for (v in listOf(m.energy, m.affection, m.curiosity)) assertTrue("$v", v in 0f..100f)
    }
}
