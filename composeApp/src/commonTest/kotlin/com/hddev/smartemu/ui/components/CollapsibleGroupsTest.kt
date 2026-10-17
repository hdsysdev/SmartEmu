package com.hddev.smartemu.ui.components

import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.profile.MeasuredObservations
import com.hddev.smartemu.capture.profile.MeasuredProfile
import com.hddev.smartemu.capture.profile.MeasuredProfileRegistry
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.PresetGroup
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
