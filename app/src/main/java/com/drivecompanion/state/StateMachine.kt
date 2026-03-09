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
import com.drivecompanion.data.TimePeriod
import com.drivecompanion.data.WeatherData

/**
 * Finite state machine that determines the companion's current state
 * based on sensor data, media state, and daily activity data.
 *
 * Supports two profiles:
 * - Auto: Braking/Turning → Turbo → Speed → Dancing → Cruising → Walking → Idle
 * - Daily: Phoning → StepGoal → Running → Dancing → Walking → LowBattery → Charging → AppWatch → Morning → Night → Idle
 */
class StateMachine(private val settings: SettingsRepository) {

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
        fun onWeatherModifierChanged(modifier: WeatherModifier)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.IDLE
    private var currentProfile: AppProfile = AppProfile.DAILY
    private var currentWeatherModifier: WeatherModifier = WeatherModifier.NONE

    // Auto profile data
    private var currentDrivingData: DrivingData = DrivingData()
    private var currentMediaData: MediaData = MediaData()

    // Daily profile data
    private var currentBatteryData: BatteryData = BatteryData()
    private var currentTimeData: TimeData = TimeData()
    private var currentScreenData: ScreenData = ScreenData()
    private var currentStepData: StepData = StepData()
    private var currentWeatherData: WeatherData = WeatherData()
    private var currentAppUsageData: AppUsageData = AppUsageData()
    private var currentPhoneCallData: PhoneCallData = PhoneCallData()

    private val handler = Handler(Looper.getMainLooper())
    private var idleStartTime: Long = 0L
    private var sleepThresholdMs: Long = 30_000L

    // Temporary state duration tracking
    private var temporaryStateEndTime: Long = 0L
    private val temporaryStateDurationMs: Long = 2500L

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    fun setProfile(profile: AppProfile) {
        currentProfile = profile
        currentState = CompanionState.IDLE
        idleStartTime = 0L
        evaluateState()
    }

    // Auto profile updates
    fun updateDrivingData(data: DrivingData) {
        currentDrivingData = data
        if (currentProfile == AppProfile.AUTO) evaluateState()
    }

    fun updateMediaData(data: MediaData) {
        currentMediaData = data
        evaluateState()
    }

    // Daily profile updates
    fun updateBatteryData(data: BatteryData) {
        currentBatteryData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    fun updateTimeData(data: TimeData) {
        currentTimeData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    fun updateScreenData(data: ScreenData) {
        currentScreenData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    fun updateStepData(data: StepData) {
        currentStepData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    fun updateWeatherData(data: WeatherData) {
        currentWeatherData = data
        val newModifier = if (settings.weatherEnabled) data.modifier else WeatherModifier.NONE
        if (newModifier != currentWeatherModifier) {
            currentWeatherModifier = newModifier
            listener?.onWeatherModifierChanged(newModifier)
        }
    }

    fun updateAppUsageData(data: AppUsageData) {
        currentAppUsageData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    fun updatePhoneCallData(data: PhoneCallData) {
        currentPhoneCallData = data
        if (currentProfile == AppProfile.DAILY) evaluateState()
    }

    private fun evaluateState() {
        val newState = when (currentProfile) {
            AppProfile.AUTO -> evaluateAutoState()
            AppProfile.DAILY -> evaluateDailyState()
        }

        if (newState != currentState) {
            val previous = currentState
            currentState = newState
            if (newState != CompanionState.IDLE && newState != CompanionState.IDLE_SLEEP) {
                idleStartTime = 0L
            }
            listener?.onStateChanged(newState, previous)
        }
    }

    private fun evaluateAutoState(): CompanionState {
        val now = System.currentTimeMillis()
        val speed = currentDrivingData.speedKmh

        // Check if we're in a temporary state that hasn't expired
        if (now < temporaryStateEndTime &&
            (currentState == CompanionState.BRAKING || currentState == CompanionState.TURNING)
        ) {
            return currentState
        }

        // Priority 1: Braking (sensor-based, temporary)
        if (settings.sensorReactionEnabled && currentDrivingData.isBraking) {
            temporaryStateEndTime = now + temporaryStateDurationMs
            return CompanionState.BRAKING
        }
        // Priority 1: Turning (sensor-based, temporary)
        if (settings.sensorReactionEnabled && currentDrivingData.isTurning) {
            temporaryStateEndTime = now + temporaryStateDurationMs
            return CompanionState.TURNING
        }
        // Priority 2: Turbo
        if (speed > settings.thresholdTurbo || currentDrivingData.isRapidAcceleration) {
            return CompanionState.TURBO
        }
        // Priority 3: Speed
        if (speed > settings.thresholdSpeed) {
            return CompanionState.SPEED
        }
        // Priority 4: Dancing
        if (settings.musicReactionEnabled && currentMediaData.isPlaying && speed < settings.thresholdCruise) {
            return CompanionState.DANCING
        }
        // Priority 5: Cruising
        if (speed > settings.thresholdCruise) {
            return CompanionState.CRUISING
        }
        // Priority 6: Walking
        if (speed > settings.thresholdWalk) {
            return CompanionState.WALKING
        }
        // Priority 7: Idle
        return resolveIdleState()
    }

    /**
     * Daily profile priority:
     * Phoning → StepGoal → Running → Dancing → Walking → LowBattery → Charging → AppWatch → Morning → Night → Idle
     */
    private fun evaluateDailyState(): CompanionState {
        // Priority 1: Phone call
        if (currentPhoneCallData.isInCall) {
            return CompanionState.PHONING
        }
        // Priority 2: Step goal just reached (one-shot celebration)
        if (currentStepData.goalReached) {
            return CompanionState.STEP_GOAL
        }
        // Priority 3: Running
        if (currentStepData.isRunning) {
            return CompanionState.RUNNING
        }
        // Priority 4: Dancing (music playing)
        if (settings.musicReactionEnabled && currentMediaData.isPlaying) {
            return CompanionState.DANCING
        }
        // Priority 5: Walking
        if (currentStepData.isWalking) {
            return CompanionState.DAILY_WALKING
        }
        // Priority 6: Low battery
        if (currentBatteryData.isLow) {
            return CompanionState.LOW_BATTERY
        }
        // Priority 7: Charging
        if (currentBatteryData.isCharging) {
            return CompanionState.CHARGING
        }
        // Priority 8: App watch (social media overuse)
        if (currentAppUsageData.isSocialMediaOveruse) {
            return CompanionState.APP_WATCH
        }
        // Priority 9: Screen off for > 30 min
        if (!currentScreenData.isScreenOn && currentScreenData.screenOffDurationMs > 30 * 60 * 1000L) {
            return CompanionState.SCREEN_OFF
        }
        // Screen just turned on after long absence
        if (currentScreenData.justTurnedOn) {
            return CompanionState.SCREEN_ON_HAPPY
        }
        // Priority 10: Morning greeting
        if (currentTimeData.isFirstUnlockOfDay && currentTimeData.period == TimePeriod.MORNING) {
            return CompanionState.MORNING
        }
        // Priority 11: Night
        if (currentTimeData.period == TimePeriod.NIGHT) {
            return CompanionState.NIGHT
        }
        // Priority 12: Idle
        return resolveIdleState()
    }

    private fun resolveIdleState(): CompanionState {
        val now = System.currentTimeMillis()
        if (currentState != CompanionState.IDLE && currentState != CompanionState.IDLE_SLEEP) {
            idleStartTime = now
        }
        return if (now - idleStartTime > sleepThresholdMs) {
            CompanionState.IDLE_SLEEP
        } else {
            CompanionState.IDLE
        }
    }
}
