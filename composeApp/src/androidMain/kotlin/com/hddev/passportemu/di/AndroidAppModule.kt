// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.di

import android.content.Context
import com.hddev.passportemu.repository.AndroidNfcSimulatorRepository
import com.hddev.passportemu.repository.AndroidPassportStore
import com.hddev.passportemu.repository.AndroidReadHistoryStore
import com.hddev.passportemu.repository.AndroidSettingsStore
import com.hddev.passportemu.repository.NfcSimulatorRepository
import com.hddev.passportemu.repository.PassportStore
import com.hddev.passportemu.repository.ReadHistoryStore
import com.hddev.passportemu.repository.SettingsStore
import com.hddev.passportemu.viewmodel.PassportSimulatorViewModel

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
     * Provides the store that keeps the passport across app restarts.
     */
    fun providePassportStore(context: Context): PassportStore = AndroidPassportStore(context)

    /**
     * Provides the store that keeps the read history across app restarts.
     */
    fun provideReadHistoryStore(context: Context): ReadHistoryStore = AndroidReadHistoryStore(context)

    /**
     * Provides the store that keeps the app settings across app restarts.
     */
    fun provideSettingsStore(context: Context): SettingsStore = AndroidSettingsStore(context)
    
    /**
     * Provides a fully configured PassportSimulatorViewModel for Android.
     */
    fun providePassportSimulatorViewModel(context: Context): PassportSimulatorViewModel {
        val repository = provideNfcSimulatorRepository(context)
        return AppModule.providePassportSimulatorViewModel(
            repository, providePassportStore(context), provideReadHistoryStore(context), provideSettingsStore(context)
        )
    }
}