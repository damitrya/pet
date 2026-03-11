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

        // Profile
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_PROFILE_MODE = "profile_mode"
        const val KEY_BLUETOOTH_DEVICE_ADDRESS = "bluetooth_device_address"
        const val KEY_BLUETOOTH_DEVICE_NAME = "bluetooth_device_name"

        // Daily profile settings
        const val KEY_STEP_GOAL = "step_goal"
        const val KEY_WEATHER_ENABLED = "weather_enabled"
        const val KEY_APP_WATCH_THRESHOLD = "app_watch_threshold_minutes"
        const val KEY_MONITORED_APPS = "monitored_apps"
        const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"

        // Time ranges
        const val KEY_MORNING_START = "morning_start"
        const val KEY_DAY_START = "day_start"
        const val KEY_EVENING_START = "evening_start"
        const val KEY_NIGHT_START = "night_start"

        // Alert braking detection
        const val KEY_ALERT_BRAKING_THRESHOLD = "alert_braking_threshold"
        const val DEFAULT_ALERT_BRAKING_THRESHOLD = -4.0f

        // Defaults
        const val DEFAULT_SIZE = 150
        const val DEFAULT_OPACITY = 100
        const val DEFAULT_THRESHOLD_WALK = 1f
        const val DEFAULT_THRESHOLD_CRUISE = 20f
        const val DEFAULT_THRESHOLD_SPEED = 80f
        const val DEFAULT_THRESHOLD_TURBO = 130f
        const val DEFAULT_STEP_GOAL = 10000
        const val DEFAULT_APP_WATCH_THRESHOLD = 30
        const val DEFAULT_LOW_BATTERY_THRESHOLD = 15
    }

    // Overlay settings
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

    // Speed thresholds
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

    // Toggles
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

    // Profile settings
    var activeProfile: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE, "DAILY") ?: "DAILY"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    /** "manual_auto", "manual_daily", "auto_bluetooth" */
    var profileMode: String
        get() = prefs.getString(KEY_PROFILE_MODE, "manual_daily") ?: "manual_daily"
        set(value) = prefs.edit().putString(KEY_PROFILE_MODE, value).apply()

    var bluetoothDeviceAddress: String
        get() = prefs.getString(KEY_BLUETOOTH_DEVICE_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BLUETOOTH_DEVICE_ADDRESS, value).apply()

    var bluetoothDeviceName: String
        get() = prefs.getString(KEY_BLUETOOTH_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BLUETOOTH_DEVICE_NAME, value).apply()

    // Daily profile settings
    var stepGoal: Int
        get() = prefs.getInt(KEY_STEP_GOAL, DEFAULT_STEP_GOAL)
        set(value) = prefs.edit().putInt(KEY_STEP_GOAL, value).apply()

    var weatherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_ENABLED, value).apply()

    var appWatchThresholdMinutes: Int
        get() = prefs.getInt(KEY_APP_WATCH_THRESHOLD, DEFAULT_APP_WATCH_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_APP_WATCH_THRESHOLD, value).apply()

    var monitoredApps: Set<String>
        get() = prefs.getStringSet(KEY_MONITORED_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_MONITORED_APPS, value).apply()

    var lowBatteryThreshold: Int
        get() = prefs.getInt(KEY_LOW_BATTERY_THRESHOLD, DEFAULT_LOW_BATTERY_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_LOW_BATTERY_THRESHOLD, value).apply()

    /** Acceleration threshold (m/s²) on axis X below which ALERT is triggered. Negative value. */
    var alertBrakingThreshold: Float
        get() = prefs.getFloat(KEY_ALERT_BRAKING_THRESHOLD, DEFAULT_ALERT_BRAKING_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_ALERT_BRAKING_THRESHOLD, value).apply()

    // Time ranges (hours)
    var morningStart: Int
        get() = prefs.getInt(KEY_MORNING_START, 6)
        set(value) = prefs.edit().putInt(KEY_MORNING_START, value).apply()

    var dayStart: Int
        get() = prefs.getInt(KEY_DAY_START, 10)
        set(value) = prefs.edit().putInt(KEY_DAY_START, value).apply()

    var eveningStart: Int
        get() = prefs.getInt(KEY_EVENING_START, 18)
        set(value) = prefs.edit().putInt(KEY_EVENING_START, value).apply()

    var nightStart: Int
        get() = prefs.getInt(KEY_NIGHT_START, 22)
        set(value) = prefs.edit().putInt(KEY_NIGHT_START, value).apply()
}
