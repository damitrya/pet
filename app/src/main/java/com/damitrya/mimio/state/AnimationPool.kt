package com.drivecompanion.state

import kotlin.random.Random

/**
 * What kind of behaviour a variant shows. [AnimationDirector] scales its chance with the mood
 * and the context (see the multipliers there).
 */
enum class AnimTag {
    /** Basic calm behaviour (breathing, looking around); always keeps a share of the CALM loop. */
    BASE,
    /** A self-started mini-scene (several seconds, eye-catching): never while driving, spaced apart. */
    SCENE,
    /** Jumps, spins, chasing: never while driving, rarer with low energy. */
    ACTIVE,
    /** Yawning, curling up: likelier with low energy. */
    SLEEPY,
    /** Listening, looking around, puzzling: likelier with high curiosity. */
    EXPLORE,
    /** Waving, teasing, wagging: likelier with high affection and right after an interaction. */
    FRIENDLY
}

/**
 * One animation file that a state can play.
 *
 * @param resName   raw resource name without the character prefix (e.g. "yawn" → fox_yawn.webp / yawn.webp).
 * @param weight    relative chance of being picked among the available variants of the pool.
 * @param cooldown  how many following picks this variant sits out after being played (0 = may repeat).
 * @param fallback  existing animation to play instead when the character has no file for [resName].
 *                  null means "skip this variant" — it is simply left out of the pool.
 * @param tags      behaviour kind, used for mood/context weights by [AnimationDirector].
 * @param minGapMs  minimum time between two plays of this variant (rare scenes), 0 = no limit.
 * @param next      follow-up animations: the next time one of these states starts, it plays the given
 *                  file instead of its own pool (e.g. curl_up → sleep_curled → wake_curled).
 */
data class AnimationVariant(
    val resName: String,
    val weight: Int = 1,
    val cooldown: Int = 0,
    val fallback: String? = null,
    val tags: Set<AnimTag> = emptySet(),
    val minGapMs: Long = 0L,
    val next: Map<CompanionState, String> = emptyMap()
)

/**
 * Weighted random choice of the next animation for a state, with a per-variant cooldown
 * so that rare animations (yawn, ear scratch…) never play twice in a row.
 */
class AnimationPool(
    val variants: List<AnimationVariant>,
    private val random: Random = Random.Default
) {
    /** Picks left before a variant may be chosen again. */
    private val cooldownLeft = IntArray(variants.size)

    /**
     * Returns the next variant, or null if none is available.
     *
     * @param isAvailable whether the variant can be played by the current character
     *                    (own file or a fallback exists).
     * @param weightOf    dynamic weight of a variant (default: its static [AnimationVariant.weight]).
     *                    Variants weighing 0 are left out unless nothing else is available.
     * @param random      source of randomness for this pick.
     */
    fun pick(
        isAvailable: (AnimationVariant) -> Boolean = { true },
        weightOf: (AnimationVariant) -> Double = { it.weight.toDouble() },
        random: Random = this.random
    ): AnimationVariant? {
        val available = variants.indices.filter { isAvailable(variants[it]) }
        if (available.isEmpty()) return null
        val weights = DoubleArray(variants.size)
        for (i in available) weights[i] = weightOf(variants[i]).coerceAtLeast(0.0)

        // If every variant is cooling down, ignore the cooldown rather than play nothing.
        val allowed = available.filter { weights[it] > 0.0 }.ifEmpty { listOf(available.first()) }
        val ready = allowed.filter { cooldownLeft[it] == 0 }.ifEmpty { allowed }
        val chosen = weightedChoice(ready, weights, random)

        for (i in cooldownLeft.indices) {
            if (cooldownLeft[i] > 0) cooldownLeft[i]--
        }
        cooldownLeft[chosen] = variants[chosen].cooldown
        return variants[chosen]
    }

    private fun weightedChoice(indices: List<Int>, weights: DoubleArray, random: Random): Int {
        val total = indices.sumOf { weights[it] }
        if (total <= 0.0) return indices.first()
        var roll = random.nextDouble() * total
        for (i in indices) {
            roll -= weights[i]
            if (roll < 0) return i
        }
        return indices.last()
    }
}

/**
 * Animation pools for every [CompanionState].
 *
 * Looping ambient states (CALM, MUSIC) pick a new variant each time the current one finishes.
 * Other states pick once when they start. To add an animation, drop `<name>.webp` and/or
 * `fox_<name>.webp` into res/raw and add a line here.
 */
object AnimationPools {

    private const val MINUTE = 60_000L

    private val SLEEP_CURLED = mapOf(
        CompanionState.SLEEP to "sleep_curled",
        CompanionState.WAKE to "wake_curled"
    )

