package com.drivecompanion.state

/**
 * All possible states of the companion character.
 * Ordered by priority (higher ordinal = lower priority in the enum,
 * but priority is handled explicitly in StateMachine).
 */
enum class CompanionState(val animationAsset: String, val isLooping: Boolean) {
    IDLE("idle_bored.json", true),
    IDLE_SLEEP("idle_sleep.json", true),
    WALKING("walk.json", true),
    CRUISING("cruise_happy.json", true),
    SPEED("speed_excited.json", true),
    TURBO("turbo_scared.json", true),
    DANCING("dance_generic.json", true),
    BRAKING("brake_reaction.json", false),
    TURNING("turn_lean.json", false);
}
