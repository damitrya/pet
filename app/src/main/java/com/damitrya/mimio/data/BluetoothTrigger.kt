package com.damitrya.mimio.data

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Reports when the car's Bluetooth connects or disconnects. The car is the device chosen in
 * settings, or any car audio / hands-free device for [SettingsRepository.BLUETOOTH_ANY_CAR].
 * Whether the profile actually follows the car is decided by CompanionService.
 */
class BluetoothTrigger(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onCarBluetoothChanged(connected: Boolean)
    }

    private var listener: Listener? = null
    private var registered = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device == null || !isCar(device)) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> listener?.onCarBluetoothChanged(true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> listener?.onCarBluetoothChanged(false)
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (registered || !hasPermission()) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(btReceiver, filter)
            registered = true
        } catch (_: Exception) {}
        checkConnected()
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
        registered = false
    }

    /**
     * Connection broadcasts only report changes: a car that was already connected when the
     * service started (or when the car device was chosen) is found through the audio profiles.
     */
    fun checkConnected() {
        if (!hasPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            if (proxy.connectedDevices.any { isCar(it) }) listener?.onCarBluetoothChanged(true)
                        } catch (_: SecurityException) {
                        } finally {
                            adapter.closeProfileProxy(p, proxy)
                        }
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (_: SecurityException) {}
        }
    }

    private fun isCar(device: BluetoothDevice): Boolean {
        val target = settings.bluetoothDeviceAddress
        if (target.isBlank()) return false
        if (target != SettingsRepository.BLUETOOTH_ANY_CAR) return device.address == target
        return try {
            when (device.bluetoothClass?.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> true
                else -> false
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
