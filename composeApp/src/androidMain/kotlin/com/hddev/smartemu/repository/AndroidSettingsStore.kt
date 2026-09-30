package com.hddev.smartemu.repository

import android.content.Context
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.AppTheme

/**
 * Saves the settings in the app's private shared preferences. Whether the introduction was seen used to live in
 * preferences of its own, which are still read so that it isn't shown again after an update.
 */
class AndroidSettingsStore(context: Context) : SettingsStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyGuidePreferences =
        context.applicationContext.getSharedPreferences(LEGACY_GUIDE_PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            developerMode = preferences.getBoolean(KEY_DEVELOPER_MODE, defaults.developerMode),
            exactCryptography = preferences.getBoolean(KEY_EXACT_CRYPTOGRAPHY, defaults.exactCryptography),
            theme = preferences.getString(KEY_THEME, null)
                ?.let { name -> AppTheme.entries.find { it.name == name } } ?: defaults.theme,
            keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            keepReadHistory = preferences.getBoolean(KEY_KEEP_READ_HISTORY, defaults.keepReadHistory),
            introSeen = preferences.getBoolean(KEY_INTRO_SEEN, false) ||
                legacyGuidePreferences.getBoolean(KEY_INTRO_SEEN, false)
        )
    }

    override fun save(settings: AppSettings) {
        preferences.edit()
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_EXACT_CRYPTOGRAPHY, settings.exactCryptography)
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_KEEP_READ_HISTORY, settings.keepReadHistory)
            .putBoolean(KEY_INTRO_SEEN, settings.introSeen)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "settings"
        const val LEGACY_GUIDE_PREFERENCES = "guide"

        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_EXACT_CRYPTOGRAPHY = "exact_cryptography"
        const val KEY_THEME = "theme"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_KEEP_READ_HISTORY = "keep_read_history"
        const val KEY_INTRO_SEEN = "introSeen"
    }
}
