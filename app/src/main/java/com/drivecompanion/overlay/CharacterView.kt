package com.drivecompanion.overlay

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
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
        val resName = state.rawVideoResName ?: return
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) { setFallbackAnimation(state); return }

        val source = ImageDecoder.createSource(context.resources, resId)
        val drawable = ImageDecoder.decodeDrawable(source)

        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.apply {
            repeatCount = if (state.isLooping) AnimatedImageDrawable.REPEAT_INFINITE else 0
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
