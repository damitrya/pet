package com.drivecompanion.receiver

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.service.CompanionService

class CompanionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = SettingsRepository(this)

        if (settings.serviceEnabled) {
            CompanionService.stop(this)
            settings.serviceEnabled = false
        } else {
            CompanionService.start(this)
            settings.serviceEnabled = true
        }
        updateTile()
    }

    private fun updateTile() {
        val settings = SettingsRepository(this)
        qsTile?.apply {
            state = if (settings.serviceEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "DriveCompanion"
            updateTile()
        }
    }
}
