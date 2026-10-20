package com.damitrya.mimio.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressGestureTest {

    private val events = mutableListOf<String>()
    private val gesture = PressGesture(object : PressGesture.Listener {
        override fun onTap() { events += "tap" }
        override fun onPetStart() { events += "pet" }
        override fun onPetEnd() { events += "finale" }
        override fun onPetCancel() { events += "cancel" }
    })

    @Test
    fun shortPressIsATap() {
        gesture.down()
        gesture.move(1f, 2f)                       // a jitter below the slop
        assertFalse(gesture.up())
        assertEquals(listOf("tap"), events)
    }

    @Test
    fun heldAndReleasedIsPettingWithItsFinale() {
        gesture.down()
        gesture.longPress()
        gesture.move(2f, -2f)
        assertFalse(gesture.up())
        assertEquals(listOf("pet", "finale"), events)
    }

    @Test
    fun pettingThatTurnsIntoADragEndsWithoutAFinale() {
        gesture.down()
        gesture.longPress()
        assertTrue(gesture.move(30f, 0f))
        assertEquals("cancelled as soon as the drag starts", listOf("pet", "cancel"), events)
        gesture.move(80f, 40f)
        assertTrue("the drop saves the position", gesture.up())
        assertEquals(listOf("pet", "cancel"), events)
    }

    @Test
    fun dragWithoutHoldingIsNeitherTapNorPetting() {
        gesture.down()
        gesture.move(0f, 20f)
        gesture.longPress()                        // a late timer after the drag started
        assertTrue(gesture.up())
        assertTrue(events.isEmpty())
    }

    @Test
    fun systemCancelNeverTapsAndEndsPettingWithoutAFinale() {
        gesture.down()
        assertFalse(gesture.cancel())
        gesture.down()
        gesture.longPress()
        gesture.cancel()
        assertEquals(listOf("pet", "cancel"), events)
    }

    @Test
    fun oneMeaningPerGesture() {
        gesture.down()
        gesture.longPress()
        gesture.longPress()                        // the timer may not fire twice, but if it does
        gesture.up()
        assertEquals(listOf("pet", "finale"), events)
    }
}