    /** Fresh pools with their own cooldown counters (one set per [AnimationDirector]). */
    fun create(random: Random = Random.Default): Map<CompanionState, AnimationPool> {
        fun pool(vararg variants: AnimationVariant) = AnimationPool(variants.toList(), random)
        fun single(resName: String, fallback: String? = null) = pool(AnimationVariant(resName, fallback = fallback))
        return mapOf(
            CompanionState.CALM to pool(
                AnimationVariant("idle_small", weight = 50, tags = setOf(AnimTag.BASE)),
                AnimationVariant("idle2", weight = 18, cooldown = 1, tags = setOf(AnimTag.BASE)),   // looks around
                AnimationVariant("yawn", weight = 5, cooldown = 6, tags = setOf(AnimTag.SLEEPY)),
                AnimationVariant("ear_scratch", weight = 5, cooldown = 6),
                AnimationVariant("tail_wag", weight = 7, cooldown = 2, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("stretch", weight = 4, cooldown = 8, tags = setOf(AnimTag.SLEEPY)),
                AnimationVariant("shake", weight = 4, cooldown = 6, tags = setOf(AnimTag.ACTIVE)),
                AnimationVariant("tail_play", weight = 4, cooldown = 10, minGapMs = 3 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.ACTIVE)),
                AnimationVariant("surprise", weight = 3, cooldown = 8, tags = setOf(AnimTag.ACTIVE)),
                // Wave 3: little scenes. The slime has no files for them — they are skipped.
                AnimationVariant("tail_hide", weight = 4, cooldown = 8, minGapMs = 3 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.FRIENDLY)),
                AnimationVariant("grooming", weight = 4, cooldown = 8, minGapMs = 3 * MINUTE,
                    tags = setOf(AnimTag.SCENE)),
                AnimationVariant("butterfly", weight = 3, cooldown = 10, minGapMs = 5 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.ACTIVE, AnimTag.EXPLORE)),
                AnimationVariant("sneeze", weight = 3, cooldown = 8, minGapMs = 2 * MINUTE),
                AnimationVariant("listen", weight = 6, cooldown = 4, tags = setOf(AnimTag.EXPLORE)),
                AnimationVariant("confused", weight = 3, cooldown = 6, minGapMs = MINUTE,
                    tags = setOf(AnimTag.EXPLORE))
            ),
            CompanionState.BLINK to single("blink"),
            CompanionState.MUSIC to pool(
                AnimationVariant("music", weight = 32),
                AnimationVariant("music2", weight = 28),
                AnimationVariant("music_nod", weight = 20, cooldown = 1),
                AnimationVariant("music_paws", weight = 14, cooldown = 1),
                AnimationVariant("music_spin", weight = 6, cooldown = 6, tags = setOf(AnimTag.ACTIVE))
            ),
            CompanionState.TAP to pool(
                AnimationVariant("tap", weight = 45),
                AnimationVariant("head_tilt", weight = 30, cooldown = 1, tags = setOf(AnimTag.EXPLORE)),
                AnimationVariant("laugh", weight = 25, cooldown = 1, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("tease", weight = 12, cooldown = 3, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("paw_wave", weight = 12, cooldown = 3, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("proud", weight = 7, cooldown = 4, minGapMs = MINUTE, tags = setOf(AnimTag.FRIENDLY))
            ),
            CompanionState.ALERT to single("alert"),
            CompanionState.PET to single("pet"),

            // Wave 1. Fallbacks are placeholders from existing files for characters
            // that do not have the new animation yet (the slime).
            CompanionState.ANNOYED to single("annoyed", fallback = "blink"),
            CompanionState.HEARTS to pool(
                AnimationVariant("hearts", weight = 80, fallback = "pet"),
                AnimationVariant("proud", weight = 20, cooldown = 2, tags = setOf(AnimTag.FRIENDLY))
            ),
            CompanionState.RELIEF to single("relief", fallback = "blink"),
            CompanionState.GREETING to single("greeting", fallback = "tap"),
            CompanionState.TRIP_START to single("trip_start", fallback = "tap"),
            CompanionState.FALL_ASLEEP to pool(
                AnimationVariant("fall_asleep", weight = 60, fallback = "blink"),
                // Lies down with the tail over its nose; SLEEP and WAKE then keep the curled pose.
                AnimationVariant("curl_up", weight = 40, tags = setOf(AnimTag.SLEEPY), next = SLEEP_CURLED)
            ),
            CompanionState.SLEEP to single("sleep", fallback = "idle_small"),
            CompanionState.WAKE to single("wake", fallback = "blink"),

            // Wave 2
            CompanionState.BOUNCE to single("bounce", fallback = "tap"),
            CompanionState.SHY to single("shy", fallback = "pet"),
            CompanionState.DROWSY to single("drowsy", fallback = "blink"),
            CompanionState.TURN_LEFT to single("turn_left", fallback = "alert"),
            CompanionState.TURN_RIGHT to single("turn_right", fallback = "alert"),
            CompanionState.PHONE_CALL to single("phone_call", fallback = "idle_small"),
            CompanionState.LOW_BATTERY to single("low_battery", fallback = "blink"),
            CompanionState.STRETCH to single("stretch", fallback = "idle2"),
            CompanionState.FIDGET to pool(
                AnimationVariant("blink", weight = 15, tags = setOf(AnimTag.BASE)),
                AnimationVariant("idle2", weight = 20, cooldown = 1, tags = setOf(AnimTag.BASE)),
                AnimationVariant("tail_wag", weight = 20, cooldown = 1, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("ear_scratch", weight = 12, cooldown = 2),
                AnimationVariant("head_tilt", weight = 12, cooldown = 2, tags = setOf(AnimTag.EXPLORE)),
                AnimationVariant("shake", weight = 8, cooldown = 3, tags = setOf(AnimTag.ACTIVE)),
                AnimationVariant("surprise", weight = 7, cooldown = 3, tags = setOf(AnimTag.ACTIVE)),
                AnimationVariant("tail_play", weight = 6, cooldown = 4, tags = setOf(AnimTag.SCENE, AnimTag.ACTIVE)),
                AnimationVariant("listen", weight = 10, cooldown = 3, tags = setOf(AnimTag.EXPLORE)),
                AnimationVariant("sneeze", weight = 5, cooldown = 4)
            )
        )
    }

    private val defaultPools: Map<CompanionState, AnimationPool> by lazy { create() }

    fun forState(state: CompanionState): AnimationPool =
        defaultPools[state] ?: error("No animation pool for $state")
}
