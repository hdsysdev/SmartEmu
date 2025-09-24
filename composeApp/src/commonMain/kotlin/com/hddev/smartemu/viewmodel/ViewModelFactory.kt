package com.hddev.smartemu.viewmodel

import com.hddev.smartemu.repository.NfcSimulatorRepository

/**
 * Factory for creating ViewModels with their dependencies.
 * This provides a simple way to inject dependencies into ViewModels.
 */
object ViewModelFactory {
    
    /**
     * Creates a PassportSimulatorViewModel with the provided repository.
     */
    fun createPassportSimulatorViewModel(
        repository: NfcSimulatorRepository
    ): PassportSimulatorViewModel {
        return PassportSimulatorViewModel(repository)
    }
}