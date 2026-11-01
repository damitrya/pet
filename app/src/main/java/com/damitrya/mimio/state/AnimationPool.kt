package com.damitrya.mimio.state

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
    FRIENDLY,

    /** Car companion: only in the AUTO profile. */
    CAR,
    /** Holding the steering wheel: only while the car moves; the one extra not toned down when driving. */
    DRIVING,
    /** Only at a confirmed standstill (fresh GPS, longer than a traffic light). */
    PARKED_ONLY,
    /** Also while getting ready for a trip (and likelier then), even before the standstill is confirmed. */
    PREPARING,
    /** Caring for the car (wrench, tyre, fuel, wash): such scenes are spaced further apart from each other. */
    MAINTENANCE
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

        markPicked(chosen)
        return variants[chosen]
    }

    /** Counts [variant], chosen outside [pick] (a guaranteed heart), as a pick: the cooldowns go on as usual. */
    fun markPicked(variant: AnimationVariant) {
        val i = variants.indexOf(variant)
        if (i >= 0) markPicked(i)
    }

    /** Picks [variant] still sits out (0 = may be chosen). */
    fun cooldownOf(variant: AnimationVariant): Int = variants.indexOf(variant).let { if (it >= 0) cooldownLeft[it] else 0 }

    private fun markPicked(chosen: Int) {
        for (i in cooldownLeft.indices) {
            if (cooldownLeft[i] > 0) cooldownLeft[i]--
        }
        cooldownLeft[chosen] = variants[chosen].cooldown
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

    /**
     * Animations with no automatic trigger yet: they exist for the gallery and the manual preview (settings →
     * debug tools) only. A reliable bump detector is still missing (pothole). Kept out of every pool on purpose.
     * (The inertia leans of wave 5 play since wave 7: INERTIA_* states.)
     */
    val PREVIEW_ONLY = setOf("pothole")

    /** Wave 7: the fox leans with the car (turns, braking, speeding up); see the INERTIA_* states. */
    val INERTIA_LEANS = listOf("inertia_left", "inertia_right", "inertia_forward", "inertia_back")

    /** Music shows of wave 5: an accordion (bayan), a balalaika and a punk dance with a mohawk. */
    val MUSIC_SHOWS_WAVE5 = listOf("bayan", "balalaika", "punk")

    /**
     * What the fox may do on its own after a long time without the user (wave 6, [ActivityDecision.initiative]):
     * look around, tilt its head, wag, scratch an ear, wave, start a little game or a friendly scene.
     */
    val INITIATIVE = setOf(
        "head_tilt", "tail_wag", "ear_scratch", "paw_wave", "listen", "tail_play", "tail_hide", "tail_back"
    )

    /** The fox turns into a heart: a petting finale and the farewell when the user hides it. */
    const val HEART_TRANSFORM = "heart_transform"

    /**
     * When the heart is complete in fox_heart_transform.webp (ms from its start): the moment of the short
     * haptic tick. Must match HEART_MOMENT_MS in tools/fox/generate.py (checked by its tests).
     */
    const val HEART_MOMENT_MS = 2000L

    private val CAR_SCENE = setOf(AnimTag.CAR, AnimTag.SCENE, AnimTag.PARKED_ONLY)
    private val CAR_CARE = CAR_SCENE + AnimTag.MAINTENANCE

    private val SLEEP_CURLED = mapOf(
        CompanionState.SLEEP to "sleep_curled",
        CompanionState.WAKE to "wake_curled"
    )

    /** Fresh pools with their own cooldown counters (one set per [AnimationDirector]). */
    fun create(random: Random = Random.Default): Map<CompanionState, AnimationPool> {
        fun pool(vararg variants: AnimationVariant) = AnimationPool(variants.toList(), random)
        fun single(resName: String, fallback: String? = null) = pool(AnimationVariant(resName, fallback = fallback))
        return mapOf(
            // Wave 6: ActivityScheduler decides by time whether CALM plays basic calm (BASE), a small movement
            // (no BASE / SCENE tag) or a scene (SCENE); the weights below only choose inside that category.
            // Cooldowns count every CALM pick, basic calm included.
            CompanionState.CALM to pool(
                AnimationVariant("idle_small", weight = 45, tags = setOf(AnimTag.BASE)),
                AnimationVariant("idle2", weight = 30, cooldown = 1, tags = setOf(AnimTag.BASE)),   // looks around
                // Small movements: head, ears, tail first; yawning and stretching mostly when tired.
                AnimationVariant("yawn", weight = 4, cooldown = 8, tags = setOf(AnimTag.SLEEPY)),
                AnimationVariant("ear_scratch", weight = 6, cooldown = 6),
                AnimationVariant("tail_wag", weight = 8, cooldown = 3, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("head_tilt", weight = 7, cooldown = 4, tags = setOf(AnimTag.EXPLORE)),
                AnimationVariant("paw_wave", weight = 3, cooldown = 8, minGapMs = MINUTE, tags = setOf(AnimTag.FRIENDLY)),
                AnimationVariant("stretch", weight = 3, cooldown = 10, tags = setOf(AnimTag.SLEEPY)),
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
                    tags = setOf(AnimTag.EXPLORE)),
                // Wave 4: turns its back and wags the tail. A scene: never while the car moves.
                AnimationVariant("tail_back", weight = 3, cooldown = 10, minGapMs = 5 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.FRIENDLY)),
                // Car companion (AUTO profile). Driving: short and calm, never a scene.
                AnimationVariant("driving", weight = 6, cooldown = 3, minGapMs = 45_000L,
                    tags = setOf(AnimTag.CAR, AnimTag.DRIVING)),
                // Standing still only; the slime has no files for them — they are skipped.
                AnimationVariant("navigator", weight = 3, cooldown = 8, minGapMs = 3 * MINUTE,
                    tags = CAR_SCENE + AnimTag.PREPARING + AnimTag.EXPLORE),
                AnimationVariant("parking", weight = 2, cooldown = 8, minGapMs = 5 * MINUTE, tags = CAR_SCENE),
                AnimationVariant("tire_check", weight = 3, cooldown = 8, minGapMs = 5 * MINUTE,
                    tags = CAR_CARE + AnimTag.EXPLORE),
                AnimationVariant("refuel", weight = 3, cooldown = 8, minGapMs = 5 * MINUTE, tags = CAR_CARE),
                AnimationVariant("mechanic", weight = 2, cooldown = 10, minGapMs = 8 * MINUTE, tags = CAR_CARE),
                AnimationVariant("car_wash", weight = 2, cooldown = 10, minGapMs = 8 * MINUTE, tags = CAR_CARE)
            ),
            CompanionState.BLINK to single("blink"),
            CompanionState.MUSIC to pool(
                AnimationVariant("music", weight = 32),
                AnimationVariant("music2", weight = 28),
                AnimationVariant("music_nod", weight = 20, cooldown = 1),
                AnimationVariant("music_paws", weight = 14, cooldown = 1),
                AnimationVariant("music_spin", weight = 6, cooldown = 6, tags = setOf(AnimTag.ACTIVE)),
                // Wave 4. The side dance moves a lot (ACTIVE: not while driving); the guitar and the microphone
                // are rare shows (SCENE: spaced apart, not while driving) so the usual dances stay the norm.
                AnimationVariant("dance_side", weight = 10, cooldown = 3, minGapMs = MINUTE, tags = setOf(AnimTag.ACTIVE)),
                AnimationVariant("microphone", weight = 4, cooldown = 8, minGapMs = 4 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.FRIENDLY)),
                AnimationVariant("guitar", weight = 4, cooldown = 8, minGapMs = 4 * MINUTE, tags = setOf(AnimTag.SCENE)),
                // Wave 5: more shows, as rare as the guitar (SCENE: spaced apart from every other show, never
                // while driving; the long minGap keeps any one of them from coming back again and again).
                AnimationVariant("bayan", weight = 3, cooldown = 8, minGapMs = 5 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.FRIENDLY)),
                AnimationVariant("balalaika", weight = 3, cooldown = 8, minGapMs = 5 * MINUTE, tags = setOf(AnimTag.SCENE)),
                AnimationVariant("punk", weight = 3, cooldown = 10, minGapMs = 6 * MINUTE,
                    tags = setOf(AnimTag.SCENE, AnimTag.ACTIVE))
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
                AnimationVariant("hearts", weight = 60, fallback = "pet"),
                AnimationVariant("proud", weight = 15, cooldown = 2, tags = setOf(AnimTag.FRIENDLY)),
                // Turns into a heart: not after every petting (minGap), never twice in a row (cooldown).
                AnimationVariant(HEART_TRANSFORM, weight = 25, cooldown = 2, minGapMs = 2 * MINUTE,
                    tags = setOf(AnimTag.FRIENDLY))
            ),
            // The slime has no heart: it waves goodbye with its petting animation.
            CompanionState.FAREWELL to single(HEART_TRANSFORM, fallback = "pet"),
            CompanionState.RELIEF to single("relief", fallback = "blink"),
            CompanionState.GREETING to single("greeting", fallback = "tap"),
            CompanionState.TRIP_START to single("trip_start", fallback = "tap"),
            // Car companion: getting into the car and the end of a long trip.
            CompanionState.TRIP_PREPARE to pool(
                AnimationVariant("seatbelt", weight = 70, fallback = "tap"),
                AnimationVariant("navigator", weight = 30, tags = setOf(AnimTag.EXPLORE))
            ),
            CompanionState.ARRIVAL to pool(
                AnimationVariant("arrival", weight = 65, fallback = "tap"),
                AnimationVariant("parking", weight = 35)
            ),
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
            // Wave 7: leans with the car, named after the way the fox leans (a right turn throws it to the left).
            // The slime has no leans: it blinks.
            CompanionState.INERTIA_LEFT to single("inertia_left", fallback = "blink"),
            CompanionState.INERTIA_RIGHT to single("inertia_right", fallback = "blink"),
            CompanionState.INERTIA_FORWARD to single("inertia_forward", fallback = "blink"),
            CompanionState.INERTIA_BACK to single("inertia_back", fallback = "blink"),
            CompanionState.PHONE_CALL to single("phone_call", fallback = "idle_small"),
            CompanionState.LOW_BATTERY to single("low_battery", fallback = "blink"),
            // Wave 5: battery at 10 % and the 1 % goodbye (the slime blinks, then the service stops).
            CompanionState.BATTERY_CRITICAL to single("battery_sad", fallback = "blink"),
            CompanionState.BATTERY_SHUTDOWN to single("cloud_farewell", fallback = "blink"),
            // The file comes from AnimationDirector.preview(); this is only what plays without one.
            CompanionState.PREVIEW to single("blink"),
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
