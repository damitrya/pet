package com.drivecompanion.state

import android.os.Handler
import android.os.Looper
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.SettingsRepository

/**
 * Finite state machine that determines the companion's current state
 * based on driving data and media state.
 *
 * Priority (highest to lowest):
 * Braking/Turning → Turbo → Speed → Dancing → Cruising → Walking → Idle
 */
class StateMachine(private val settings: SettingsRepository) {

    interface Listener {
        fun onStateChanged(newState: CompanionState, previousState: CompanionState)
    }

    private var listener: Listener? = null
    private var currentState: CompanionState = CompanionState.IDLE
    private var currentDrivingData: DrivingData = DrivingData()
    private var currentMediaData: MediaData = MediaData()

    private val handler = Handler(Looper.getMainLooper())
    private var idleStartTime: Long = 0L
    private var sleepThresholdMs: Long = 30_000L  // 30 seconds

    // Temporary state duration tracking
    private var temporaryStateEndTime: Long = 0L
    private val temporaryStateDurationMs: Long = 2500L  // 2.5 seconds for braking/turning

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentState(): CompanionState = currentState

    fun updateDrivingData(data: DrivingData) {
        currentDrivingData = data
        evaluateState()
    }

    fun updateMediaData(data: MediaData) {
        currentMediaData = data
        evaluateState()
    }

    private fun evaluateState() {
        val now = System.currentTimeMillis()
        val speed = currentDrivingData.speedKmh
        val newState: CompanionState

        // Check if we're in a temporary state that hasn't expired
        if (now < temporaryStateEndTime &&
            (currentState == CompanionState.BRAKING || currentState == CompanionState.TURNING)
        ) {
            return  // Keep temporary state active
        }

        // Priority 1: Braking (sensor-based, temporary)
        if (settings.sensorReactionEnabled && currentDrivingData.isBraking) {
            newState = CompanionState.BRAKING
            temporaryStateEndTime = now + temporaryStateDurationMs
        }
        // Priority 1: Turning (sensor-based, temporary)
        else if (settings.sensorReactionEnabled && currentDrivingData.isTurning) {
            newState = CompanionState.TURNING
            temporaryStateEndTime = now + temporaryStateDurationMs
        }
        // Priority 2: Turbo (very high speed or rapid acceleration)
        else if (speed > settings.thresholdTurbo || currentDrivingData.isRapidAcceleration) {
            newState = CompanionState.TURBO
        }
        // Priority 3: Speed (highway)
        else if (speed > settings.thresholdSpeed) {
            newState = CompanionState.SPEED
        }
        // Priority 4: Dancing (music playing)
        else if (settings.musicReactionEnabled && currentMediaData.isPlaying && speed < settings.thresholdCruise) {
            newState = CompanionState.DANCING
        }
        // Priority 5: Cruising (city driving)
        else if (speed > settings.thresholdCruise) {
            newState = CompanionState.CRUISING
        }
        // Priority 6: Walking (slow movement)
        else if (speed > settings.thresholdWalk) {
            newState = CompanionState.WALKING
        }
        // Priority 7: Idle
        else {
            if (currentState != CompanionState.IDLE && currentState != CompanionState.IDLE_SLEEP) {
                idleStartTime = now
            }
            newState = if (now - idleStartTime > sleepThresholdMs) {
                CompanionState.IDLE_SLEEP
            } else {
                CompanionState.IDLE
            }
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
}
