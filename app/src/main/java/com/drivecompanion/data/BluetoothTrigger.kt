package com.drivecompanion.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class BluetoothTrigger(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onBluetoothAutoProfile(switchToAuto: Boolean)
    }

    private var listener: Listener? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val targetAddress = settings.bluetoothDeviceAddress
            if (targetAddress.isBlank() || device?.address != targetAddress) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    listener?.onBluetoothAutoProfile(true)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    listener?.onBluetoothAutoProfile(false)
                }
            }
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (settings.profileMode != "auto_bluetooth") return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(btReceiver, filter)
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
    }
}
