package com.drivecompanion.overlay

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.drivecompanion.state.CompanionState

/**
 * Custom view that displays the animated companion character using Lottie
 * or AnimatedImageDrawable (for animated WebP states like CALM).
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

    private val imageView: ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }

    private var currentState: CompanionState? = null

    /** Tracks which music track to play next (alternates between "music" and "music2"). */
    private var useMusicTrack2 = false

    /** Tracks which idle animation to play next (alternates between "idle_small" and "idle2"). */
    private var useIdleTrack2 = false

    /** Called when a non-looping animation finishes playing. */
    var oneShotAnimationEndListener: ((CompanionState) -> Unit)? = null

    init {
        setBackgroundColor(0x00000000)
        addView(lottieView)
        addView(imageView)
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
        stopAnimatedDrawable()
        imageView.visibility = View.GONE
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
        playVideo(state)
    }

    private fun playVideo(state: CompanionState) {
        var resName = state.rawVideoResName ?: return
        val alternatingMusic = state == CompanionState.MUSIC
        val alternatingIdle = state == CompanionState.CALM
        if (alternatingMusic) {
            resName = if (useMusicTrack2) "music2" else "music"
            useMusicTrack2 = !useMusicTrack2
        } else if (alternatingIdle) {
            resName = if (useIdleTrack2) "idle2" else "idle_small"
            useIdleTrack2 = !useIdleTrack2
        }
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) { setFallbackAnimation(state); return }

        val source = ImageDecoder.createSource(context.resources, resId)
        val drawable = ImageDecoder.decodeDrawable(source)

        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.apply {
            if (alternatingMusic || alternatingIdle) {
                // Play once, then switch to the other track
                repeatCount = 0
                val expectedState = state
                registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) {
                        post {
                            if (currentState == expectedState) playVideo(state)
                        }
                    }
                })
            } else {
                repeatCount = if (state.isLooping) AnimatedImageDrawable.REPEAT_INFINITE else 0
                if (!state.isLooping) {
                    registerAnimationCallback(object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(d: Drawable?) {
                            post { oneShotAnimationEndListener?.invoke(state) }
                        }
                    })
                }
            }
            start()
        }
    }

    private fun stopAnimatedDrawable() {
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
        imageView.setImageDrawable(null)
    }

    private fun setFallbackAnimation(state: CompanionState) {
        lottieView.cancelAnimation()
    }

    fun pauseAnimation() {
        if (currentState?.rawVideoResName != null) {
            (imageView.drawable as? AnimatedImageDrawable)?.stop()
        } else {
            lottieView.pauseAnimation()
        }
    }

    fun resumeAnimation() {
        if (currentState?.rawVideoResName != null) {
            (imageView.drawable as? AnimatedImageDrawable)?.start()
        } else {
            if (!lottieView.isAnimating) lottieView.resumeAnimation()
        }
    }
}
