// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.repository

import com.hddev.passportemu.data.AppSettings

/**
 * Keeps the app settings across restarts. Loading is synchronous, as the settings are small and decide what the first
 * frame shows: the advanced or the guided screens, light or dark.
 */
interface SettingsStore {

    fun load(): AppSettings

    fun save(settings: AppSettings)

    /** Keeps the settings in memory only, for tests and previews. */
    class InMemory(private var settings: AppSettings = AppSettings()) : SettingsStore {
        override fun load(): AppSettings = settings

        override fun save(settings: AppSettings) {
            this.settings = settings
        }
    }
}
