// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.viewmodel

import com.hddev.passportemu.repository.NfcSimulatorRepository
import com.hddev.passportemu.repository.PassportStore
import com.hddev.passportemu.repository.ReadHistoryStore
import com.hddev.passportemu.repository.SettingsStore

/**
 * Factory for creating ViewModels with their dependencies.
 * This provides a simple way to inject dependencies into ViewModels.
 */
object ViewModelFactory {
    
    /**
     * Creates a PassportSimulatorViewModel with the provided repository and stores.
     */
    fun createPassportSimulatorViewModel(
        repository: NfcSimulatorRepository,
        passportStore: PassportStore,
        readHistoryStore: ReadHistoryStore,
        settingsStore: SettingsStore = SettingsStore.InMemory()
    ): PassportSimulatorViewModel {
        return PassportSimulatorViewModel(repository, passportStore, readHistoryStore, settingsStore)
    }
}