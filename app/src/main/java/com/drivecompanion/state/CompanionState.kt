package com.drivecompanion.state

/**
 * All possible states of the companion character.
 * States are used across both Auto and Daily profiles.
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
    TAP("", false, "tap");
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
