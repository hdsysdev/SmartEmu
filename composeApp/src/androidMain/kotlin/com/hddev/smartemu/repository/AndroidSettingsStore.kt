package com.hddev.smartemu.repository

import android.content.Context
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.AppTheme
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PaceSuite
import com.hddev.smartemu.data.SessionCipher

/**
 * Saves the settings in the app's private shared preferences. Whether the introduction was seen used to live in
 * preferences of its own, which are still read so that it isn't shown again after an update. The extra PACE suites
 * are saved as one string, "MAPPING,DOMAIN,CIPHER" for each, separated by semicolons, with the enum constants' names.
 */
class AndroidSettingsStore(context: Context) : SettingsStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyGuidePreferences =
        context.applicationContext.getSharedPreferences(LEGACY_GUIDE_PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            developerMode = preferences.getBoolean(KEY_DEVELOPER_MODE, defaults.developerMode),
            paceCurve = preferences.getString(KEY_PACE_CURVE, null)
                ?.let { name -> EcCurve.entries.find { it.name == name } },
            paceDhGroup = preferences.getString(KEY_PACE_DH_GROUP, null)
                ?.let { name -> DhGroup.entries.find { it.name == name } },
            paceCipher = preferences.getString(KEY_PACE_CIPHER, null)
                ?.let { name -> SessionCipher.entries.find { it.name == name } },
            extraPaceSuites = preferences.getString(KEY_EXTRA_PACE_SUITES, null)
                ?.split(SUITE_SEPARATOR)
                ?.mapNotNull(::decodeSuite)
                .orEmpty(),
            extraPaceSuitesFirst = preferences.getBoolean(KEY_EXTRA_PACE_SUITES_FIRST, defaults.extraPaceSuitesFirst),
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
            .putString(KEY_PACE_CURVE, settings.paceCurve?.name)
            .putString(KEY_PACE_DH_GROUP, settings.paceDhGroup?.name)
            .putString(KEY_PACE_CIPHER, settings.paceCipher?.name)
            .putString(KEY_EXTRA_PACE_SUITES, settings.extraPaceSuites.joinToString(SUITE_SEPARATOR, transform = ::encodeSuite))
            .putBoolean(KEY_EXTRA_PACE_SUITES_FIRST, settings.extraPaceSuitesFirst)
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_KEEP_READ_HISTORY, settings.keepReadHistory)
            .putBoolean(KEY_INTRO_SEEN, settings.introSeen)
            .apply()
    }

    private fun encodeSuite(suite: PaceSuite): String {
        val domain = suite.spec.dhGroup?.name ?: suite.spec.curve.name
        return listOf(suite.mapping.name, domain, suite.spec.cipher.name).joinToString(FIELD_SEPARATOR)
    }

    /** A suite [encodeSuite] saved, or null for one this version can't read. */
    private fun decodeSuite(encoded: String): PaceSuite? {
        val fields = encoded.split(FIELD_SEPARATOR)
        if (fields.size != 3) return null
        val mapping = PaceMapping.entries.find { it.name == fields[0] } ?: return null
        val cipher = SessionCipher.entries.find { it.name == fields[2] } ?: return null
        val dhGroup = DhGroup.entries.find { it.name == fields[1] }
        val spec = if (dhGroup != null) {
            PaceSpec(cipher = cipher, dhGroup = dhGroup)
        } else {
            PaceSpec(curve = EcCurve.entries.find { it.name == fields[1] } ?: return null, cipher = cipher)
        }
        return PaceSuite(mapping, spec)
    }

    private companion object {
        const val PREFERENCES_NAME = "settings"
        const val LEGACY_GUIDE_PREFERENCES = "guide"

        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_PACE_CURVE = "pace_curve"
        const val KEY_PACE_DH_GROUP = "pace_dh_group"
        const val KEY_PACE_CIPHER = "pace_cipher"
        const val KEY_EXTRA_PACE_SUITES = "extra_pace_suites"
        const val KEY_EXTRA_PACE_SUITES_FIRST = "extra_pace_suites_first"
        const val SUITE_SEPARATOR = ";"
        const val FIELD_SEPARATOR = ","
        const val KEY_THEME = "theme"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_KEEP_READ_HISTORY = "keep_read_history"
        const val KEY_INTRO_SEEN = "introSeen"
    }
}
