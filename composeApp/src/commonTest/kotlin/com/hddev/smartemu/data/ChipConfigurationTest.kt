package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChipConfigurationTest {

    private val holder = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    @Test
    fun `profile ids are unique and every profile's aspects have a provenance`() {
        assertEquals(ChipProfiles.all.size, ChipProfiles.all.map { it.id }.toSet().size)
        assertEquals(ChipProfiles.GENERIC_ID, ChipProfiles.default.id)
        assertEquals(ChipProfiles.default, ChipProfiles.byId("no-such-profile"))
        for (profile in ChipProfiles.all.filter { it.id != ChipProfiles.GENERIC_ID }) {
            assertTrue(profile.sources.isNotEmpty() || profile.provenance.values.all { it != Provenance.REPORTED }, profile.id)
        }
    }

    @Test
    fun `adapted cryptography swaps a Brainpool PACE curve for the NIST one of the same size`() {
        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017"))

        val adapted = german.chipConfiguration()
        val exact = german.copy(exactCryptography = true).chipConfiguration()

        assertEquals(EcCurve.NIST_P256, adapted.pace.curve)
        assertEquals(1, adapted.adaptations.size)
        assertEquals(EcCurve.BRAINPOOL_P256R1, exact.pace.curve)
        assertTrue(exact.adaptations.isEmpty())
        // Chip Authentication and the signature keep the profile's curve either way
        assertEquals(EcCurve.BRAINPOOL_P256R1, adapted.chipAuthentication?.curve)
        assertEquals(KeySpec.Ec(EcCurve.BRAINPOOL_P256R1), adapted.sod.signerKey)
    }

    @Test
    fun `NIST equivalents keep at least the strength`() {
        assertEquals(EcCurve.NIST_P256, EcCurve.BRAINPOOL_P256R1.nistEquivalent)
        assertEquals(EcCurve.NIST_P384, EcCurve.BRAINPOOL_P320R1.nistEquivalent)
        assertEquals(EcCurve.NIST_P384, EcCurve.BRAINPOOL_P384R1.nistEquivalent)
        assertEquals(EcCurve.NIST_P521, EcCurve.BRAINPOOL_P512R1.nistEquivalent)
        EcCurve.entries.filter { it.isNist }.forEach { assertEquals(it, it.nistEquivalent) }
    }

    @Test
    fun `data groups follow the features`() {
        val bacOnly = holder.copy(accessControl = AccessControl.BAC_ONLY).chipConfiguration()
        assertEquals(listOf(1, 2), bacOnly.dataGroups)

        val withPace = holder.chipConfiguration()
        assertEquals(listOf(1, 2, 14), withPace.dataGroups)

        val german = holder.withChipProfile(ChipProfiles.byId("de-passport-2017")).chipConfiguration()
        assertEquals(listOf(1, 2, 3, 14), german.dataGroups)

        val dutch = holder.withChipProfile(ChipProfiles.byId("nl-passport")).chipConfiguration()
        assertEquals(listOf(1, 2, 3, 11, 12, 14, 15), dutch.dataGroups)

        // RSA Active Authentication needs no DG14 on a BAC-only chip
        val dutch2006 = holder.withChipProfile(ChipProfiles.byId("nl-passport-2006")).chipConfiguration()
        assertEquals(listOf(1, 2, 15), dutch2006.dataGroups)
        assertEquals(KeySpec.Rsa(1024), dutch2006.activeAuthentication)
    }

    @Test
    fun `a profile sets the chip settings, the document type and the country`() {
        val card = holder.copy(chipFault = ChipFault.CLONED_CHIP, activeAuthentication = true)
            .withChipProfile(ChipProfiles.byId("de-id-card"))

        assertEquals(AccessControl.PACE_ONLY, card.accessControl)
        assertEquals(DocumentType.ID_CARD, card.documentType)
        assertEquals("DEU", card.issuingCountry)
        assertEquals("DEU", card.nationality)
        assertFalse(card.activeAuthentication)
        assertEquals(ChipFault.NONE, card.chipFault, "a fault that needs Active Authentication goes with it")
        assertEquals("Anna Maria", card.firstName)
    }

    @Test
    fun `a profile that doesn't fit the document type falls back to the generic one`() {
        val data = holder.copy(chipProfileId = "de-passport-2017", documentType = DocumentType.ID_CARD)
        assertEquals(ChipProfiles.GENERIC_ID, data.chipProfile.id)
    }

    @Test
    fun `the German MRZ has D for the country and the Dutch one the personal number`() {
        val german = holder.copy(issuingCountry = "DEU", nationality = "DEU")
        assertTrue(german.toMrzData().startsWith("P<D<<ERIKSSON<<ANNA<MARIA"), german.toMrzData())

        val dutch = holder.withChipProfile(ChipProfiles.byId("nl-passport")).copy(personalNumber = "999999990")
        val line2 = dutch.toMrzData().substring(44)
        assertEquals("999999990<<<<<", line2.substring(28, 42))
        assertNull(holder.withChipProfile(ChipProfiles.byId("gb-passport")).chipConfiguration().activeAuthentication)
    }

    @Test
    fun `country presets are valid and use their profile`() {
        val today = LocalDate(2026, 9, 30)
        val presets = PassportPresets.all.filter { it.group == PresetGroup.COUNTRIES }
        assertTrue(presets.isNotEmpty())
        for (preset in presets) {
            val data = preset.build(today)
            assertEquals("profile-${data.chipProfileId}", preset.id)
            assertTrue(data.isValid(), preset.id)
        }
    }

    @Test
    fun `exact cryptography needs developer mode`() {
        assertFalse(AppSettings().usesExactCryptography)
        assertTrue(AppSettings(developerMode = true).usesExactCryptography)
        assertFalse(AppSettings(developerMode = true, exactCryptography = false).usesExactCryptography)
    }
}
