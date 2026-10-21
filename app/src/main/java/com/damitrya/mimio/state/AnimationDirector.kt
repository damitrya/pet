package com.damitrya.mimio.state

import kotlin.random.Random

/** What is going on around the character when an animation is chosen. */
data class PlayContext(
    /** The car is confirmed moving (fresh GPS speed): no eye-catching scenes. */
    val moving: Boolean = false,
    val music: Boolean = false,
    /** Time since the user last tapped or petted the character. */
    val sinceInteractionMs: Long = Long.MAX_VALUE,
    /** AUTO profile: car animations may play. */
    val car: Boolean = false,
    /** Confirmed parking: fresh GPS shows the car standing longer than a traffic light, and not mid-trip. */
    val parked: Boolean = false,
    /** Phase of the car trip (see [TripTracker]). */
    val trip: TripPhase = TripPhase.PARKED
)

/**
 * Chooses which file each state plays, on top of [AnimationPools]:
 *
 * - **mood** ([CharacterMood]): low energy favours [AnimTag.SLEEPY], high curiosity [AnimTag.EXPLORE],
 *   high affection [AnimTag.FRIENDLY], and active play needs energy;
 * - **context**: while the car moves, [AnimTag.SCENE] and [AnimTag.ACTIVE] variants are not played and
 *   other extras are toned down; right after an interaction friendly variants are likelier;
 * - **car**: [AnimTag.CAR] variants only play in the AUTO profile, [AnimTag.DRIVING] only while the car
 *   moves, [AnimTag.PARKED_ONLY] only at a confirmed standstill (or while getting ready, for
 *   [AnimTag.PREPARING] ones), and [AnimTag.MAINTENANCE] scenes are at least [MAINTENANCE_GAP_MS] apart;
 * - **time, not count** (CALM, wave 6): [ActivityScheduler] decides by the clock, the user's
 *   [CompanionActivityMode] and the mood whether the next CALM pick is basic calm, a small movement or a
 *   mini-scene; the pick is then made inside that category. A scene that cannot play (cooldown, minimum gap,
 *   context) is replaced by a small movement — its limits are never broken to force it;
 * - **variety**: variants played recently lose weight, [AnimationVariant.minGapMs] spaces rare ones out,
 *   the same small movement never plays twice in a row, and self-started scenes are at least
 *   [CompanionActivityMode.sceneGapMs] (CALM) or [MUSIC_SHOW_GAP_MS] (music shows) apart;
 * - **calm first**: in pools with [AnimTag.BASE] variants those keep at least [MIN_BASE_SHARE] of the chance
 *   (CALM: after every scene the scheduler plays basic calm first);
 * - **the heart**: every [HEART_EVERY_PETS]th petting finale (HEARTS or SHY) without a heart turns into a heart
 *   for sure ([isHeartDue]; the state machine then plays HEARTS instead of SHY); cooldown and minGap only hold
 *   back the random heart in between. Why it was or was not picked goes to [log];
 * - **preview**: [preview] makes the next PREVIEW play one given file (settings → debug tools);
 * - **sequences**: a variant's [AnimationVariant.next] decides what the following states play
 *   (curl_up → sleep_curled → wake_curled), but only once that variant has played to its end — the
 *   follow-ups continue from its last pose. Any other state in between (a tap while still lying down,
 *   braking, a turn, a profile switch) drops the sequence, and missing files fall back to the pools.
 *
 * The director only picks files — it never changes the state, so it cannot interrupt ALERT, PET or any
 * other reaction; [isSceneRunning] lets the state machine postpone its own idle one-shots (blink, dozing
 * off) until a scene has finished, and [isDistractingSceneRunning] lets it cut a scene short when the car
 * starts moving. Randomness and time are injected for tests.
 */
