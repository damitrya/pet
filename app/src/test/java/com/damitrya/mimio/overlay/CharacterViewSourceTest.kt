package com.damitrya.mimio.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the AnimatedImageDrawable fix of PR #22 (CharacterView is an Android view, so this checks its source):
 * every decoded drawable gets the one shared callback once, when it is decoded, and it is never removed —
 * clearAnimationCallbacks() / unregisterAnimationCallback() null the drawable's callback list, and a start/end
 * lambda the framework already posted then crashes with an NPE (postOnAnimationStart).
 */
class CharacterViewSourceTest {

    private val source = listOf(
        File("src/main/java/com/damitrya/mimio/overlay/CharacterView.kt"),
        File("app/src/main/java/com/damitrya/mimio/overlay/CharacterView.kt")
    ).first { it.isFile }.readText()

    /** The code without comments (the comments explain the crash and name the forbidden calls). */
    private val code = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        .joinToString("\n")

    @Test
    fun callbacksAreNeverRemoved() {
        assertFalse(code.contains("clearAnimationCallbacks("))
        assertFalse(code.contains("unregisterAnimationCallback("))
    }

    @Test
    fun theSharedCallbackIsRegisteredOnceWhenDecoded() {
        assertEquals(1, Regex("registerAnimationCallback\\(").findAll(code).count())
        val decode = code.substringAfter("private fun obtainDrawable").substringBefore("private ")
        assertTrue("registered right after decoding", decode.contains("registerAnimationCallback(animationCallback)"))
    }

    @Test
    fun endsAreFilteredNotUnregistered() {
        // A stale end (an older drawable, the replay after stop()) is ignored by identity, not by removing callbacks.
        assertTrue(code.contains("isRealEnd(d)"))
        assertTrue(code.contains("endAction === action"))
    }
}
