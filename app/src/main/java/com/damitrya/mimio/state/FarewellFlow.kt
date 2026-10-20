package com.damitrya.mimio.state

/**
 * The user's "Hide" / "Show" (notification buttons) around the farewell, kept out of the service so it
 * can be tested without Android:
 *
 * - Hide → the fox turns into a heart ([StateMachine.startFarewell]); [hideNow] runs once it is over,
 *   or after the state machine's safety timeout, or at once when nothing can play (braking, a sharp
 *   turn, asleep) or Hide is pressed again;
 * - Show during the farewell → it is dropped, the overlay stays, and [hideNow] never runs for it;
 * - the overlay going away for another reason (the service stops) → [release]: nothing runs later.
 */
class FarewellFlow(
    private val stateMachine: StateMachine,
    private val isVisible: () -> Boolean,
    private val hideNow: () -> Unit,
    private val showNow: () -> Unit
) {
    fun onHidePressed() {
        if (!isVisible()) return
        if (stateMachine.isFarewellRunning() || !stateMachine.startFarewell { hideNow() }) {
            stateMachine.cancelFarewell()
            hideNow()
        }
    }

    fun onShowPressed() {
        stateMachine.cancelFarewell()
        showNow()
    }

    /** The overlay or the service goes away: a pending farewell must not hide (or notify) later. */
    fun release() = stateMachine.cancelFarewell()
}
