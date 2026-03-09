package com.drivecompanion.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.drivecompanion.state.WeatherModifier

class WeatherOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val lottieView: LottieAnimationView = LottieAnimationView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var currentModifier: WeatherModifier = WeatherModifier.NONE

    init {
        addView(lottieView)
        setBackgroundColor(0x00000000)
        visibility = GONE
    }

    fun setWeatherModifier(modifier: WeatherModifier) {
        if (modifier == currentModifier) return
        currentModifier = modifier

        lottieView.cancelAnimation()

        if (modifier == WeatherModifier.NONE) {
            visibility = GONE
            return
        }

        try {
            lottieView.setAnimation(modifier.overlayAsset)
            lottieView.repeatCount = LottieDrawable.INFINITE
            lottieView.playAnimation()
            visibility = VISIBLE
        } catch (e: Exception) {
            visibility = GONE
        }
    }

    fun pauseAnimation() {
        lottieView.pauseAnimation()
    }

    fun resumeAnimation() {
        if (currentModifier != WeatherModifier.NONE && !lottieView.isAnimating) {
            lottieView.resumeAnimation()
        }
    }
}
