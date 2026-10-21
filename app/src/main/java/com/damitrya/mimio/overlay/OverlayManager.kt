package com.damitrya.mimio.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.damitrya.mimio.R
import com.damitrya.mimio.data.SettingsRepository

class OverlayManager(
    private val context: Context,
    private val settings: SettingsRepository
) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var characterView: CharacterView? = null
    private var weatherOverlayView: WeatherOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    /**
     * Invoked on a short tap (< [LONG_PRESS_THRESHOLD_MS] ms, no drag).
     * Wire this up in CompanionService to trigger the TAP animation.
     */
    var onTapListener: (() -> Unit)? = null

    /**
     * Invoked when a long press fires (finger held ≥ [LONG_PRESS_THRESHOLD_MS] ms, no drag).
     * Wire this up in CompanionService to start the PET animation.
     */
    var onLongPressListener: (() -> Unit)? = null

    /**
     * Invoked when the finger is released after a long press.
     * Wire this up in CompanionService to end the PET animation.
     */
    var onLongPressReleasedListener: (() -> Unit)? = null

    /**
     * Invoked when a long press does not end as petting: the finger started dragging the character, or
     * the system cancelled the gesture. Ends PET without the HEARTS / SHY finale.
     */
    var onLongPressCancelledListener: (() -> Unit)? = null

    private val hapticPlayer = HapticPlayer(context)

    /** Responses to the user's touches (tap, petting, the heart) in the chosen mode; OFF in the settings → silent. */
    private val haptics = TouchHaptics({ settings.hapticMode }) { effect, mode ->
        hapticPlayer.play(effect, mode, overlayView)
    }

    fun getCharacterView(): CharacterView? = characterView
    fun getWeatherOverlayView(): WeatherOverlayView? = weatherOverlayView

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizePx = dpToPx(settings.characterSize)

        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX
            y = settings.overlayY
        }

        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_character, null)
        characterView = overlayView?.findViewById(R.id.character_view)
        characterView?.haptics = haptics
        weatherOverlayView = overlayView?.findViewById(R.id.weather_overlay_view)

        // Apply opacity
        overlayView?.alpha = settings.overlayOpacity / 100f

        // Setup drag
        setupDragBehavior()

        windowManager?.addView(overlayView, layoutParams)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(overlayView)
        } catch (_: Exception) {}
        overlayView = null
        characterView = null
        weatherOverlayView = null
        isShowing = false
    }

    fun isVisible(): Boolean = isShowing

    fun updateSize(sizeDp: Int) {
        if (!isShowing) return
        val sizePx = dpToPx(sizeDp)
        layoutParams?.width = sizePx
        layoutParams?.height = sizePx
        try {
            windowManager?.updateViewLayout(overlayView, layoutParams)
        } catch (_: Exception) {}
    }

    fun updateOpacity(percent: Int) {
        overlayView?.alpha = percent / 100f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragBehavior() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val gesture = PressGesture(object : PressGesture.Listener {
            override fun onTap() {
                haptics.play(TouchHaptics.Effect.TAP)
                onTapListener?.invoke()
            }
            override fun onPetStart() {
                haptics.play(TouchHaptics.Effect.HOLD)
                onLongPressListener?.invoke()
            }
            override fun onPetEnd() {
                onLongPressReleasedListener?.invoke()
            }
            override fun onPetCancel() {
                onLongPressCancelledListener?.invoke()
            }
        })

        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable { gesture.longPress() }

        fun savePosition() {
            settings.overlayX = layoutParams?.x ?: 0
            settings.overlayY = layoutParams?.y ?: 0
        }

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    gesture.down()
                    longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    // Moving the character is not petting: a petting that turns into a drag is cancelled.
                    if (gesture.move(dx, dy)) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        layoutParams?.x = initialX + dx.toInt()
                        layoutParams?.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(overlayView, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (gesture.up()) savePosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Gesture interrupted by the system: never a tap, a petting ends without its finale.
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (gesture.cancel()) savePosition()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        /** Presses held at least this long trigger the PET emotion. */
        private const val LONG_PRESS_THRESHOLD_MS = 600L
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
