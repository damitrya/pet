package com.drivecompanion.state

import android.os.Handler
import android.os.Looper
import com.drivecompanion.data.AppProfile
import com.drivecompanion.data.AppUsageData
import com.drivecompanion.data.BatteryData
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.PhoneCallData
import com.drivecompanion.data.ScreenData
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.data.StepData
import com.drivecompanion.data.TimeData
import com.drivecompanion.data.WeatherData

/**
 * Finite state machine that determines the companion's current state.
 * Currently only supports the CALM state.
 */
class StateMachine(private val settings: SettingsRepository) {

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
        fun onWeatherModifierChanged(modifier: WeatherModifier)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.CALM
    private var currentWeatherModifier: WeatherModifier = WeatherModifier.NONE

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    fun setProfile(profile: AppProfile) {
        currentState = CompanionState.CALM
    }

    // Auto profile updates
    fun updateDrivingData(data: DrivingData) {}

    fun updateMediaData(data: MediaData) {}

    // Daily profile updates
    fun updateBatteryData(data: BatteryData) {}

    fun updateTimeData(data: TimeData) {}

    fun updateScreenData(data: ScreenData) {}

    fun updateStepData(data: StepData) {}

    fun updateWeatherData(data: WeatherData) {
        val newModifier = if (settings.weatherEnabled) data.modifier else WeatherModifier.NONE
        if (newModifier != currentWeatherModifier) {
            currentWeatherModifier = newModifier
            listener?.onWeatherModifierChanged(newModifier)
        }
    }

    fun updateAppUsageData(data: AppUsageData) {}

    fun updatePhoneCallData(data: PhoneCallData) {}
}
