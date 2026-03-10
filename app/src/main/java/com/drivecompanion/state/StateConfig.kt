package com.drivecompanion.state

/**
 * Interface defining state evaluation logic for a profile.
 * Each profile (Auto / Daily) implements its own state resolution.
 */
interface StateConfig {
    fun getAvailableStates(): Set<CompanionState>
    fun getIdleState(): CompanionState
    fun getSleepState(): CompanionState
}

class DriveStateConfig : StateConfig {
    override fun getAvailableStates(): Set<CompanionState> = setOf(
        CompanionState.CALM,
        CompanionState.IDLE,
        CompanionState.IDLE_SLEEP,
        CompanionState.WALKING,
        CompanionState.CRUISING,
        CompanionState.SPEED,
        CompanionState.TURBO,
        CompanionState.DANCING,
        CompanionState.BRAKING,
        CompanionState.TURNING
    )

    override fun getIdleState(): CompanionState = CompanionState.CALM
    override fun getSleepState(): CompanionState = CompanionState.IDLE_SLEEP
}

class DailyStateConfig : StateConfig {
    override fun getAvailableStates(): Set<CompanionState> = setOf(
        CompanionState.CALM,
        CompanionState.IDLE,
        CompanionState.IDLE_SLEEP,
        CompanionState.DANCING,
        CompanionState.MORNING,
        CompanionState.DAILY_WALKING,
        CompanionState.RUNNING,
        CompanionState.STEP_GOAL,
        CompanionState.CHARGING,
        CompanionState.LOW_BATTERY,
        CompanionState.NIGHT,
        CompanionState.PHONING,
        CompanionState.SCREEN_OFF,
        CompanionState.SCREEN_ON_HAPPY,
        CompanionState.APP_WATCH
    )

    override fun getIdleState(): CompanionState = CompanionState.CALM
    override fun getSleepState(): CompanionState = CompanionState.IDLE_SLEEP
}
