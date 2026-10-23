package com.damitrya.mimio.state

import kotlin.math.exp
import kotlin.math.min

/** Mood values, each 0–100. */
data class MoodSnapshot(val energy: Float, val affection: Float, val curiosity: Float) {
    companion object {
        val NEUTRAL = MoodSnapshot(CharacterMood.NEUTRAL_ENERGY, CharacterMood.NEUTRAL_AFFECTION, CharacterMood.NEUTRAL_CURIOSITY)
    }
}

/** Persisted mood: values and when they were saved. */
data class StoredMood(val mood: MoodSnapshot, val savedAt: Long)

/** Where [CharacterMood] keeps its state between app launches. */
interface MoodStore {
    fun load(): StoredMood?
    fun save(stored: StoredMood)
}

/** Things that happen to the character and nudge its mood. */
enum class MoodEvent { TAP, PET, GREETING, TRIP_START, MUSIC_START, ARRIVAL }

/**
 * The character's mood: energy, affection and curiosity (0–100). Values change gradually:
 * every event moves them by a few points with diminishing returns, and over time they drift
 * back to neutral, so the character never depends on constant attention — there is nothing
 * to "lose" by leaving it alone. Energy drains slowly while awake and recovers while asleep.
 *
 * Saved to [store] at most every [SAVE_INTERVAL_MS] (and on [flush]); after a long absence the
 * restored mood has relaxed toward neutral by the time that passed.
 *
 * Not thread-safe: use from the main thread.
 */
