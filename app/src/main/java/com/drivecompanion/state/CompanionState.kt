package com.drivecompanion.state

/**
 * All possible states of the companion character.
 * States are used across both Auto and Daily profiles.
 *
 * States with [rawVideoResName] are played as animated WebP; the actual file is chosen
 * from [AnimationPools] (rawVideoResName is the default variant).
 */
enum class CompanionState(
    val animationAsset: String,
    val isLooping: Boolean,
    val rawVideoResName: String? = null
) {
    CALM("", true, "idle_small"),
    BLINK("", false, "blink"),
    MUSIC("", true, "music"),

    /** One-shot tap reaction. Returns to the previous looping state (CALM or MUSIC) when done. */
    TAP("", false, "tap"),

    /**
     * One-shot high-priority alert triggered by sharp braking.
     * Immediately interrupts any current state and returns to the previous looping state when done.
     */
    ALERT("", false, "alert"),

    /**
     * Looping petting reaction triggered by a long press (≥ 600 ms).
     * Plays until the finger is released, then returns to the previous looping state (CALM or MUSIC).
     */
    PET("", true, "pet"),

    /** One-shot reaction to tap spam (≥ 5 taps within 3 s). Replaces TAP, then returns to the looping state. */
    ANNOYED("", false, "annoyed"),

    /** One-shot finale after petting: hearts when the long press is released. */
    HEARTS("", false, "hearts"),

    /** One-shot that follows ALERT: the character calms down. */
    RELIEF("", false, "relief"),

    /** One-shot greeting when the overlay appears. */
    GREETING("", false, "greeting"),

    /** One-shot joy when the car starts moving after a standstill (Auto profile). */
    TRIP_START("", false, "trip_start"),

    /** One-shot transition into SLEEP after a long period without interaction. */
    FALL_ASLEEP("", false, "fall_asleep"),

    /** Looping sleep. Left via WAKE on tap / long press / music, or directly by ALERT and TRIP_START. */
    SLEEP("", true, "sleep"),

    /** One-shot waking up, then returns to the looping state (CALM or MUSIC). */
    WAKE("", false, "wake");
}

/**
 * Weather modifier overlays applied on top of the main animation.
 */
enum class WeatherModifier(val overlayAsset: String) {
    NONE(""),
    RAIN("weather_rain.json"),
    SUN("weather_sun.json"),
    COLD("weather_cold.json"),
    SNOW("weather_snow.json"),
    WIND("weather_wind.json");
}
