package com.drivecompanion.data

import android.content.Context
import android.content.SharedPreferences
import com.drivecompanion.state.MoodSnapshot
import com.drivecompanion.state.MoodStore
import com.drivecompanion.state.StoredMood

/**
 * Keeps [com.drivecompanion.state.CharacterMood] in its own preferences file, so mood saves
 * do not wake the settings change listener of the service.
 */
class PrefsMoodStore(private val prefs: SharedPreferences) : MoodStore {

    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override fun load(): StoredMood? {
        if (!prefs.contains(KEY_SAVED_AT)) return null
        return StoredMood(
            MoodSnapshot(
                energy = prefs.getFloat(KEY_ENERGY, MoodSnapshot.NEUTRAL.energy),
                affection = prefs.getFloat(KEY_AFFECTION, MoodSnapshot.NEUTRAL.affection),
                curiosity = prefs.getFloat(KEY_CURIOSITY, MoodSnapshot.NEUTRAL.curiosity)
            ),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        )
    }

    override fun save(stored: StoredMood) {
        prefs.edit()
            .putFloat(KEY_ENERGY, stored.mood.energy)
            .putFloat(KEY_AFFECTION, stored.mood.affection)
            .putFloat(KEY_CURIOSITY, stored.mood.curiosity)
            .putLong(KEY_SAVED_AT, stored.savedAt)
            .apply()
    }

    companion object {
        private const val FILE = "drivecompanion_mood"
        private const val KEY_ENERGY = "energy"
        private const val KEY_AFFECTION = "affection"
        private const val KEY_CURIOSITY = "curiosity"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
