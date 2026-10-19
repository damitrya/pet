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

    /** Most recently used decoded animations (small: 128×128 frames are decoded on the fly). */
    private val drawableCache = object : LinkedHashMap<Int, Drawable>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Drawable>?) =
            size > MAX_CACHED_DRAWABLES
    }

    /** "<character>/<variant>" → raw resource id (0 = unavailable); avoids getIdentifier() on every pick. */
    private val resolvedIds = HashMap<String, Int>()

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

        val drawable = obtainDrawable(resId)
        val previous = imageView.drawable as? AnimatedImageDrawable
        if (previous != null && previous !== drawable) {
            previous.clearAnimationCallbacks()
            previous.stop()
        }

        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.apply {
            // A cached drawable may still be running (e.g. PET → HEARTS share pet.webp for the slime):
            // stop() makes the next start() replay from the first frame.
            clearAnimationCallbacks()
            stop()
            if (state.isLooping && pool.variants.size > 1) {
                // Play once, then pick the next variant
                repeatCount = 0
                registerAnimationCallback(object : Animatable2.AnimationCallback() {
                    override fun onAnimationEnd(d: Drawable?) {
                        post {
                            if (isRealEnd(d) && currentState == state) playVideo(state)
                        }
                    }
                })
            } else {
                repeatCount = if (state.isLooping) AnimatedImageDrawable.REPEAT_INFINITE else 0
                if (!state.isLooping) {
                    registerAnimationCallback(object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(d: Drawable?) {
                            post {
                                if (isRealEnd(d)) oneShotAnimationEndListener?.invoke(state)
                            }
                        }
                    })
                }
            }
            start()
        }
    }

    /**
     * True if the drawable genuinely finished: it is still the one on screen (the state was not
     * interrupted) and it is not running (the end was not the one posted by stop() before a replay).
     */
    private fun isRealEnd(d: Drawable?): Boolean =
        d != null && imageView.drawable === d && !(d as AnimatedImageDrawable).isRunning

    /**
     * Decoded drawables are cached and replayed instead of re-decoding the WebP on every
     * CALM/MUSIC cycle. Keyed by resource id, so a character change simply misses the cache.
     */
    private fun obtainDrawable(resId: Int): Drawable =
        drawableCache.getOrPut(resId) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId))
        }

    /** Raw resource for the variant: its own file, else its fallback, else 0 (variant unavailable). */
    private fun resolveVariant(variant: AnimationVariant): Int {
        val character = settings.character
        return resolvedIds.getOrPut("$character/${variant.resName}") {
            val id = resolveRawRes(character, variant.resName)
            if (id != 0) id else variant.fallback?.let { resolveRawRes(character, it) } ?: 0
        }
    }

    /**
     * Finds the animation for the selected character: "<character>_<name>" (e.g. fox_tap),
     * falling back to the unprefixed original (slime) file if that character lacks it.
     */
    private fun resolveRawRes(character: String, resName: String): Int {
        val res = context.resources
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

    companion object {
        private const val MAX_CACHED_DRAWABLES = 12
    }
}