class CharacterMood(
    private val store: MoodStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    var energy: Float = NEUTRAL_ENERGY
        private set
    var affection: Float = NEUTRAL_AFFECTION
        private set
    var curiosity: Float = NEUTRAL_CURIOSITY
        private set

    /** True while the character sleeps: energy recovers instead of draining. */
    var asleep: Boolean = false
        private set

    private var updatedAt: Long = clock()
    private var savedAt: Long = updatedAt
    private var saved: MoodSnapshot = MoodSnapshot.NEUTRAL

    init {
        store.load()?.let { stored ->
            energy = stored.mood.energy.coerceIn(0f, 100f)
            affection = stored.mood.affection.coerceIn(0f, 100f)
            curiosity = stored.mood.curiosity.coerceIn(0f, 100f)
            saved = snapshotNow()
            // Away from the app: everything relaxes toward neutral, as if it had a rest.
            val away = (updatedAt - stored.savedAt).coerceAtLeast(0L)
            energy = relax(energy, NEUTRAL_ENERGY, away, ABSENCE_HALF_LIFE_MS)
            affection = relax(affection, NEUTRAL_AFFECTION, away, ABSENCE_HALF_LIFE_MS)
            curiosity = relax(curiosity, NEUTRAL_CURIOSITY, away, ABSENCE_HALF_LIFE_MS)
        }
    }

    fun snapshot(): MoodSnapshot {
        advance()
        return snapshotNow()
    }

    /** Applies the time-based drift up to now and saves if due. Cheap; call as often as convenient. */
    fun advance(now: Long = clock()) {
        val dt = now - updatedAt
        if (dt <= 0L) return
        updatedAt = now
        val hours = dt / HOUR_MS.toFloat()
        energy = if (asleep) {
            min(100f, energy + SLEEP_RECOVERY_PER_HOUR * hours)
        } else {
            (energy - AWAKE_DRAIN_PER_HOUR * hours).coerceAtLeast(min(energy, ENERGY_FLOOR))
        }
        affection = relax(affection, NEUTRAL_AFFECTION, dt, AFFECTION_HALF_LIFE_MS)
        // Left alone and awake, the character slowly gets curious about its surroundings.
        curiosity = relax(curiosity, if (asleep) NEUTRAL_CURIOSITY else IDLE_CURIOSITY, dt, CURIOSITY_HALF_LIFE_MS)
        saveIfDue(now)
    }

    fun onEvent(event: MoodEvent) {
        advance()
        when (event) {
            MoodEvent.TAP -> { affection = gain(affection, 1.5f); curiosity = gain(curiosity, 0.5f) }
            MoodEvent.PET -> { affection = gain(affection, 5f); energy = gain(energy, 1f) }
            MoodEvent.GREETING -> affection = gain(affection, 1f)
            MoodEvent.TRIP_START -> { curiosity = gain(curiosity, 8f); energy = gain(energy, 5f) }
            MoodEvent.MUSIC_START -> energy = gain(energy, 4f)
            MoodEvent.ARRIVAL -> { affection = gain(affection, 3f); energy = gain(energy, 2f) }
        }
        saveIfDue(updatedAt)
    }

    /**
     * An animation just played: exploring satisfies curiosity, active play costs some energy. [share] scales the
     * effect: the many small self-started movements of wave 6 count only a little, so that a lively character
     * does not use up its mood within minutes.
     */
    fun onPlayed(tags: Set<AnimTag>, share: Float = 1f) {
        if (tags.isEmpty() || share <= 0f) return
        advance()
        if (AnimTag.EXPLORE in tags) curiosity = (curiosity - EXPLORE_SATISFIES * share).coerceAtLeast(0f)
        if (AnimTag.ACTIVE in tags) energy = (energy - ACTIVE_COST * share).coerceAtLeast(0f)
    }

    fun setAsleep(value: Boolean) {
        if (value == asleep) return
        advance()
        asleep = value
    }

    /** Saves now if anything changed since the last save (service stop, overlay hidden). */
    fun flush() {
        advance()
        if (snapshotNow() != saved) save(updatedAt)
    }

    private fun saveIfDue(now: Long) {
        if (now - savedAt < SAVE_INTERVAL_MS) return
        val current = snapshotNow()
        if (current.differsFrom(saved)) save(now) else savedAt = now
    }

    private fun save(now: Long) {
        saved = snapshotNow()
        savedAt = now
        store.save(StoredMood(saved, now))
    }

    private fun snapshotNow() = MoodSnapshot(energy, affection, curiosity)

    private fun MoodSnapshot.differsFrom(other: MoodSnapshot) =
        kotlin.math.abs(energy - other.energy) >= SAVE_MIN_CHANGE ||
            kotlin.math.abs(affection - other.affection) >= SAVE_MIN_CHANGE ||
            kotlin.math.abs(curiosity - other.curiosity) >= SAVE_MIN_CHANGE

    companion object {
        const val NEUTRAL_ENERGY = 65f
        const val NEUTRAL_AFFECTION = 50f
        const val NEUTRAL_CURIOSITY = 50f

        private const val HOUR_MS = 3_600_000L

        /** Awake energy drain and its floor; sleeping recovers it. */
        private const val AWAKE_DRAIN_PER_HOUR = 8f
        private const val ENERGY_FLOOR = 20f
        private const val SLEEP_RECOVERY_PER_HOUR = 40f

        /** Curiosity a quiet, awake character drifts toward. */
        private const val IDLE_CURIOSITY = 65f

        private const val AFFECTION_HALF_LIFE_MS = 6 * HOUR_MS
        private const val CURIOSITY_HALF_LIFE_MS = HOUR_MS
        private const val ABSENCE_HALF_LIFE_MS = 8 * HOUR_MS

        private const val EXPLORE_SATISFIES = 3f
        private const val ACTIVE_COST = 1.5f

        /** SharedPreferences writes: at most this often, and only for a noticeable change. */
        const val SAVE_INTERVAL_MS = 5 * 60_000L
        private const val SAVE_MIN_CHANGE = 1f

        /** Moves [value] toward [target] with the given half-life. */
        internal fun relax(value: Float, target: Float, dtMs: Long, halfLifeMs: Long): Float {
            if (dtMs <= 0L) return value
            val k = exp(-dtMs * LN2 / halfLifeMs).toFloat()
            return target + (value - target) * k
        }

        /** Adds [amount] with diminishing returns: the closer to 100, the smaller the step. */
        internal fun gain(value: Float, amount: Float): Float =
            (value + amount * (1f - value / 100f)).coerceIn(0f, 100f)

        private const val LN2 = 0.6931471805599453
    }
}
