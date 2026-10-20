package com.damitrya.mimio.data

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

data class MediaData(
    val isPlaying: Boolean = false,
    val trackTitle: String? = null,
    val artist: String? = null
)

/** What one active media session reports, read off its [MediaController]. */
data class MediaSessionSnapshot(
    val isPlaying: Boolean,
    val trackTitle: String? = null,
    val artist: String? = null
)

/**
 * Reads active media sessions through the app's own notification listener. Needs no privileged
 * permission: [MediaSessionManager.getActiveSessions] with our listener component works once the
 * user grants notification access. Without that access, or if the system refuses anyway
 * ([SecurityException]), [sessions] returns null and the caller falls back to [AudioManager].
 */
class MediaSessionReader(
    private val hasNotificationAccess: () -> Boolean,
    private val querySessions: () -> List<MediaController>?
) {
    fun sessions(): List<MediaController>? {
        if (!hasNotificationAccess()) return null
        return try {
            querySessions()
        } catch (_: SecurityException) {
            null
        }
    }
}

object MediaStateResolver {
    /**
     * Music plays when a session is playing or, as before, when the music stream is active
     * (also the only signal without notification access). Track info comes from the playing session.
     */
    fun resolve(sessions: List<MediaSessionSnapshot>?, musicStreamActive: Boolean): MediaData {
        val playing = sessions?.firstOrNull { it.isPlaying }
        return MediaData(
            isPlaying = playing != null || musicStreamActive,
            trackTitle = playing?.trackTitle,
            artist = playing?.artist
        )
    }
}

class MediaStateProvider(private val context: Context) {

    interface Listener {
        fun onMediaStateChanged(data: MediaData)
    }

    private var listener: Listener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val listenerComponent = ComponentName(context, NotificationListenerStub::class.java)
    private val sessionReader = MediaSessionReader(
        hasNotificationAccess = { hasNotificationAccess(context) },
        querySessions = { mediaSessionManager?.getActiveSessions(listenerComponent) }
    )
    private var controllers: List<MediaController> = emptyList()
    private var sessionsListenerRegistered = false
    private var started = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            // Also picks up notification access granted (or revoked) while the service runs.
            refreshSessions(sessionReader.sessions())
            notifyCurrentState()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = notifyCurrentState()
        override fun onMetadataChanged(metadata: MediaMetadata?) = notifyCurrentState()
        override fun onSessionDestroyed() {
            refreshSessions(sessionReader.sessions())
            notifyCurrentState()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            refreshSessions(sessions)
            notifyCurrentState()
        }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (started) return
        started = true
        handler.post(pollRunnable)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(pollRunnable)
        unregisterSessionsListener()
    }

    private fun refreshSessions(sessions: List<MediaController>?) {
        if (!started) return
        if (sessions == null || !registerSessionsListener()) {
            unregisterSessionsListener()
            return
        }
        val current = controllers
        current.filter { old -> sessions.none { it.sessionToken == old.sessionToken } }
            .forEach { it.unregisterCallback(mediaCallback) }
        val kept = sessions.map { new -> current.firstOrNull { it.sessionToken == new.sessionToken } ?: new }
        kept.filter { it !in current }.forEach { it.registerCallback(mediaCallback, handler) }
        controllers = kept
    }

    /** @return whether session change updates are coming, i.e. the session list can be trusted. */
    private fun registerSessionsListener(): Boolean {
        if (sessionsListenerRegistered) return true
        val manager = mediaSessionManager ?: return false
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent, handler)
            sessionsListenerRegistered = true
        } catch (_: SecurityException) {
            // Access was revoked between the check and the call; polling retries.
        }
        return sessionsListenerRegistered
    }

    private fun unregisterSessionsListener() {
        if (sessionsListenerRegistered) {
            sessionsListenerRegistered = false
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers = emptyList()
    }

    private fun notifyCurrentState() {
        if (!started) return
        val snapshots = if (sessionsListenerRegistered) controllers.map { it.snapshot() } else null
        val data = MediaStateResolver.resolve(snapshots, audioManager.isMusicActive)
        listener?.onMediaStateChanged(data)
    }

    private fun MediaController.snapshot() = MediaSessionSnapshot(
        isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
        trackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
    )

    /**
     * NotificationListenerService whose component lets MediaSessionManager hand out active
     * sessions. The user grants notification access in system settings for full media support.
     */
    class NotificationListenerStub : NotificationListenerService()

    companion object {
        private const val POLL_MS = 2000L

        fun hasNotificationAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
