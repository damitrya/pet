package com.drivecompanion.state

import kotlin.random.Random

/** What is going on around the character when an animation is chosen. */
data class PlayContext(
    /** The car is confirmed moving (fresh GPS speed): no eye-catching scenes. */
    val moving: Boolean = false,
    val music: Boolean = false,
    /** Time since the user last tapped or petted the character. */
    val sinceInteractionMs: Long = Long.MAX_VALUE
)

/**
 * Chooses which file each state plays, on top of [AnimationPools]:
 *
 * - **mood** ([CharacterMood]): low energy favours [AnimTag.SLEEPY], high curiosity [AnimTag.EXPLORE],
 *   high affection [AnimTag.FRIENDLY], and active play needs energy;
 * - **context**: while the car moves, [AnimTag.SCENE] and [AnimTag.ACTIVE] variants are not played and
 *   other extras are toned down; right after an interaction friendly variants are likelier;
 * - **variety**: variants played recently lose weight, [AnimationVariant.minGapMs] spaces rare ones out,
 *   and self-started scenes are at least [SCENE_GAP_MS] apart;
 * - **calm first**: in pools with [AnimTag.BASE] variants those keep at least [MIN_BASE_SHARE] of the chance;
 * - **sequences**: a variant's [AnimationVariant.next] decides what the following states play
 *   (curl_up → sleep_curled → wake_curled), as long as the character has those files.
 *
 * The director only picks files — it never changes the state, so it cannot interrupt ALERT, PET or any
 * other reaction; [isSceneRunning] lets the state machine postpone its own idle one-shots (blink, dozing
 * off) until a scene has finished. Randomness and time are injected for tests.
 */
