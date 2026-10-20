package com.drivecompanion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.service.CompanionService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settings = SettingsRepository(context)
            if (settings.autoStartEnabled) {
                CompanionService.start(context)
            }
        }
    }
}
