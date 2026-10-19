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
            AnimationVariant("idle_small", weight = 50),
            AnimationVariant("idle2", weight = 18, cooldown = 1),   // looks around
            AnimationVariant("yawn", weight = 5, cooldown = 6),
            AnimationVariant("ear_scratch", weight = 5, cooldown = 6),
            AnimationVariant("tail_wag", weight = 7, cooldown = 2),
            AnimationVariant("stretch", weight = 4, cooldown = 8),
            AnimationVariant("shake", weight = 4, cooldown = 6),
            AnimationVariant("tail_play", weight = 4, cooldown = 10),
            AnimationVariant("surprise", weight = 3, cooldown = 8)
        )),
        CompanionState.BLINK to single("blink"),
        CompanionState.MUSIC to AnimationPool(listOf(
            AnimationVariant("music", weight = 32),
            AnimationVariant("music2", weight = 28),
            AnimationVariant("music_nod", weight = 20, cooldown = 1),
            AnimationVariant("music_paws", weight = 14, cooldown = 1),
            AnimationVariant("music_spin", weight = 6, cooldown = 6)
        )),
        CompanionState.TAP to AnimationPool(listOf(
            AnimationVariant("tap", weight = 45),
            AnimationVariant("head_tilt", weight = 30, cooldown = 1),
            AnimationVariant("laugh", weight = 25, cooldown = 1)
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
        CompanionState.WAKE to single("wake", fallback = "blink"),

        // Wave 2
        CompanionState.BOUNCE to single("bounce", fallback = "tap"),
        CompanionState.SHY to single("shy", fallback = "pet"),
        CompanionState.DROWSY to single("drowsy", fallback = "blink"),
        CompanionState.TURN_LEFT to single("turn_left", fallback = "alert"),
        CompanionState.TURN_RIGHT to single("turn_right", fallback = "alert"),
        CompanionState.PHONE_CALL to single("phone_call", fallback = "idle_small"),
        CompanionState.LOW_BATTERY to single("low_battery", fallback = "blink"),
        CompanionState.STRETCH to single("stretch", fallback = "idle2")
    )

    fun forState(state: CompanionState): AnimationPool =
        pools[state] ?: error("No animation pool for $state")

    private fun single(resName: String, fallback: String? = null) =
        AnimationPool(listOf(AnimationVariant(resName, fallback = fallback)))
}
