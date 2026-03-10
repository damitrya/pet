package com.drivecompanion.overlay

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.drivecompanion.state.CompanionState

/**
 * Custom view that displays the animated companion character using Lottie
 * or video playback (for webm states like CALM).
 * Manages animation transitions between states.
 */
class CharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val lottieView: LottieAnimationView = LottieAnimationView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val videoSurface: SurfaceView = SurfaceView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentState: CompanionState? = null
    private var surfaceReady = false
    private var pendingVideoState: CompanionState? = null

    init {
        addView(lottieView)
        addView(videoSurface)
        setBackgroundColor(0x00000000) // Transparent

        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                pendingVideoState?.let { playVideo(it) }
                pendingVideoState = null
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                releaseMediaPlayer()
            }
        })
    }

    fun setState(state: CompanionState) {
        if (state == currentState) return
        currentState = state

        if (state.rawVideoResName != null) {
            showVideo(state)
        } else {
            showLottie(state)
        }
    }

    private fun showLottie(state: CompanionState) {
        releaseMediaPlayer()
        videoSurface.visibility = View.GONE
        lottieView.visibility = View.VISIBLE

        lottieView.cancelAnimation()

        try {
            lottieView.setAnimation(state.animationAsset)
            lottieView.repeatCount = if (state.isLooping) LottieDrawable.INFINITE else 0
            lottieView.playAnimation()
        } catch (e: Exception) {
            setFallbackAnimation(state)
        }
    }

    private fun showVideo(state: CompanionState) {
        lottieView.cancelAnimation()
        lottieView.visibility = View.GONE
        videoSurface.visibility = View.VISIBLE

        if (surfaceReady) {
            playVideo(state)
        } else {
            pendingVideoState = state
        }
    }

    private fun playVideo(state: CompanionState) {
        releaseMediaPlayer()

        val resName = state.rawVideoResName ?: return
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) {
            setFallbackAnimation(state)
            return
        }

        val uri = Uri.parse("android.resource://${context.packageName}/$resId")
        mediaPlayer = MediaPlayer().apply {
            setDisplay(videoSurface.holder)
            setDataSource(context, uri)
            isLooping = state.isLooping
            setOnPreparedListener { mp ->
                mp.start()
            }
            prepareAsync()
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.run {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
    }

    /**
     * Fallback when animation/video is not available.
     */
    private fun setFallbackAnimation(state: CompanionState) {
        lottieView.cancelAnimation()
    }

    fun pauseAnimation() {
        if (currentState?.rawVideoResName != null) {
            mediaPlayer?.takeIf { it.isPlaying }?.pause()
        } else {
            lottieView.pauseAnimation()
        }
    }

    fun resumeAnimation() {
        if (currentState?.rawVideoResName != null) {
            mediaPlayer?.takeIf { !it.isPlaying }?.start()
        } else {
            if (lottieView.isAnimating.not()) {
                lottieView.resumeAnimation()
            }
        }
    }
}
