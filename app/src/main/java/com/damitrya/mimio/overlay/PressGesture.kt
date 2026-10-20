package com.damitrya.mimio.overlay

/**
 * What one finger on the overlay means: a tap, petting (a long press) or moving the character.
 * Pure logic for [OverlayManager]'s touch listener, so every gesture ends in exactly one meaning:
 *
 * - short press without moving → [Listener.onTap];
 * - held for the long-press delay → [Listener.onPetStart]; released without moving →
 *   [Listener.onPetEnd] (the HEARTS / SHY finale);
 * - the finger moves past [SLOP_PX] → a drag. A petting that turns into a drag is cancelled
 *   ([Listener.onPetCancel], no finale): moving the character is not a reaction to petting;
 * - a gesture cancelled by the system never taps and cancels a petting.
 */
class PressGesture(private val listener: Listener) {

    interface Listener {
        fun onTap()
        fun onPetStart()
        fun onPetEnd()
        fun onPetCancel()
    }

    var isDragging = false
        private set
    var isPetting = false
        private set

    /** Finger down; the caller arms the long-press timer. */
    fun down() {
        isDragging = false
        isPetting = false
    }

    /** The long-press timer fired (the caller removes it as soon as a drag starts). */
    fun longPress() {
        if (isDragging || isPetting) return
        isPetting = true
        listener.onPetStart()
    }

    /** Finger moved by (dx, dy) px from where it went down. Returns true once this is a drag. */
    fun move(dx: Float, dy: Float): Boolean {
        if (!isDragging && dx * dx + dy * dy > SLOP_PX * SLOP_PX) {
            isDragging = true
            if (isPetting) {
                isPetting = false
                listener.onPetCancel()
            }
        }
        return isDragging
    }

    /** Finger lifted. Returns true if it was a drag (the caller saves the new position). */
    fun up(): Boolean {
        when {
            isPetting -> listener.onPetEnd()
            !isDragging -> listener.onTap()
        }
        isPetting = false
        return isDragging
    }

    /** The system took the gesture away. Returns true if it was a drag. */
    fun cancel(): Boolean {
        if (isPetting) listener.onPetCancel()
        isPetting = false
        val dragged = isDragging
        isDragging = false
        return dragged
    }

    companion object {
        /** Movement that turns a press into a drag (the same 5 px as before). */
        const val SLOP_PX = 5f
    }
}
