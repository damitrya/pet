package com.drivecompanion.data

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("drivecompanion_prefs", Context.MODE_PRIVATE)

    companion object {
        // Overlay
        const val KEY_CHARACTER_SIZE = "character_size"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"

        // Speed thresholds (km/h)
        const val KEY_THRESHOLD_WALK = "threshold_walk"
        const val KEY_THRESHOLD_CRUISE = "threshold_cruise"
        const val KEY_THRESHOLD_SPEED = "threshold_speed"
        const val KEY_THRESHOLD_TURBO = "threshold_turbo"

        // Toggles
        const val KEY_MUSIC_REACTION = "music_reaction_enabled"
        const val KEY_SENSOR_REACTION = "sensor_reaction_enabled"
        const val KEY_AUTO_START = "auto_start_enabled"
        const val KEY_SERVICE_ENABLED = "service_enabled"

        // Defaults
        const val DEFAULT_SIZE = 150
        const val DEFAULT_OPACITY = 100
        const val DEFAULT_THRESHOLD_WALK = 1f
        const val DEFAULT_THRESHOLD_CRUISE = 20f
        const val DEFAULT_THRESHOLD_SPEED = 80f
        const val DEFAULT_THRESHOLD_TURBO = 130f
    }

    var characterSize: Int
        get() = prefs.getInt(KEY_CHARACTER_SIZE, DEFAULT_SIZE)
        set(value) = prefs.edit().putInt(KEY_CHARACTER_SIZE, value).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, 0)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, 100)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    var overlayOpacity: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_OPACITY, value).apply()

    var thresholdWalk: Float
        get() = prefs.getFloat(KEY_THRESHOLD_WALK, DEFAULT_THRESHOLD_WALK)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD_WALK, value).apply()

    var thresholdCruise: Float
        get() = prefs.getFloat(KEY_THRESHOLD_CRUISE, DEFAULT_THRESHOLD_CRUISE)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD_CRUISE, value).apply()

    var thresholdSpeed: Float
        get() = prefs.getFloat(KEY_THRESHOLD_SPEED, DEFAULT_THRESHOLD_SPEED)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD_SPEED, value).apply()

    var thresholdTurbo: Float
        get() = prefs.getFloat(KEY_THRESHOLD_TURBO, DEFAULT_THRESHOLD_TURBO)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD_TURBO, value).apply()

    var musicReactionEnabled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_REACTION, true)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_REACTION, value).apply()

    var sensorReactionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENSOR_REACTION, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSOR_REACTION, value).apply()

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()
}
