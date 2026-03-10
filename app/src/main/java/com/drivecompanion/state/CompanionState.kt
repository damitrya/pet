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
    // Shared states
    CALM("", true, "idle_small"),
    IDLE("idle_bored.json", true),
    IDLE_SLEEP("idle_sleep.json", true),
    DANCING("dance_generic.json", true),

    // Auto profile states
    WALKING("walk.json", true),
    CRUISING("cruise_happy.json", true),
    SPEED("speed_excited.json", true),
    TURBO("turbo_scared.json", true),
    BRAKING("brake_reaction.json", false),
    TURNING("turn_lean.json", false),

    // Daily profile states
    MORNING("morning_stretch.json", false),
    DAILY_WALKING("walk_steps.json", true),
    RUNNING("run_fast.json", true),
    STEP_GOAL("step_goal_celebrate.json", false),
    CHARGING("charging_happy.json", true),
    LOW_BATTERY("low_battery_panic.json", true),
    NIGHT("night_sleep.json", true),
    PHONING("phone_call.json", true),
    SCREEN_OFF("screen_off_waiting.json", true),
    SCREEN_ON_HAPPY("screen_on_happy.json", false),
    APP_WATCH("app_watch_bored.json", true);
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
