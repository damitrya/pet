package com.drivecompanion.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnimationPoolTest {

    private val a = AnimationVariant("a", weight = 1)
    private val b = AnimationVariant("b", weight = 1, cooldown = 2)
    private val c = AnimationVariant("c", weight = 1)

    @Test
    fun dynamicWeightsShiftTheChoice() {
        val pool = AnimationPool(listOf(a, c), Random(1))
        val counts = HashMap<String, Int>()
        repeat(2000) {
            val v = pool.pick(weightOf = { if (it == a) 9.0 else 1.0 })!!
            counts.merge(v.resName, 1, Int::plus)
        }
        val shareA = counts.getValue("a") / 2000.0
        assertTrue("share of a = $shareA", shareA in 0.85..0.95)
    }

    @Test
    fun zeroWeightIsNeverPickedWhileOthersAreAvailable() {
        val pool = AnimationPool(listOf(a, c), Random(2))
        repeat(500) { assertEquals("c", pool.pick(weightOf = { if (it == a) 0.0 else 1.0 })!!.resName) }
    }

    @Test
    fun allZeroWeightsFallBackToTheFirstAvailable() {
        val pool = AnimationPool(listOf(a, c), Random(3))
        assertEquals("a", pool.pick(weightOf = { 0.0 })!!.resName)
        assertEquals("c", pool.pick(isAvailable = { it == c }, weightOf = { 0.0 })!!.resName)
    }

    @Test
    fun cooldownSkipsFollowingPicks() {
        val pool = AnimationPool(listOf(a, b), Random(4))
        // Force b, then it must sit out the next two picks.
        assertEquals("b", pool.pick(weightOf = { if (it == b) 1.0 else 0.0 })!!.resName)
        assertEquals("a", pool.pick()!!.resName)
        assertEquals("a", pool.pick()!!.resName)
    }

    @Test
    fun unavailablePoolReturnsNull() {
        assertNull(AnimationPool(listOf(a)).pick(isAvailable = { false }))
    }
}
