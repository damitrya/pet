package com.drivecompanion.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class PhoneCallData(
    val isInCall: Boolean = false
)

class PhoneCallProvider(private val context: Context) {

    interface Listener {
        fun onPhoneCallChanged(data: PhoneCallData)
    }

    private var listener: Listener? = null
    private var currentData = PhoneCallData()
    private val handler = Handler(Looper.getMainLooper())
    private var phoneStateListener: PhoneStateListener? = null
    private var useFallback = false

    private val fallbackRunnable = object : Runnable {
        override fun run() {
            checkAudioMode()
            handler.postDelayed(this, 2000L)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): PhoneCallData = currentData

    fun start() {
        if (hasReadPhoneStatePermission()) {
            startTelephonyListener()
        } else {
            useFallback = true
            handler.post(fallbackRunnable)
        }
    }

    fun stop() {
        if (useFallback) {
            handler.removeCallbacks(fallbackRunnable)
        } else {
            try {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                telephony?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun startTelephonyListener() {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: run {
                useFallback = true
                handler.post(fallbackRunnable)
                return
            }

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val isInCall = state == TelephonyManager.CALL_STATE_OFFHOOK
                currentData = PhoneCallData(isInCall = isInCall)
                handler.post { listener?.onPhoneCallChanged(currentData) }
            }
        }

        try {
            telephony.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) {
            useFallback = true
            handler.post(fallbackRunnable)
        }
    }

    private fun checkAudioMode() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isInCall = audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        val prev = currentData.isInCall
        currentData = PhoneCallData(isInCall = isInCall)
        if (prev != isInCall) {
            listener?.onPhoneCallChanged(currentData)
        }
    }

    private fun hasReadPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
