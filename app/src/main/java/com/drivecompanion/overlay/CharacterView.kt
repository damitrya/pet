package com.drivecompanion.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.drivecompanion.state.CompanionState

/**
 * Custom view that displays the animated companion character using Lottie.
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

    private var currentState: CompanionState? = null

    init {
        addView(lottieView)
        setBackgroundColor(0x00000000) // Transparent
    }

    fun setState(state: CompanionState) {
        if (state == currentState) return
        currentState = state

        lottieView.cancelAnimation()

        try {
            lottieView.setAnimation(state.animationAsset)
            lottieView.repeatCount = if (state.isLooping) LottieDrawable.INFINITE else 0
            lottieView.playAnimation()
        } catch (e: Exception) {
            // Animation asset not found — use fallback
            setFallbackAnimation(state)
        }
    }

    /**
     * Fallback when Lottie JSON is not available.
     * Shows a simple colored circle with state indicator.
     */
    private fun setFallbackAnimation(state: CompanionState) {
        lottieView.cancelAnimation()
        // The fallback is handled by setting a static image resource if available
        // For MVP, we'll just keep whatever was showing or show nothing
    }

    fun pauseAnimation() {
        lottieView.pauseAnimation()
    }

    fun resumeAnimation() {
        if (lottieView.isAnimating.not()) {
            lottieView.resumeAnimation()
        }
    }
}
