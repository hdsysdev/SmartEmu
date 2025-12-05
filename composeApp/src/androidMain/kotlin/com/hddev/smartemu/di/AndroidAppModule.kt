package com.hddev.smartemu.di

import android.content.Context
import com.hddev.smartemu.repository.AndroidNfcSimulatorRepository
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * Android-specific dependency injection module.
 * Provides Android-specific implementations and context-dependent dependencies.
 */
object AndroidAppModule {
    
    private var nfcSimulatorRepository: AndroidNfcSimulatorRepository? = null

    /**
     * Provides the Android-specific NFC simulator repository.
     */
    fun provideNfcSimulatorRepository(context: Context): NfcSimulatorRepository {
        if (nfcSimulatorRepository == null) {
            nfcSimulatorRepository = AndroidNfcSimulatorRepository(context)
        }
        return nfcSimulatorRepository!!
    }
    
    /**
     * Provides a fully configured PassportSimulatorViewModel for Android.
     */
    fun providePassportSimulatorViewModel(context: Context): PassportSimulatorViewModel {
        val repository = provideNfcSimulatorRepository(context)
        return AppModule.providePassportSimulatorViewModel(repository)
    }
}