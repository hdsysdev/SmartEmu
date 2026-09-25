package com.hddev.smartemu.viewmodel

import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore

/**
 * Factory for creating ViewModels with their dependencies.
 * This provides a simple way to inject dependencies into ViewModels.
 */
object ViewModelFactory {
    
    /**
     * Creates a PassportSimulatorViewModel with the provided repository and passport store.
     */
    fun createPassportSimulatorViewModel(
        repository: NfcSimulatorRepository,
        passportStore: PassportStore
    ): PassportSimulatorViewModel {
        return PassportSimulatorViewModel(repository, passportStore)
    }
}