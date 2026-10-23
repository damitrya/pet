package com.damitrya.mimio.state

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What kind of self-started behaviour a CALM variant is. */
enum class ActivityCategory {
    /** Basic calm presence (breathing, looking around): [AnimTag.BASE]. */
    AMBIENT,
    /** Short movements and small changes of pose: tail, ears, head, a yawn, a sneeze. */
    MICRO,
    /** Self-started mini-scenes with a noticeable action: [AnimTag.SCENE]. */
    SCENE
}

/** The category of a CALM variant: BASE → AMBIENT, SCENE → SCENE, everything else → MICRO. */
val AnimationVariant.category: ActivityCategory
    get() = when {
        AnimTag.BASE in tags -> ActivityCategory.AMBIENT
        AnimTag.SCENE in tags -> ActivityCategory.SCENE
        else -> ActivityCategory.MICRO
    }

/** What the next CALM pick should be; [initiative] = the fox turns to the user on its own. */
data class ActivityDecision(val category: ActivityCategory, val initiative: Boolean = false)

/** Self-started behaviour of the current session (debug tools). No personal data: counters and times only. */
data class ActivityStats(
    val mode: CompanionActivityMode,
    val ambient: Int,
    val micro: Int,
    val scenes: Int,
    val skippedScenes: Int,
    val initiatives: Int,
    /** Time in MICRO / SCENE animations and in AMBIENT ones (blinks included), ms. */
    val activeMs: Long,
    val calmMs: Long,
    val sessionMs: Long,
    /** Until the next window opens (≤ 0: open now), ms. */
    val nextMicroInMs: Long,
    val nextSceneInMs: Long
) {
    val activeShare: Double get() = if (activeMs + calmMs > 0) activeMs.toDouble() / (activeMs + calmMs) else 0.0
}

/**
 * Plans the character's own behaviour in CALM by time, not by the number of animations played: at every CALM
 * pick (the end of the running animation — there is no timer and no polling here) it says whether the moment
 * is for basic calm ([ActivityCategory.AMBIENT]), a small movement ([ActivityCategory.MICRO]) or a mini-scene
 * ([ActivityCategory.SCENE]); [AnimationDirector] then picks the file inside that category.
 *
 * - **mode** ([CompanionActivityMode]) sets the windows; a change applies at the next pick.
 * - **mood** ([CharacterMood]) moves each window inside the mode's bounds: an energetic, curious fox gets its
 *   next scene closer to the lower bound, a tired one closer to the upper bound — never outside them.
 * - **no backlog**: a window is one timestamp. A missed or blocked scene is counted as skipped and a new window
 *   is planned; time in other states (music, a call, sleep, a hidden overlay) never piles up scenes.
 * - **calm in between**: after a scene the next [CALM_PICKS_AFTER_SCENE] picks are basic calm; after a reaction
 *   of any kind ([onOtherState]) the windows are pushed back a little.
 * - **car**: while the car moves there are no scenes and small movements follow the calm mode, whatever the
 *   user chose (the director also keeps its own rules for scenes and active play while driving).
 * - **initiative**: after a long time without a tap or petting a due window may become a friendly or playful
 *   action ([ActivityDecision.initiative]); never while driving and never from sleep (SLEEP has no CALM picks).
 *
 * Pure logic: time and randomness come from the caller. Not thread-safe: use from the main thread.
 */
