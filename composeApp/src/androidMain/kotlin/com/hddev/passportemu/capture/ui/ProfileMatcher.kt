// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.capture.ui

import com.hddev.passportemu.capture.CaptureRecord

/** What comparing a capture with the known chip profiles found, in words the capture screen and report can show. */
data class MatchSummary(
    /** The profile the capture matches, or null if none was compared or none matched. */
    val profileName: String?,
    /** How many captures that profile is built from, if matched. */
    val captureCount: Int? = null,
    /** The one line the screen and the report show. */
    val description: String
) {
    companion object {
        val NotCompared = MatchSummary(profileName = null, description = "Compared with profiles: not yet")
    }
}

/** Compares a capture with the known chip profiles. The real one arrives with the profiles-as-data phase. */
fun interface ProfileMatcher {
    fun match(record: CaptureRecord): MatchSummary
}

/** Compares with nothing, until profiles can be compared. */
object NoProfileMatcher : ProfileMatcher {
    override fun match(record: CaptureRecord): MatchSummary = MatchSummary.NotCompared
}
