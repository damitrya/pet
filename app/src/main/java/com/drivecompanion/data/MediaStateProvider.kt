package com.drivecompanion.data

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

data class MediaData(
    val isPlaying: Boolean = false,
    val trackTitle: String? = null,
    val artist: String? = null
)

class MediaStateProvider(private val context: Context) {

    interface Listener {
        fun onMediaStateChanged(data: MediaData)
    }

    private var listener: Listener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkAudioState()
            handler.postDelayed(this, 2000L)
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            notifyCurrentState()
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        // Try MediaSessionManager first
        try {
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            setupMediaSessionListener()
        } catch (_: Exception) {
            // Fallback to polling AudioManager
        }

        // Start polling as fallback/supplement
        handler.post(pollRunnable)
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
    }

    private fun setupMediaSessionListener() {
        try {
            val component = ComponentName(context, NotificationListenerStub::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            if (!controllers.isNullOrEmpty()) {
                activeController = controllers[0]
                activeController?.registerCallback(mediaCallback)
            }
        } catch (_: SecurityException) {
            // NotificationListener permission not granted, use polling fallback
        }
    }

    private fun checkAudioState() {
        val isPlaying = audioManager.isMusicActive
        val data = MediaData(isPlaying = isPlaying)
        handler.post { listener?.onMediaStateChanged(data) }
    }

    private fun notifyCurrentState() {
        val controller = activeController
        val playbackState = controller?.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val metadata = controller?.metadata
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)

        val data = MediaData(
            isPlaying = isPlaying,
            trackTitle = title,
            artist = artist
        )
        handler.post { listener?.onMediaStateChanged(data) }
    }

    /**
     * Stub NotificationListenerService required by MediaSessionManager.
     * User must grant notification access for full media session support.
     */
    class NotificationListenerStub : NotificationListenerService()
}
