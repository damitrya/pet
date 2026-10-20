package com.damitrya.mimio.overlay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Debug
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.state.AnimationDirector
import com.damitrya.mimio.state.AnimationPools
import com.damitrya.mimio.state.AnimationVariant
import com.damitrya.mimio.state.CompanionState

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

    /** Chooses the variants (mood, context, variety). Without it the plain [AnimationPools] are used. */
    var director: AnimationDirector? = null

    /** Haptic ticks; the view gives one when the fox has become a heart. */
    var haptics: TouchHaptics? = null

    /** Tick at [AnimationPools.HEART_MOMENT_MS] of heart_transform; dropped if anything else starts. */
    private val heartTick = Runnable { haptics?.play(TouchHaptics.Effect.HEART) }

    init {
        setBackgroundColor(0x00000000)
        addView(lottieView)
        addView(imageView)
    }

    fun setState(state: CompanionState) {
        if (state == currentState) return
        currentState = state
        removeCallbacks(heartTick)
        imageView.animate().cancel()      // a scene cut may be fading out
        imageView.alpha = 1f

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
        val isAvailable = { v: AnimationVariant -> resolveVariant(v) != 0 }
        val director = director
        val variant = if (director != null) director.pick(state, isAvailable) else pool.pick(isAvailable)
        val resId = variant?.let { resolveVariant(it) } ?: 0
        if (resId == 0) {
            setFallbackAnimation(state)
            // Nothing to play: a one-shot must still end, or the state machine would wait for it.
            if (!state.isLooping) post { if (currentState == state) oneShotAnimationEndListener?.invoke(state) }
            return
        }

        removeCallbacks(heartTick)
        if (variant?.resName == AnimationPools.HEART_TRANSFORM &&
            resId == resolveRawRes(settings.character, AnimationPools.HEART_TRANSFORM)   // not a fallback
        ) {
            postDelayed(heartTick, AnimationPools.HEART_MOMENT_MS)
        }

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
                            if (isRealEnd(d) && currentState == state) {
                                if (variant != null) director?.onFinished(state, variant)
                                playVideo(state)
                            }
                        }
                    }
                })
            } else {
                repeatCount = if (state.isLooping) AnimatedImageDrawable.REPEAT_INFINITE else 0
                if (!state.isLooping) {
                    registerAnimationCallback(object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(d: Drawable?) {
                            post {
                                if (isRealEnd(d)) {
                                    // The director continues a sequence (curl_up → sleep_curled) only from here.
                                    if (variant != null) director?.onFinished(state, variant)
                                    oneShotAnimationEndListener?.invoke(state)
                                }
                            }
                        }
                    })
                }
            }
            start()
        }
    }

    /**
     * Replaces the running variant of a looping state at once (the car started moving during a scene).
     * A short fade hides the jump between poses; the director then picks a calm variant.
     */
    fun cutScene() {
        val state = currentState ?: return
        if (!state.isLooping || state.rawVideoResName == null) return
        imageView.animate().cancel()
        imageView.animate().alpha(0f).setDuration(CUT_FADE_MS).withEndAction {
            if (currentState == state) playVideo(state)
            imageView.animate().alpha(1f).setDuration(CUT_FADE_MS)
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
            decodes++
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId))
        }

    /** Cache misses since the view was created (memory log in debug builds). */
    private var decodes = 0

    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Debug builds only: every [MEMORY_LOG_MS] logs the decoded-animation cache and the process heaps
     * (`adb logcat -s MimioMem`), to watch memory over a long session.
     */
    private val memoryLog = object : Runnable {
        override fun run() {
            val rt = Runtime.getRuntime()
            Log.d(
                MEMORY_TAG,
                "cached=${drawableCache.size}/$MAX_CACHED_DRAWABLES decodes=$decodes " +
                    "javaUsedKb=${(rt.totalMemory() - rt.freeMemory()) / 1024} " +
                    "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} state=$currentState"
            )
            postDelayed(this, MEMORY_LOG_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (debuggable) postDelayed(memoryLog, MEMORY_LOG_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(memoryLog)
        removeCallbacks(heartTick)
        imageView.animate().cancel()
        super.onDetachedFromWindow()
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
        removeCallbacks(heartTick)
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
        private const val CUT_FADE_MS = 120L
        private const val MEMORY_LOG_MS = 60_000L
        private const val MEMORY_TAG = "MimioMem"
    }
}