class ActivityScheduler(
    private val mode: () -> CompanionActivityMode = { CompanionActivityMode.DEFAULT },
    private val random: Random = Random.Default,
    /** Debug log (MimioActivity); null in release builds, so no log text is even built. */
    private val log: ((String) -> Unit)? = null
) {
    private var plannedFor: CompanionActivityMode? = null
    private var nextMicroAt = 0L
    private var nextSceneAt = 0L
    private var lastSceneAt = NEVER
    private var lastActionAt = NEVER
    private var lastInitiativeAt = NEVER

    /** When the character was last started over (overlay shown, profile switch): initiative counts from here too. */
    private var restartedAt = NEVER

    /** Basic calm picks still owed after a scene. */
    private var calmPicksOwed = 0

    private var sessionStart = NEVER
    private var ambient = 0
    private var micro = 0
    private var scenes = 0
    private var skippedScenes = 0
    private var initiatives = 0
    private var activeMs = 0L
    private var calmMs = 0L

    /** The CALM / BLINK animation on screen, for the time shares (null: a reaction or nothing). */
    private var segment: ActivityCategory? = null
    private var segmentStart = 0L

    /** Decides what the next CALM pick is. [AnimationDirector] reports back with [onPicked] / [onSceneUnavailable]. */
    fun decide(now: Long, ctx: PlayContext, mood: MoodSnapshot): ActivityDecision {
        start(now)
        val m = mode()
        if (plannedFor != m) {
            log?.invoke("mode ${plannedFor ?: "-"} → $m")
            replan(now, m, mood)
        }
        if (calmPicksOwed > 0) {
            calmPicksOwed--
            return ActivityDecision(ActivityCategory.AMBIENT)
        }
        if (now >= nextSceneAt) {
            when {
                ctx.moving -> {
                    // Driving: this window is simply dropped; the next one comes a whole interval later.
                    skippedScenes++
                    nextSceneAt = now + interval(m.sceneMinMs, m.sceneMaxMs, mood)
                    log?.invoke("scene skipped: the car is moving; next scene window in ${(nextSceneAt - now) / 1000} s")
                }
                now - lastSceneAt < m.sceneGapMs -> nextSceneAt = lastSceneAt + m.sceneGapMs   // not missed: not yet
                else -> return ActivityDecision(ActivityCategory.SCENE, initiative(now, ctx, m, mood))
            }
        }
        if (now >= nextMicroAt) {
            return ActivityDecision(ActivityCategory.MICRO, initiative(now, ctx, m, mood))
        }
        return ActivityDecision(ActivityCategory.AMBIENT)
    }

    /** The scene window was open but nothing could play: counted, and a new window is planned (no queue). */
    fun onSceneUnavailable(now: Long, mood: MoodSnapshot, reason: String) {
        val m = mode()
        skippedScenes++
        // Not the full interval: the next chance comes after about one small-movement window.
        nextSceneAt = now + interval(m.microMinMs, m.microMaxMs, mood)
        log?.invoke("scene skipped: $reason; next scene window in ${(nextSceneAt - now) / 1000} s")
    }

    /** A CALM variant was picked (whatever the decision was: [category] is what actually plays). */
    fun onPicked(
        name: String,
        category: ActivityCategory,
        decision: ActivityDecision,
        now: Long,
        ctx: PlayContext,
        mood: MoodSnapshot
    ) {
        start(now)
        closeSegment(now)
        segment = category
        segmentStart = now
        val m = mode()
        val sinceAction = if (lastActionAt == NEVER) "-" else "${(now - lastActionAt) / 1000.0} s"
        when (category) {
            ActivityCategory.AMBIENT -> ambient++
            ActivityCategory.MICRO -> {
                micro++
                nextMicroAt = rhythmFrom(nextMicroAt, now) + microInterval(m, ctx, mood)
                lastActionAt = now
            }
            ActivityCategory.SCENE -> {
                scenes++
                lastSceneAt = now
                lastActionAt = now
                nextSceneAt = rhythmFrom(nextSceneAt, now) + interval(m.sceneMinMs, m.sceneMaxMs, mood)
                nextMicroAt = max(nextMicroAt, now + microInterval(m, ctx, mood))
                calmPicksOwed = CALM_PICKS_AFTER_SCENE
            }
        }
        val initiative = decision.initiative && category != ActivityCategory.AMBIENT
        if (initiative) {
            initiatives++
            lastInitiativeAt = now
        }
        if (category != ActivityCategory.AMBIENT || decision.category != ActivityCategory.AMBIENT) {
            log?.invoke(
                "$m ${moodText(mood)} wanted=${decision.category} played=$category $name" +
                    (if (initiative) " (initiative)" else "") +
                    " since last action $sinceAction; next micro in ${(nextMicroAt - now) / 1000} s," +
                    " next scene in ${(nextSceneAt - now) / 1000} s"
            )
        }
    }

    /** A blink: calm time, not a pick of the CALM pool. */
    fun onBlink(now: Long) {
        start(now)
        closeSegment(now)
        segment = ActivityCategory.AMBIENT
        segmentStart = now
    }

    /**
     * Any other state took over (a tap, petting, braking, music, a call, sleep…). The running CALM animation is
     * over, and the windows step back so that the fox first returns to calm — nothing that was missed meanwhile
     * is played afterwards.
     */
    fun onOtherState(now: Long) {
        closeSegment(now)
        segment = null
        if (plannedFor == null) return
        nextMicroAt = max(nextMicroAt, now + AFTER_REACTION_MICRO_MS)
        nextSceneAt = max(nextSceneAt, now + AFTER_REACTION_SCENE_MS)
    }

    /** Starts over (overlay shown again, profile switch): fresh windows from now; the session stats stay. */
    fun reset(now: Long) {
        closeSegment(now)
        segment = null
        plannedFor = null
        calmPicksOwed = 0
        restartedAt = now
    }

    fun stats(now: Long): ActivityStats {
        val open = if (segment != null) min(now - segmentStart, MAX_SEGMENT_MS) else 0L
        val active = segment != null && segment != ActivityCategory.AMBIENT
        return ActivityStats(
            mode = mode(),
            ambient = ambient, micro = micro, scenes = scenes, skippedScenes = skippedScenes,
            initiatives = initiatives,
            activeMs = activeMs + if (active) open else 0L,
            calmMs = calmMs + if (!active) open else 0L,
            sessionMs = if (sessionStart == NEVER) 0L else now - sessionStart,
            nextMicroInMs = if (plannedFor == null) 0L else nextMicroAt - now,
            nextSceneInMs = if (plannedFor == null) 0L else nextSceneAt - now
        )
    }

    private fun initiative(now: Long, ctx: PlayContext, m: CompanionActivityMode, mood: MoodSnapshot): Boolean {
        if (ctx.moving) return false
        val alone = min(ctx.sinceInteractionMs, now - restartedAt)
        if (alone < m.initiativeAfterMs) return false
        if (now - lastInitiativeAt < m.initiativeGapMs) return false
        // A fond, rested fox turns to the user more readily.
        val warmth = ((mood.affection + mood.energy) / 200.0).coerceIn(0.0, 1.0)
        return random.nextDouble() < m.initiativeChance * (0.6 + 0.8 * warmth)
    }

    private fun replan(now: Long, m: CompanionActivityMode, mood: MoodSnapshot) {
        plannedFor = m
        nextMicroAt = now + interval(m.microMinMs, m.microMaxMs, mood)
        nextSceneAt = now + interval(m.sceneMinMs, m.sceneMaxMs, mood)
    }

    /**
     * The next window counts from when this one opened, not from the pick that used it (the pick waits for the
     * end of the running calm animation) — but with at most [PICK_WAIT_CREDIT_MS] of credit, so a window that
     * stood open for long (a reaction, music, a hidden overlay) never brings the next one forward.
     */
    private fun rhythmFrom(openedAt: Long, now: Long): Long = max(openedAt, now - PICK_WAIT_CREDIT_MS)

    /** While the car moves, small movements follow the calm mode even if the user chose a livelier one. */
    private fun microInterval(m: CompanionActivityMode, ctx: PlayContext, mood: MoodSnapshot): Long {
        val r = if (ctx.moving) CompanionActivityMode.CALM else m
        return interval(r.microMinMs, r.microMaxMs, mood)
    }

    /** A random point of [minMs]–[maxMs], pulled toward the lower bound by a lively mood, never outside. */
    internal fun interval(minMs: Long, maxMs: Long, mood: MoodSnapshot): Long {
        val t = random.nextDouble() * (1 - MOOD_WEIGHT) + (1 - drive(mood)) * MOOD_WEIGHT
        return minMs + ((maxMs - minMs) * t.coerceIn(0.0, 1.0)).toLong()
    }

    private fun start(now: Long) {
        if (sessionStart == NEVER) sessionStart = now
        if (restartedAt == NEVER) restartedAt = now
    }

    private fun closeSegment(now: Long) {
        val s = segment ?: return
        val ms = min((now - segmentStart).coerceAtLeast(0L), MAX_SEGMENT_MS)
        if (s == ActivityCategory.AMBIENT) calmMs += ms else activeMs += ms
        segment = null
    }

    private fun moodText(m: MoodSnapshot) =
        "mood(e=${m.energy.toInt()} a=${m.affection.toInt()} c=${m.curiosity.toInt()} drive=${"%.2f".format(drive(m))})"

    companion object {
        private const val NEVER = Long.MIN_VALUE / 4

        /**
         * How strongly the mood places a window inside the mode's bounds (0: not at all, 1: fully). The rest is
         * chance, so even a tired fox sometimes plays sooner.
         */
        const val MOOD_WEIGHT = 0.6

        /** Basic calm picks after every scene: never two long scenes back to back. */
        const val CALM_PICKS_AFTER_SCENE = 2

        /** After a reaction (tap, petting, music, a call…) the windows open no earlier than this. */
        const val AFTER_REACTION_MICRO_MS = 3_000L
        const val AFTER_REACTION_SCENE_MS = 15_000L

        /** At most this much of the wait for a pick is credited to the next window (one basic calm animation). */
        const val PICK_WAIT_CREDIT_MS = 2_400L

        /** A segment longer than this (the overlay was hidden, an end got lost) counts only this long. */
        const val MAX_SEGMENT_MS = 8_000L

        /**
         * How lively the fox feels right now, 0–1 (0.5 at a neutral mood): mostly energy, some curiosity and
         * affection. It decides where in the mode's interval the next window opens.
         */
        fun drive(m: MoodSnapshot): Double {
            val e = m.energy / 100.0 - CharacterMood.NEUTRAL_ENERGY / 100.0
            val c = m.curiosity / 100.0 - CharacterMood.NEUTRAL_CURIOSITY / 100.0
            val a = m.affection / 100.0 - CharacterMood.NEUTRAL_AFFECTION / 100.0
            return (0.5 + 0.9 * e + 0.25 * c + 0.15 * a).coerceIn(0.0, 1.0)
        }
    }
}
