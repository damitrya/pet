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
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.state.AnimationPools
import com.drivecompanion.state.AnimationVariant
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

    private val settings = SettingsRepository(context)

    private var currentState: CompanionState? = null

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

    /**
     * Plays the next variant from the state's [AnimationPools] pool.
     * Looping states with several variants play one variant once and then pick the next one;
     * single-variant looping states loop that file forever.
     */
    private fun playVideo(state: CompanionState) {
        val pool = AnimationPools.forState(state)
        val variant = pool.pick { resolveVariant(it) != 0 }
        val resId = variant?.let { resolveVariant(it) } ?: 0
        if (resId == 0) { setFallbackAnimation(state); return }

        val source = ImageDecoder.createSource(context.resources, resId)
        val drawable = ImageDecoder.decodeDrawable(source)

        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.apply {
            if (state.isLooping && pool.variants.size > 1) {
                // Play once, then pick the next variant
                repeatCount = 0
                registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) {
                        post {
                            if (currentState == state && imageView.drawable === d) playVideo(state)
                        }
                    }
                })
            } else {
                repeatCount = if (state.isLooping) AnimatedImageDrawable.REPEAT_INFINITE else 0
                if (!state.isLooping) {
                    registerAnimationCallback(object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(d: Drawable?) {
                            post {
                                // Ignore a replaced drawable: the state was interrupted (or re-entered).
                                if (imageView.drawable === d) oneShotAnimationEndListener?.invoke(state)
                            }
                        }
                    })
                }
            }
            start()
        }
    }

    /** Raw resource for the variant: its own file, else its fallback, else 0 (variant unavailable). */
    private fun resolveVariant(variant: AnimationVariant): Int {
        val id = resolveRawRes(variant.resName)
        if (id != 0) return id
        return variant.fallback?.let { resolveRawRes(it) } ?: 0
    }

    /**
     * Finds the animation for the selected character: "<character>_<name>" (e.g. fox_tap),
     * falling back to the unprefixed original (slime) file if that character lacks it.
     */
    private fun resolveRawRes(resName: String): Int {
        val res = context.resources
        val character = settings.character
        if (character != SettingsRepository.CHARACTER_SLIME) {
            val id = res.getIdentifier("${character}_$resName", "raw", context.packageName)
            if (id != 0) return id
        }
        return res.getIdentifier(resName, "raw", context.packageName)
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
