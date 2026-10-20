package com.drivecompanion.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Checks the animation files in res/raw against the pools (run from the module or the project dir). */
class AnimationResourcesTest {

    private val rawDir = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
    private val files = rawDir.listFiles()!!.map { it.name.removeSuffix(".webp") }.toSet()
    private val pools = AnimationPools.create()

    private fun allNames(): Set<String> =
        pools.values.flatMap { p -> p.variants.flatMap { listOf(it.resName) + it.next.values } }.toSet()

    @Test
    fun foxHasEveryAnimationOfThePools() {
        val missing = allNames().filter { "fox_$it" !in files }
        assertTrue("missing fox files: $missing", missing.isEmpty())
    }

    @Test
    fun slimeFilesMatchTheTestHelper() {
        assertEquals(AnimationDirectorTest.SLIME_FILES, files.filter { !it.startsWith("fox_") }.toSet())
    }

    @Test
    fun slimeHasSomethingToPlayInEveryState() {
        for ((state, pool) in pools) {
            val playable = pool.variants.filter(AnimationDirectorTest.SLIME)
            assertTrue("$state: nothing the slime can play", playable.isNotEmpty())
            playable.forEach { v ->
                val file = if (v.resName in files) v.resName else v.fallback
                assertTrue("$state/${v.resName} → $file", file in files)
            }
        }
    }

    @Test
    fun newFoxAnimationsAreCompatibleAnimatedWebp() {
        for (name in WAVE3) {
            val info = readWebp(File(rawDir, "fox_$name.webp"))
            assertEquals("$name width", 128, info.width)
            assertEquals("$name height", 128, info.height)
            assertTrue("$name is animated", info.animated)
            assertTrue("$name has alpha", info.alpha)
            assertTrue("$name frames ${info.frames}", info.frames >= 40)
            assertEquals("$name loops (the view sets the repeat count itself)", 0, info.loopCount)
        }
    }

    @Test
    fun animationsEndInTime() {
        // One-shots must end well before the state machine's watchdog, idle scenes before
        // the director stops treating them as running.
        for ((state, pool) in pools) {
            for (v in pool.variants) {
                val f = File(rawDir, "fox_${v.resName}.webp")
                if (!f.exists()) continue
                val ms = readWebp(f).durationMs
                if (!state.isLooping) assertTrue("$state/${v.resName}: $ms ms", ms < StateMachine.ONE_SHOT_TIMEOUT_MS)
                if (state == CompanionState.CALM && AnimTag.BASE !in v.tags) {
                    assertTrue("${v.resName}: $ms ms", ms <= AnimationDirector.MAX_SCENE_MS)
                }
            }
        }
        for (name in listOf("curl_up", "wake_curled")) {
            assertTrue(readWebp(File(rawDir, "fox_$name.webp")).durationMs < StateMachine.ONE_SHOT_TIMEOUT_MS)
        }
    }

    private data class WebpInfo(
        val width: Int, val height: Int, val animated: Boolean, val alpha: Boolean,
        val frames: Int, val durationMs: Long, val loopCount: Int
    )

    private fun readWebp(file: File): WebpInfo {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
        fun u24(at: Int) = (b.get(at).toInt() and 0xff) or ((b.get(at + 1).toInt() and 0xff) shl 8) or
            ((b.get(at + 2).toInt() and 0xff) shl 16)
        assertEquals("RIFF", tag(0))
        assertEquals("WEBP", tag(8))
        var pos = 12
        var width = 0; var height = 0; var flags = 0; var frames = 0; var duration = 0L; var loops = -1
        while (pos + 8 <= b.limit()) {
            val chunk = tag(pos)
            val size = b.getInt(pos + 4)
            val data = pos + 8
            when (chunk) {
                "VP8X" -> { flags = b.get(data).toInt(); width = u24(data + 4) + 1; height = u24(data + 7) + 1 }
                "ANIM" -> loops = b.getShort(data + 4).toInt() and 0xffff
                "ANMF" -> { frames++; duration += u24(data + 12) }
            }
            pos = data + size + (size and 1)
        }
        return WebpInfo(width, height, flags and 0x02 != 0, flags and 0x10 != 0, frames, duration, loops)
    }

    companion object {
        val WAVE3 = listOf(
            "tail_hide", "grooming", "butterfly", "curl_up", "sleep_curled", "wake_curled",
            "tease", "sneeze", "listen", "paw_wave", "confused", "proud"
        )
    }
}