class AnimationDirector(
    private val mood: CharacterMood,
    private val context: () -> PlayContext = { PlayContext() },
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The user's activity mode; read at every CALM pick, so a change in the settings applies at once. */
    private val activityMode: () -> CompanionActivityMode = { CompanionActivityMode.DEFAULT },
    /** Self-started behaviour, step by step (debug builds: MimioActivity); null = off. */
    private val activityLog: ((String) -> Unit)? = null,
    /** Why the heart was (not) picked, guaranteed or not (logcat in the app). Last: a trailing lambda is this log. */
    private val log: (String) -> Unit = {}
) {
    private val pools: Map<CompanionState, AnimationPool> = AnimationPools.create(random)

    /** When CALM shows basic calm, a small movement or a scene. */
    private val activity = ActivityScheduler(activityMode, random, activityLog)

    /** The last small movement in CALM: never the same one twice in a row. */
    private var lastMicro: String? = null

    /** Last picks (newest last), for the repetition penalty. */
    private val history = ArrayDeque<String>()

    private val lastPlayedAt = HashMap<String, Long>()
    private var lastSceneAt: Long = Long.MIN_VALUE / 2
    private var lastMaintenanceAt: Long = Long.MIN_VALUE / 2

    /** Follow-ups of the variant on screen; they take effect when it reaches its last frame. */
    private var armedNext: Map<CompanionState, String> = emptyMap()

    /** Follow-up files in effect: the character is in the end pose of a finished sequence step. */
    private val pendingNext = HashMap<CompanionState, String>()

    private var current: AnimationVariant? = null
    private var currentState: CompanionState? = null
    private var currentStartedAt: Long = 0L

    /** Petting finales (HEARTS or SHY picks) since the fox last turned into a heart. */
    private var finalesSinceHeart = 0

    /** File the next PREVIEW plays (null: the PREVIEW pool's placeholder). */
    private var previewName: String? = null

    /** The next petting finale must be the heart: it is the [HEART_EVERY_PETS]th one without a heart. */
    fun isHeartDue(): Boolean = finalesSinceHeart >= HEART_EVERY_PETS - 1

    /** The next PREVIEW plays [resName] (raw name without the character prefix, e.g. "inertia_left"). */
    fun preview(resName: String) {
        previewName = resName
    }

    /**
     * Picks the variant [state] plays next, or null if the character has none.
     * [isAvailable] tells whether the current character can play a variant (own file or fallback).
     */
    fun pick(state: CompanionState, isAvailable: (AnimationVariant) -> Boolean): AnimationVariant? {
        val now = clock()
        when (state) {
            CompanionState.CALM -> {}
            CompanionState.BLINK -> activity.onBlink(now)
            else -> activity.onOtherState(now)     // a reaction or another ambient state: the CALM plan steps back
        }
        armedNext = emptyMap()         // something else starts: the previous end pose will not be reached
        if (state !in pendingNext) pendingNext.clear()
        pendingNext.remove(state)?.let { name ->
            val follow = AnimationVariant(name)
            if (isAvailable(follow)) return played(state, follow, now)
            pendingNext.clear()       // the character lacks the sequence: back to the normal pools
        }

        val pool = pools[state] ?: return null
        if (state == CompanionState.PREVIEW) pickPreview(isAvailable)?.let { return it }
        if (state == CompanionState.HEARTS && isHeartDue()) {
            val heart = pool.variants.first { it.resName == AnimationPools.HEART_TRANSFORM }
            if (isAvailable(heart)) {
                // Guaranteed: neither the cooldown nor the minimum gap may hold it back.
                log("HEARTS → ${heart.resName}: guaranteed, finale ${finalesSinceHeart + 1} without a heart")
                pool.markPicked(heart)
                return played(state, heart, now)
            }
            log("HEARTS: the heart is due, but this character has no ${heart.resName}")
            finalesSinceHeart = 0
        }
        val weights = weightsFor(pool, isAvailable, now, sceneGapFor(state))
        if (state == CompanionState.CALM) return pickCalm(pool, weights, isAvailable, now)
        val heartCooldown = if (state == CompanionState.HEARTS) heartCooldown(pool) else 0
        val chosen = pool.pick(isAvailable, { weights[it] ?: 0.0 }, random) ?: return null
        if (state == CompanionState.HEARTS) {
            log("HEARTS → ${chosen.resName}: ${heartReason(pool, chosen, weights, heartCooldown, isAvailable, now)}")
        }
        armedNext = chosen.next
        return played(state, chosen, now)
    }

    /**
     * CALM: [ActivityScheduler] names the category, the weights choose inside it. An unavailable scene becomes a
     * small movement, an unavailable small movement basic calm; cooldowns and minimum gaps are never broken for it.
     */
    private fun pickCalm(
        pool: AnimationPool,
        weights: Map<AnimationVariant, Double>,
        isAvailable: (AnimationVariant) -> Boolean,
        now: Long
    ): AnimationVariant? {
        val ctx = context()
        val m = mood.snapshot()
        val decision = activity.decide(now, ctx, m)
        var chosen: AnimationVariant? = null
        if (decision.category == ActivityCategory.SCENE) {
            chosen = pickIn(pool, weights, ActivityCategory.SCENE, decision.initiative)
            if (chosen == null) activity.onSceneUnavailable(now, m, if (activityLog != null) sceneUnavailable(pool, isAvailable, now) else "")
        }
        if (chosen == null && decision.category != ActivityCategory.AMBIENT) {
            chosen = pickIn(pool, weights, ActivityCategory.MICRO, decision.initiative)
        }
        if (chosen == null) chosen = pickIn(pool, weights, ActivityCategory.AMBIENT, false)
        if (chosen == null) chosen = pool.pick(isAvailable, { weights[it] ?: 0.0 }, random) ?: return null
        if (chosen.category == ActivityCategory.MICRO) lastMicro = chosen.resName
        // Only a fitting animation counts as the fox's initiative (its window may have had none free).
        val fulfilled = decision.copy(initiative = decision.initiative && chosen.resName in AnimationPools.INITIATIVE)
        activity.onPicked(chosen.resName, chosen.category, fulfilled, now, ctx, m)
        armedNext = chosen.next
        return played(CompanionState.CALM, chosen, now)
    }

    /**
     * Weighted pick among the variants of [category] that may play now (weight > 0; MICRO and SCENE also off
     * their cooldown, MICRO not the last one again). An [initiative] prefers [AnimationPools.INITIATIVE].
     */
    private fun pickIn(
        pool: AnimationPool,
        weights: Map<AnimationVariant, Double>,
        category: ActivityCategory,
        initiative: Boolean
    ): AnimationVariant? {
        var candidates = weights.filter { (v, w) ->
            w > 0.0 && v.category == category && (category == ActivityCategory.AMBIENT || pool.cooldownOf(v) == 0)
        }
        if (category == ActivityCategory.MICRO) {
            candidates.filterKeys { it.resName != lastMicro }.let { if (it.isNotEmpty()) candidates = it }
        }
        if (initiative) {
            candidates.filterKeys { it.resName in AnimationPools.INITIATIVE }.let { if (it.isNotEmpty()) candidates = it }
        }
        if (candidates.isEmpty()) return null
        return pool.pick({ it in candidates }, { candidates[it] ?: 0.0 }, random)
    }

    /** Why no scene could play although its window was open (debug log only). */
    private fun sceneUnavailable(pool: AnimationPool, isAvailable: (AnimationVariant) -> Boolean, now: Long): String {
        val scenes = pool.variants.filter { it.category == ActivityCategory.SCENE && isAvailable(it) }
        if (scenes.isEmpty()) return "this character has no scenes"
        val ctx = context()
        return scenes.joinToString(prefix = "every scene rests: ") { v ->
            val last = lastPlayedAt[v.resName]
            val why = when {
                !carAllows(v, ctx) -> "not here"
                AnimTag.MAINTENANCE in v.tags && now - lastMaintenanceAt < MAINTENANCE_GAP_MS -> "car care gap"
                last != null && now - last < v.minGapMs -> "min gap ${(v.minGapMs - (now - last)) / 1000} s"
                pool.cooldownOf(v) > 0 -> "cooldown ${pool.cooldownOf(v)}"
                else -> "weight 0"
            }
            "${v.resName} ($why)"
        }
    }

    /** Self-started scenes of [state] are at least this far apart: the user's mode in CALM, the shows' gap elsewhere. */
    private fun sceneGapFor(state: CompanionState): Long =
        if (state == CompanionState.CALM) activityMode().sceneGapMs else MUSIC_SHOW_GAP_MS

    /** Self-started behaviour of this session (debug tools → statistics). */
    fun activityStats(): ActivityStats = activity.stats(clock())

    /** The requested preview file, played as it is: no history, no gaps, no mood. */
    private fun pickPreview(isAvailable: (AnimationVariant) -> Boolean): AnimationVariant? {
        val name = previewName ?: return null
        previewName = null
        val v = AnimationVariant(name)
        if (!isAvailable(v)) {
            log("PREVIEW: no file for $name")
            return null
        }
        current = v
        currentState = CompanionState.PREVIEW
        currentStartedAt = clock()
        return v
    }

    private fun heartCooldown(pool: AnimationPool): Int =
        pool.cooldownOf(pool.variants.first { it.resName == AnimationPools.HEART_TRANSFORM })

    /** Why a random petting finale did or did not become the heart (for the log). */
    private fun heartReason(
        pool: AnimationPool,
        chosen: AnimationVariant,
        weights: Map<AnimationVariant, Double>,
        cooldown: Int,
        isAvailable: (AnimationVariant) -> Boolean,
        now: Long
    ): String {
        val heart = pool.variants.first { it.resName == AnimationPools.HEART_TRANSFORM }
        val left = HEART_EVERY_PETS - 1 - finalesSinceHeart
        val w = weights[heart] ?: 0.0
        val total = weights.values.sum()
        val last = lastPlayedAt[heart.resName]
        return when {
            chosen == heart -> "random heart (chance ${percent(w, total)}%)"
            !isAvailable(heart) -> "no heart: this character has no file"
            last != null && now - last < heart.minGapMs -> "no heart: minimum gap, ${(heart.minGapMs - (now - last)) / 1000} s left"
            cooldown > 0 -> "no heart: cooldown, $cooldown more pick(s)"
            w <= 0.0 -> "no heart: weight 0 in this context"
            else -> "no heart this time (chance ${percent(w, total)}%), guaranteed in $left more petting(s)"
        }
    }

    private fun percent(w: Double, total: Double) = if (total > 0) (100 * w / total).toInt() else 0

    /** Called when a variant played to its last frame (looping cycle or one-shot). */
    fun onFinished(state: CompanionState, variant: AnimationVariant) {
        if (state != currentState || variant != current) return
        current = null
        if (armedNext.isNotEmpty()) {
            pendingNext.clear()
            pendingNext.putAll(armedNext)
            armedNext = emptyMap()
        }
    }

    /**
     * True while a non-basic CALM variant (yawn, a scene…) or a MUSIC show (guitar, microphone) is playing;
     * capped at [MAX_SCENE_MS] so a lost end callback (overlay hidden) can never block the state machine.
     */
    fun isSceneRunning(): Boolean {
        val v = current ?: return false
        val busy = when (currentState) {
            CompanionState.CALM -> AnimTag.BASE !in v.tags
            CompanionState.MUSIC -> AnimTag.SCENE in v.tags
            else -> false
        }
        return busy && clock() - currentStartedAt < MAX_SCENE_MS
    }

    /**
     * True while a scene or an active animation (butterfly, tail chasing, turning its back, the guitar,
     * the side dance…) plays in CALM, FIDGET or MUSIC: something that should stop at once when the car
     * starts moving.
     */
    fun isDistractingSceneRunning(): Boolean {
        val v = current ?: return false
        if (currentState != CompanionState.CALM && currentState != CompanionState.FIDGET &&
            currentState != CompanionState.MUSIC
        ) return false
        if (AnimTag.SCENE !in v.tags && AnimTag.ACTIVE !in v.tags) return false
        return clock() - currentStartedAt < MAX_SCENE_MS
    }

    /** Forgets running sequences and the current scene (profile or character switch). */
    fun reset() {
        armedNext = emptyMap()
        pendingNext.clear()
        current = null
        currentState = null
        // Fresh activity windows from now: nothing missed while hidden is played afterwards. Mood and stats stay.
        activity.reset(clock())
    }

    /** Dynamic weight of every available variant of [pool]. Exposed for tests. */
    internal fun weightsFor(
        pool: AnimationPool,
        isAvailable: (AnimationVariant) -> Boolean,
        now: Long = clock(),
        sceneGapMs: Long = MUSIC_SHOW_GAP_MS
    ): Map<AnimationVariant, Double> {
        val m = mood.snapshot()
        val ctx = context()
        val weights = LinkedHashMap<AnimationVariant, Double>()
        for (v in pool.variants) {
            if (!isAvailable(v)) continue
            weights[v] = dynamicWeight(v, m, ctx, now, sceneGapMs)
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

    private fun dynamicWeight(v: AnimationVariant, m: MoodSnapshot, ctx: PlayContext, now: Long, sceneGapMs: Long): Double {
        var w = v.weight.toDouble()
        if (AnimTag.BASE in v.tags) return w
        if (!carAllows(v, ctx)) return 0.0
        if (AnimTag.MAINTENANCE in v.tags && now - lastMaintenanceAt < MAINTENANCE_GAP_MS) return 0.0
        if (AnimTag.PREPARING in v.tags && ctx.trip == TripPhase.PREPARING) w *= PREPARING_FACTOR
        val e = m.energy / 100.0
        val a = m.affection / 100.0
        val c = m.curiosity / 100.0
        // Wave 6: a stronger pull of the mood (it was 2.6–0.4, 0.3–1.6, 0.4–2.2, 0.5–2.0).
        if (AnimTag.SLEEPY in v.tags) w *= lerp(3.0, 0.3, e)
        if (AnimTag.ACTIVE in v.tags) w *= lerp(0.25, 1.9, e)
        if (AnimTag.EXPLORE in v.tags) w *= lerp(0.3, 2.8, c)
        if (AnimTag.FRIENDLY in v.tags) {
            w *= lerp(0.35, 2.7, a)
            if (ctx.sinceInteractionMs < RECENT_INTERACTION_MS) w *= 1.5
        }

        if (ctx.moving) {
            if (AnimTag.SCENE in v.tags || AnimTag.ACTIVE in v.tags) return 0.0
            if (AnimTag.DRIVING !in v.tags) w *= MOVING_EXTRA_FACTOR
        }
        if (AnimTag.SCENE in v.tags && now - lastSceneAt < sceneGapMs) return 0.0
        if (v.minGapMs > 0) {
            val last = lastPlayedAt[v.resName]
            if (last != null && now - last < v.minGapMs) return 0.0
        }
        val repeats = history.count { it == v.resName }
        repeat(repeats) { w *= REPEAT_FACTOR }
        return w
    }

    /** Car-only conditions of a variant: profile, driving, standing still. */
    private fun carAllows(v: AnimationVariant, ctx: PlayContext): Boolean {
        if (AnimTag.CAR in v.tags && !ctx.car) return false
        if (AnimTag.DRIVING in v.tags && !ctx.moving) return false
        if (AnimTag.PARKED_ONLY in v.tags) {
            val preparing = AnimTag.PREPARING in v.tags && ctx.trip == TripPhase.PREPARING && !ctx.moving
            if (!ctx.parked && !preparing) return false
        }
        return true
    }

    private fun played(state: CompanionState, v: AnimationVariant, now: Long): AnimationVariant {
        history.addLast(v.resName)
        while (history.size > HISTORY_SIZE) history.removeFirst()
        lastPlayedAt[v.resName] = now
        if (AnimTag.SCENE in v.tags) lastSceneAt = now
        if (AnimTag.MAINTENANCE in v.tags) lastMaintenanceAt = now
        if (state == CompanionState.HEARTS || state == CompanionState.SHY) {
            if (v.resName == AnimationPools.HEART_TRANSFORM) finalesSinceHeart = 0 else finalesSinceHeart++
        }
        current = v
        currentState = state
        currentStartedAt = now
        mood.onPlayed(v.tags, moodShare(state, v))
        return v
    }

    /** How much a play changes the mood: self-started CALM movements and scenes less than reactions and shows. */
    private fun moodShare(state: CompanionState, v: AnimationVariant): Float =
        if (state != CompanionState.CALM) 1f else when (v.category) {
            ActivityCategory.AMBIENT -> 1f
            ActivityCategory.MICRO -> MICRO_MOOD_SHARE
            ActivityCategory.SCENE -> SCENE_MOOD_SHARE
        }

    private fun lerp(from: Double, to: Double, t: Double) = from + (to - from) * t.coerceIn(0.0, 1.0)

    companion object {
        /** Minimum share of [AnimTag.BASE] variants in a pool's total weight. */
        const val MIN_BASE_SHARE = 0.55

        /**
         * Music shows (and the scenes of the dance breaks) are at least this far apart. Self-started CALM scenes use
         * the gap of the user's [CompanionActivityMode] instead (the former global SCENE_GAP_MS).
         */
        const val MUSIC_SHOW_GAP_MS = 45_000L

        /** A scene longer than this is considered finished even without an end callback. */
        const val MAX_SCENE_MS = 8_000L

        /** Weight multiplier per recent play of the same variant (last [HISTORY_SIZE] picks). */
        const val REPEAT_FACTOR = 0.35
        const val HISTORY_SIZE = 10

        /** Non-basic extras while driving (scenes and active ones are off entirely). */
        const val MOVING_EXTRA_FACTOR = 0.6

        const val RECENT_INTERACTION_MS = 90_000L

        /** Car-care scenes (wrench, tyre, fuel, wash) are at least this far apart from each other. */
        const val MAINTENANCE_GAP_MS = 4 * 60_000L

        /** [AnimTag.PREPARING] variants while getting ready for a trip. */
        const val PREPARING_FACTOR = 3.0

        /** Mood effect of a self-started small movement / scene in CALM (a reaction or a show: 1). */
        const val MICRO_MOOD_SHARE = 0.04f
        const val SCENE_MOOD_SHARE = 0.3f

        /** Every this many petting finales at least one is the heart (counting from the last heart). */
        const val HEART_EVERY_PETS = 5
    }
}
