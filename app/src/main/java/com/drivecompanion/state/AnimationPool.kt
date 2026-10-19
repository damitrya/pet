package com.drivecompanion.state

import kotlin.random.Random

/**
 * One animation file that a state can play.
 *
 * @param resName   raw resource name without the character prefix (e.g. "yawn" → fox_yawn.webp / yawn.webp).
 * @param weight    relative chance of being picked among the available variants of the pool.
 * @param cooldown  how many following picks this variant sits out after being played (0 = may repeat).
 * @param fallback  existing animation to play instead when the character has no file for [resName].
 *                  null means "skip this variant" — it is simply left out of the pool.
 */
data class AnimationVariant(
    val resName: String,
    val weight: Int = 1,
    val cooldown: Int = 0,
    val fallback: String? = null
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
     */
    fun pick(isAvailable: (AnimationVariant) -> Boolean = { true }): AnimationVariant? {
        val available = variants.indices.filter { isAvailable(variants[it]) }
        if (available.isEmpty()) return null

        // If every variant is cooling down, ignore the cooldown rather than play nothing.
        val ready = available.filter { cooldownLeft[it] == 0 }.ifEmpty { available }
        val chosen = weightedChoice(ready)

        for (i in cooldownLeft.indices) {
            if (cooldownLeft[i] > 0) cooldownLeft[i]--
        }
        cooldownLeft[chosen] = variants[chosen].cooldown
        return variants[chosen]
    }

    private fun weightedChoice(indices: List<Int>): Int {
        val total = indices.sumOf { variants[it].weight.coerceAtLeast(0) }
        if (total <= 0) return indices.first()
        var roll = random.nextInt(total)
        for (i in indices) {
            roll -= variants[i].weight.coerceAtLeast(0)
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

    private val pools: Map<CompanionState, AnimationPool> = mapOf(
        CompanionState.CALM to AnimationPool(listOf(
            AnimationVariant("idle_small", weight = 65),
            AnimationVariant("idle2", weight = 23, cooldown = 1),   // looks around
            AnimationVariant("yawn", weight = 6, cooldown = 6),
            AnimationVariant("ear_scratch", weight = 6, cooldown = 6)
        )),
        CompanionState.BLINK to single("blink"),
        CompanionState.MUSIC to AnimationPool(listOf(
            AnimationVariant("music", weight = 40),
            AnimationVariant("music2", weight = 35),
            AnimationVariant("music_nod", weight = 25, cooldown = 1)
        )),
        CompanionState.TAP to AnimationPool(listOf(
            AnimationVariant("tap", weight = 60),
            AnimationVariant("head_tilt", weight = 40, cooldown = 1)
        )),
        CompanionState.ALERT to single("alert"),
        CompanionState.PET to single("pet"),

        // Wave 1. Fallbacks are placeholders from existing files for characters
        // that do not have the new animation yet (the slime).
        CompanionState.ANNOYED to single("annoyed", fallback = "blink"),
        CompanionState.HEARTS to single("hearts", fallback = "pet"),
        CompanionState.RELIEF to single("relief", fallback = "blink"),
        CompanionState.GREETING to single("greeting", fallback = "tap"),
        CompanionState.TRIP_START to single("trip_start", fallback = "tap"),
        CompanionState.FALL_ASLEEP to single("fall_asleep", fallback = "blink"),
        CompanionState.SLEEP to single("sleep", fallback = "idle_small"),
        CompanionState.WAKE to single("wake", fallback = "blink")
    )

    fun forState(state: CompanionState): AnimationPool =
        pools[state] ?: error("No animation pool for $state")

    private fun single(resName: String, fallback: String? = null) =
        AnimationPool(listOf(AnimationVariant(resName, fallback = fallback)))
}
