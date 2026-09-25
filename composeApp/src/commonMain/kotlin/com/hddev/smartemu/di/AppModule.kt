package com.hddev.smartemu.di

import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import com.hddev.smartemu.viewmodel.ViewModelFactory

/**
 * Simple dependency injection module for the application.
 * Provides a centralized way to create and manage dependencies.
 */
object AppModule {
    
    /**
     * Creates a PassportSimulatorViewModel with the provided repository and passport store.
     * This is the main entry point for creating the ViewModel with its dependencies.
     */
    fun providePassportSimulatorViewModel(
        repository: NfcSimulatorRepository,
        passportStore: PassportStore
    ): PassportSimulatorViewModel {
        return ViewModelFactory.createPassportSimulatorViewModel(repository, passportStore)
    }
}