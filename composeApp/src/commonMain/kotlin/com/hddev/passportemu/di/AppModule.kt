// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.di

import com.hddev.passportemu.repository.NfcSimulatorRepository
import com.hddev.passportemu.repository.PassportStore
import com.hddev.passportemu.repository.ReadHistoryStore
import com.hddev.passportemu.repository.SettingsStore
import com.hddev.passportemu.viewmodel.PassportSimulatorViewModel
import com.hddev.passportemu.viewmodel.ViewModelFactory

/**
 * Simple dependency injection module for the application.
 * Provides a centralized way to create and manage dependencies.
 */
object AppModule {
    
    /**
     * Creates a PassportSimulatorViewModel with the provided repository and stores.
     * This is the main entry point for creating the ViewModel with its dependencies.
     */
    fun providePassportSimulatorViewModel(
        repository: NfcSimulatorRepository,
        passportStore: PassportStore,
        readHistoryStore: ReadHistoryStore,
        settingsStore: SettingsStore = SettingsStore.InMemory()
    ): PassportSimulatorViewModel {
        return ViewModelFactory.createPassportSimulatorViewModel(repository, passportStore, readHistoryStore, settingsStore)
    }
}