class AnimationDirector(
    private val mood: CharacterMood,
    private val context: () -> PlayContext = { PlayContext() },
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val pools: Map<CompanionState, AnimationPool> = AnimationPools.create(random)

    /** Last picks (newest last), for the repetition penalty. */
    private val history = ArrayDeque<String>()

    private val lastPlayedAt = HashMap<String, Long>()
    private var lastSceneAt: Long = Long.MIN_VALUE / 2

    /** Follow-up files requested by the variant that started a sequence. */
    private val pendingNext = HashMap<CompanionState, String>()

    private var current: AnimationVariant? = null
    private var currentState: CompanionState? = null
    private var currentStartedAt: Long = 0L

    /**
     * Picks the variant [state] plays next, or null if the character has none.
     * [isAvailable] tells whether the current character can play a variant (own file or fallback).
     */
    fun pick(state: CompanionState, isAvailable: (AnimationVariant) -> Boolean): AnimationVariant? {
        val now = clock()
        if (state !in pendingNext) pendingNext.clear()
        pendingNext.remove(state)?.let { name ->
            val follow = AnimationVariant(name)
            if (isAvailable(follow)) return played(state, follow, now)
            pendingNext.clear()       // the character lacks the sequence: back to the normal pools
        }

        val pool = pools[state] ?: return null
        val weights = weightsFor(pool, isAvailable, now)
        val chosen = pool.pick(isAvailable, { weights[it] ?: 0.0 }, random) ?: return null
        chosen.next.forEach { (s, name) -> pendingNext[s] = name }
        return played(state, chosen, now)
    }

    /** Called when a variant of a looping state played to its end. */
    fun onFinished(state: CompanionState, variant: AnimationVariant) {
        if (state == currentState && variant == current) current = null
    }

    /**
     * True while a non-basic CALM variant (yawn, a scene…) is playing; capped at [MAX_SCENE_MS]
     * so a lost end callback (overlay hidden) can never block the state machine.
     */
    fun isSceneRunning(): Boolean {
        val v = current ?: return false
        if (currentState != CompanionState.CALM || AnimTag.BASE in v.tags) return false
        return clock() - currentStartedAt < MAX_SCENE_MS
    }

    /** Forgets running sequences and the current scene (profile or character switch). */
    fun reset() {
        pendingNext.clear()
        current = null
        currentState = null
    }

    /** Dynamic weight of every available variant of [pool]. Exposed for tests. */
    internal fun weightsFor(
        pool: AnimationPool,
        isAvailable: (AnimationVariant) -> Boolean,
        now: Long = clock()
    ): Map<AnimationVariant, Double> {
        val m = mood.snapshot()
        val ctx = context()
        val weights = LinkedHashMap<AnimationVariant, Double>()
        for (v in pool.variants) {
            if (!isAvailable(v)) continue
            weights[v] = dynamicWeight(v, m, ctx, now)
        }
        // Rare extras must not crowd out the basic calm behaviour.
        val base = weights.filterKeys { AnimTag.BASE in it.tags }.values.sum()
        val extra = weights.filterKeys { AnimTag.BASE !in it.tags }.values.sum()
        val maxExtra = base * (1 - MIN_BASE_SHARE) / MIN_BASE_SHARE
        if (base > 0 && extra > maxExtra) {
            val k = maxExtra / extra
            for (v in weights.keys.toList()) if (AnimTag.BASE !in v.tags) weights[v] = weights.getValue(v) * k
        }
        return weights
    }

    private fun dynamicWeight(v: AnimationVariant, m: MoodSnapshot, ctx: PlayContext, now: Long): Double {
        var w = v.weight.toDouble()
        if (AnimTag.BASE in v.tags) return w
        val e = m.energy / 100.0
        val a = m.affection / 100.0
        val c = m.curiosity / 100.0
        if (AnimTag.SLEEPY in v.tags) w *= lerp(2.6, 0.4, e)
        if (AnimTag.ACTIVE in v.tags) w *= lerp(0.3, 1.6, e)
        if (AnimTag.EXPLORE in v.tags) w *= lerp(0.4, 2.2, c)
        if (AnimTag.FRIENDLY in v.tags) {
            w *= lerp(0.5, 2.0, a)
            if (ctx.sinceInteractionMs < RECENT_INTERACTION_MS) w *= 1.5
        }

        if (ctx.moving) {
            if (AnimTag.SCENE in v.tags || AnimTag.ACTIVE in v.tags) return 0.0
            w *= MOVING_EXTRA_FACTOR
        }
        if (AnimTag.SCENE in v.tags && now - lastSceneAt < SCENE_GAP_MS) return 0.0
        if (v.minGapMs > 0) {
            val last = lastPlayedAt[v.resName]
            if (last != null && now - last < v.minGapMs) return 0.0
        }
        val repeats = history.count { it == v.resName }
        repeat(repeats) { w *= REPEAT_FACTOR }
        return w
    }

    private fun played(state: CompanionState, v: AnimationVariant, now: Long): AnimationVariant {
        history.addLast(v.resName)
        while (history.size > HISTORY_SIZE) history.removeFirst()
        lastPlayedAt[v.resName] = now
        if (AnimTag.SCENE in v.tags) lastSceneAt = now
        current = v
        currentState = state
        currentStartedAt = now
        mood.onPlayed(v.tags)
        return v
    }

    private fun lerp(from: Double, to: Double, t: Double) = from + (to - from) * t.coerceIn(0.0, 1.0)

    companion object {
        /** Minimum share of [AnimTag.BASE] variants in a pool's total weight. */
        const val MIN_BASE_SHARE = 0.55

        /** Self-started scenes are at least this far apart. */
        const val SCENE_GAP_MS = 45_000L

        /** A scene longer than this is considered finished even without an end callback. */
        const val MAX_SCENE_MS = 8_000L

        /** Weight multiplier per recent play of the same variant (last [HISTORY_SIZE] picks). */
        const val REPEAT_FACTOR = 0.35
        const val HISTORY_SIZE = 8

        /** Non-basic extras while driving (scenes and active ones are off entirely). */
        const val MOVING_EXTRA_FACTOR = 0.6

        const val RECENT_INTERACTION_MS = 90_000L
    }
}
