package com.hddev.smartemu.viewmodel

import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore
import com.hddev.smartemu.repository.ReadHistoryStore
import com.hddev.smartemu.repository.SettingsStore

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