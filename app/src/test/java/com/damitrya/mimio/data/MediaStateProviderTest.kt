package com.damitrya.mimio.data

import android.media.session.MediaController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaStateProviderTest {

    @Test
    fun withoutNotificationAccessSessionsAreNotQueried() {
        var queried = false
        val reader = MediaSessionReader(hasNotificationAccess = { false }, querySessions = {
            queried = true
            emptyList()
        })
        assertNull(reader.sessions())
        assertFalse(queried)
    }

    @Test
    fun securityExceptionFallsBackInsteadOfCrashing() {
        val reader = MediaSessionReader(hasNotificationAccess = { true }, querySessions = {
            throw SecurityException("Missing permission to control media")
        })
        assertNull(reader.sessions())
    }

    @Test
    fun withNotificationAccessSessionsAreRead() {
        val reader = MediaSessionReader(hasNotificationAccess = { true }, querySessions = { emptyList<MediaController>() })
        assertNotNull(reader.sessions())
    }

    @Test
    fun accessGrantedLaterIsPickedUp() {
        var granted = false
        val reader = MediaSessionReader(hasNotificationAccess = { granted }, querySessions = { emptyList<MediaController>() })
        assertNull(reader.sessions())
        granted = true
        assertNotNull(reader.sessions())
    }

    @Test
    fun withoutSessionsTheMusicStreamDecides() {
        assertTrue(MediaStateResolver.resolve(null, musicStreamActive = true).isPlaying)
        assertEquals(MediaData(), MediaStateResolver.resolve(null, musicStreamActive = false))
    }

    @Test
    fun aPlayingSessionMeansMusicWithItsTrack() {
        val data = MediaStateResolver.resolve(
            listOf(
                MediaSessionSnapshot(isPlaying = false, trackTitle = "Paused", artist = "A"),
                MediaSessionSnapshot(isPlaying = true, trackTitle = "Song", artist = "B")
            ),
            musicStreamActive = false
        )
        assertEquals(MediaData(isPlaying = true, trackTitle = "Song", artist = "B"), data)
    }

    @Test
    fun pausedSessionsAreSilence() {
        val paused = listOf(MediaSessionSnapshot(isPlaying = false, trackTitle = "Paused"))
        assertEquals(MediaData(), MediaStateResolver.resolve(paused, musicStreamActive = false))
        assertEquals(MediaData(), MediaStateResolver.resolve(emptyList(), musicStreamActive = false))
        assertTrue("a player without a session", MediaStateResolver.resolve(paused, musicStreamActive = true).isPlaying)
    }

    @Test
    fun manifestRequestsNoPrivilegedMediaPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.MEDIA_CONTENT_CONTROL"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
    }
}
