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
        CompanionState.CALM
    )

    override fun getIdleState(): CompanionState = CompanionState.CALM
    override fun getSleepState(): CompanionState = CompanionState.CALM
}

class DailyStateConfig : StateConfig {
    override fun getAvailableStates(): Set<CompanionState> = setOf(
        CompanionState.CALM
    )

    override fun getIdleState(): CompanionState = CompanionState.CALM
    override fun getSleepState(): CompanionState = CompanionState.CALM
}
