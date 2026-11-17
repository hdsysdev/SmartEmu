// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.repository

import com.hddev.passportemu.data.ReadRecord

/**
 * Keeps the read history across app restarts.
 */
interface ReadHistoryStore {

    /**
     * Loads the records saved last, newest first.
     */
    suspend fun load(): Result<List<ReadRecord>>

    /**
     * Saves the records, newest first, replacing those saved before.
     */
    suspend fun save(records: List<ReadRecord>): Result<Unit>

    /** Keeps the history in memory only, for tests and platforms without storage. */
    class InMemory : ReadHistoryStore {
        private var records: List<ReadRecord> = emptyList()

        override suspend fun load(): Result<List<ReadRecord>> = Result.success(records)

        override suspend fun save(records: List<ReadRecord>): Result<Unit> {
            this.records = records
            return Result.success(Unit)
        }
    }
}
