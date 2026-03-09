package com.drivecompanion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.drivecompanion.R
import com.drivecompanion.data.SettingsRepository

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
        var isDragging = false

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (dx * dx + dy * dy > 25)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams?.x = initialX + dx.toInt()
                        layoutParams?.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(overlayView, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Save position
                        settings.overlayX = layoutParams?.x ?: 0
                        settings.overlayY = layoutParams?.y ?: 0
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
