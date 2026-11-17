// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.repository

import com.hddev.passportemu.data.PassportData

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
