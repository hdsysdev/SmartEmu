// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.ui.components

import com.hddev.passportemu.capture.ProbePackRef
import com.hddev.passportemu.capture.profile.MeasuredObservations
import com.hddev.passportemu.capture.profile.MeasuredProfile
import com.hddev.passportemu.capture.profile.MeasuredProfileRegistry
import com.hddev.passportemu.data.PassportPresets
import com.hddev.passportemu.data.PresetGroup
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollapsibleGroupsTest {

    @AfterTest
    fun clearRegistry() = MeasuredProfileRegistry.clear()

    @Test
    fun togglingAddsAGroupThatIsClosedAndRemovesOneThatIsOpen() {
        assertEquals(setOf("a", "b"), setOf("a").toggled("b"))
        assertEquals(setOf("a"), setOf("a", "b").toggled("b"))
        assertEquals(emptySet(), emptySet<String>().toggled("a").toggled("a"))
    }

    @Test
    fun aPresetListOpensOnItsFirstGroupOnly() {
        assertEquals(setOf(PresetGroup.SAMPLED), initiallyExpanded(PassportPresets.grouped))
        assertEquals(emptySet(), initiallyExpanded(emptyList()))
    }

    @Test
    fun aPresetListOpensOnTheCapturedDocumentsWhenThereAreAny() {
        MeasuredProfileRegistry.link(
            listOf(
                MeasuredProfile(
                    id = "xx-card",
                    chipProfileId = null,
                    generation = "gen-xx-card",
                    probePack = ProbePackRef("tier-a", 2),
                    sampleCount = 1,
                    captureIds = listOf("a"),
                    observations = MeasuredObservations(bacGetChallenge = 0x9000)
                )
            )
        )
        assertEquals(setOf(PresetGroup.CAPTURED), initiallyExpanded(PassportPresets.grouped))
    }
}
