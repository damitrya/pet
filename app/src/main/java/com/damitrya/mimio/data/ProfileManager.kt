package com.drivecompanion.data

import android.content.Context

enum class AppProfile {
    AUTO,
    DAILY
}

class ProfileManager(private val context: Context, private val settings: SettingsRepository) {

    interface Listener {
        fun onProfileChanged(newProfile: AppProfile, previousProfile: AppProfile)
    }

    private var listener: Listener? = null
    private var currentProfile: AppProfile = AppProfile.DAILY

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentProfile(): AppProfile = currentProfile

    fun switchProfile(profile: AppProfile) {
        if (profile == currentProfile) return
        val previous = currentProfile
        currentProfile = profile
        settings.activeProfile = profile.name
        listener?.onProfileChanged(profile, previous)
    }

    fun restoreProfile() {
        val saved = settings.activeProfile
        currentProfile = try {
            AppProfile.valueOf(saved)
        } catch (_: Exception) {
            AppProfile.DAILY
        }
    }
}
