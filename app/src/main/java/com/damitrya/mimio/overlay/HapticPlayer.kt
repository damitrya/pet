package com.damitrya.mimio.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import com.damitrya.mimio.data.HapticMode

/**
 * Plays [TouchHaptics] effects on the device.
 *
 * The standard View.performHapticFeedback alone (KEYBOARD_TAP / LONG_PRESS / CLOCK_TICK) gives the system's
 * short clicks, and some devices ignore them in an overlay window or tie KEYBOARD_TAP to the keyboard's own
 * setting. So the patterns of [HapticPatterns] go through the vibrator (VibratorManager on Android 12+,
 * Vibrator before), with touch attributes the system scales by its "touch feedback" strength (Android 13+).
 * Without amplitude control the strength becomes the pulse length. The system "touch feedback" switch off →
 * silent; no vibrator access → the standard performHapticFeedback as the fallback ([HapticRoute]).
 * Every response is logged with its route and result (`adb logcat -s MimioHaptics`).
 */
class HapticPlayer(private val context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun route(): HapticRoute = HapticRoute.choose(hasVibrator(), hasPermission(), systemTouchFeedback())

    /** Plays [effect] in [mode]; [view] is used for the fallback. Returns whether anything was played. */
    fun play(effect: TouchHaptics.Effect, mode: HapticMode, view: View?): Boolean {
        if (mode == HapticMode.OFF) return false
        val route = route()
        val played = when (route) {
            HapticRoute.VIBRATOR -> vibrate(pattern(effect, mode)) || viewFeedback(effect, view)
            HapticRoute.VIEW_FEEDBACK -> viewFeedback(effect, view)
            HapticRoute.SILENT -> false
        }
        Log.d(TAG, "$effect mode=$mode route=$route played=$played")
        return played
    }

    /** The pattern this device plays for [effect] (shorter pulses instead of weaker ones without amplitude control). */
    fun pattern(effect: TouchHaptics.Effect, mode: HapticMode): HapticPattern {
        val p = HapticPatterns.of(effect, if (mode == HapticMode.OFF) HapticMode.SOFT else mode)
        return if (hasAmplitudeControl()) p else p.withoutAmplitude()
    }

    private fun vibrate(p: HapticPattern): Boolean {
        val v = vibrator ?: return false
        return try {
            val effect = if (p.timings.size == 2) {
                VibrationEffect.createOneShot(p.timings[1], p.amplitudes[1])
            } else {
                VibrationEffect.createWaveform(p.timings, p.amplitudes, -1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            true
        } catch (e: RuntimeException) {          // SecurityException, a vendor's IllegalArgumentException…
            Log.w(TAG, "vibrate failed: $e")
            false
        }
    }

    /** The standard system haptic of the view (the fallback); false if the system refused it. */
    fun viewFeedback(effect: TouchHaptics.Effect, view: View?): Boolean =
        view?.performHapticFeedback(
            when (effect) {
                TouchHaptics.Effect.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
                TouchHaptics.Effect.HOLD -> HapticFeedbackConstants.LONG_PRESS
                TouchHaptics.Effect.HEART ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.LONG_PRESS
            }
        ) ?: false

    fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    fun hasAmplitudeControl(): Boolean = vibrator?.hasAmplitudeControl() == true

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED

    /** The system "touch feedback" switch; null if it cannot be read. */
    fun systemTouchFeedback(): Boolean? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED) != 0
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Lines for the settings screen: what this device can do and what Mimio uses. */
    fun diagnostics(mode: HapticMode, viewFeedbackResult: Boolean?): List<String> {
        val ringer = when ((context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "обычный"
            AudioManager.RINGER_MODE_VIBRATE -> "вибрация"
            AudioManager.RINGER_MODE_SILENT -> "без звука"
            else -> "неизвестно"
        }
        val touch = when (systemTouchFeedback()) {
            true -> "включён"
            false -> "выключен (Mimio молчит)"
            null -> "неизвестно"
        }
        val api = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "VibratorManager" else "Vibrator"
        return listOf(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "Режим Mimio: $mode",
            "Вибромотор ($api): ${if (hasVibrator()) "есть" else "нет"}",
            "Управление силой (амплитудой): ${if (hasAmplitudeControl()) "да" else "нет — сила передаётся длиной"}",
            "Разрешение VIBRATE: ${if (hasPermission()) "есть" else "нет"}",
            "Системный виброотклик на касание: $touch",
            "Режим звонка: $ringer",
            "performHapticFeedback(): ${viewFeedbackResult?.let { if (it) "true — система приняла" else "false — система отклонила" } ?: "не проверялся"}",
            "Способ: " + when (route()) {
                HapticRoute.VIBRATOR -> "VibrationEffect через $api"
                HapticRoute.VIEW_FEEDBACK -> "запасной — performHapticFeedback()"
                HapticRoute.SILENT -> "нет (виброотклик выключен в системе)"
            },
            "Касание: ${pattern(TouchHaptics.Effect.TAP, mode)}",
            "Поглаживание: ${pattern(TouchHaptics.Effect.HOLD, mode)}",
            "Сердечко: ${pattern(TouchHaptics.Effect.HEART, mode)}"
        )
    }

    companion object {
        private const val TAG = "MimioHaptics"
    }
}
