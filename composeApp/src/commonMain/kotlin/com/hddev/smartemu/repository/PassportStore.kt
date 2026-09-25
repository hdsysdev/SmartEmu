package com.hddev.smartemu.repository

import com.hddev.smartemu.data.PassportData

/**
 * Keeps the passport details and chip settings across app restarts.
 */
interface PassportStore {

    /**
     * Loads the passport saved last.
     *
     * @return Result containing the saved passport, or null if none was ever saved
     */
    suspend fun load(): Result<PassportData?>

    /**
     * Saves the passport, replacing the one saved before.
     *
     * @return Result indicating success or failure with error details
     */
    suspend fun save(passportData: PassportData): Result<Unit>
}
