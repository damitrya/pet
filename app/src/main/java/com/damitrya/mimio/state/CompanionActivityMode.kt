package com.damitrya.mimio.state

/**
 * How lively the character is on its own (settings → "Character activity"). Not to be confused with
 * [CompanionState.CALM]: that is a state of the machine, this is the user's choice of temperament.
 *
 * The intervals are the wished-for rhythm of opportunities, not a timer: [ActivityScheduler] offers a small
 * movement or a scene once its window is open, and only at the end of the running calm animation, only in CALM
 * and only if a fitting animation is free (cooldown, minimum gap). Missed windows are never queued.
 * [CharacterMood] moves each window inside its bounds — never outside them.
 */
enum class CompanionActivityMode(
    /** Small movements (MICRO): a new one every [microMinMs]–[microMaxMs]. */
    val microMinMs: Long,
    val microMaxMs: Long,
    /** Self-started mini-scenes (SCENE): every [sceneMinMs]–[sceneMaxMs]. */
    val sceneMinMs: Long,
    val sceneMaxMs: Long,
    /**
     * Two self-started CALM scenes are never closer than this, whatever the mood (replaces the former global
     * SCENE_GAP_MS for CALM; music shows keep [AnimationDirector.MUSIC_SHOW_GAP_MS]).
     */
    val sceneGapMs: Long,
    /** After this long without a tap or petting the fox may show some initiative of its own. */
    val initiativeAfterMs: Long,
    /** At most one initiative per this period. */
    val initiativeGapMs: Long,
    /** Chance that a due window becomes an initiative (before the mood factor). */
    val initiativeChance: Double,
    /** DAILY profile without any activity: drowsy once, then dozing off ([StateMachine]; AUTO has its own limits). */
    val drowsyAfterMs: Long,
    val sleepAfterMs: Long
) {
    CALM(
        microMinMs = 10_000L, microMaxMs = 20_000L,
        sceneMinMs = 120_000L, sceneMaxMs = 240_000L,
        sceneGapMs = 100_000L,
        initiativeAfterMs = 120_000L, initiativeGapMs = 5 * 60_000L, initiativeChance = 0.15,
        drowsyAfterMs = 2 * 60_000L, sleepAfterMs = 3 * 60_000L
    ),
    LIVELY(
        microMinMs = 5_000L, microMaxMs = 10_000L,
        sceneMinMs = 45_000L, sceneMaxMs = 90_000L,
        sceneGapMs = 40_000L,
        initiativeAfterMs = 90_000L, initiativeGapMs = 3 * 60_000L, initiativeChance = 0.3,
        drowsyAfterMs = 4 * 60_000L, sleepAfterMs = 5 * 60_000L
    ),
    PLAYFUL(
        microMinMs = 3_000L, microMaxMs = 7_000L,
        sceneMinMs = 25_000L, sceneMaxMs = 60_000L,
        sceneGapMs = 22_000L,
        initiativeAfterMs = 60_000L, initiativeGapMs = 2 * 60_000L, initiativeChance = 0.45,
        drowsyAfterMs = 7 * 60_000L, sleepAfterMs = 8 * 60_000L
    );

    companion object {
        /** "Lively" — also after an update from a version without this setting. */
        val DEFAULT = LIVELY

        fun parse(name: String?): CompanionActivityMode? = values().firstOrNull { it.name == name }
    }
}
