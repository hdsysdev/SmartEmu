package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TD1 MRZs, name transliteration and the ready-made documents.
 */
class DocumentFeaturesTest {

    private val today = LocalDate(2026, 9, 30)

    @Test
    fun `a card's MRZ has the ICAO specimen's three lines and check digits`() {
        // ICAO 9303-5's TD1 specimen, with a real country for Utopia and ID for its document code
        val card = PassportData(
            documentType = DocumentType.ID_CARD,
            passportNumber = "D23145890",
            dateOfBirth = LocalDate(1974, 8, 12),
            expiryDate = LocalDate(2012, 4, 15),
            issuingCountry = "NLD",
            nationality = "NLD",
            firstName = "Anna Maria",
            lastName = "Eriksson",
            gender = "F"
        )

        assertEquals(
            listOf(
                "IDNLDD231458907<<<<<<<<<<<<<<<",
                "7408122F1204159NLD<<<<<<<<<<<6",
                "ERIKSSON<<ANNA<MARIA<<<<<<<<<<"
            ),
            card.toMrzLines()
        )
        assertEquals("D23145890774081221204159", card.mrzKey())
    }

    @Test
    fun `names on a card are cut to its 30 character line`() {
        val card = PassportPresets.byId("long-name")!!.build(today).copy(documentType = DocumentType.ID_CARD)

        val nameLine = card.toMrzLines()[2]

        assertEquals(30, nameLine.length)
        assertEquals("MONTGOMERY<WORTHINGTON<<MAXIMI", nameLine)
    }

    @Test
    fun `accented letters are spelled out as ICAO 9303 transliterates them`() {
        assertEquals("MUELLER", MrzTransliteration.transliterate("MÜLLER"))
        assertEquals("OESTERGAARD", MrzTransliteration.transliterate("ØSTERGÅRD"))
        assertEquals("ZOE RENEE", MrzTransliteration.transliterate("ZOË RENÉE"))
        assertEquals("STRASSE THORA", MrzTransliteration.transliterate("STRAẞE ÞORA"))
        assertEquals("AEGIR", MrzTransliteration.transliterate("ÆGIR"))
    }

    @Test
    fun `the MRZ name uses the transliterated name`() {
        val data = PassportPresets.byId("accents")!!.build(today)

        assertTrue(data.toMrzLines()[0].startsWith("P<DNKMUELLER<OESTERGAARD<<ZOE<RENEE<"), data.toMrzLines()[0])
    }

    @Test
    fun `every preset is a valid document`() {
        for (preset in PassportPresets.all) {
            // Applying a preset gives it a CAN; it has none of its own
            val data = preset.build(today).copy(can = "123456")
            assertTrue(data.isValid(), "${preset.id}: ${data.getValidationErrors()}")
            assertEquals(data.documentType.mrzLineCount, data.toMrzLines().size, preset.id)
        }
    }

    @Test
    fun `preset ids are unique and found by id`() {
        val ids = PassportPresets.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(PassportPresets.byId("everyday"))
        assertNull(PassportPresets.byId("no-such-preset"))
    }

    @Test
    fun `dated presets stay what they say on any day`() {
        for (day in listOf(today, LocalDate(2031, 2, 28), LocalDate(2040, 12, 31))) {
            assertTrue(PassportPresets.byId("expired")!!.build(day).expiryDate!! < day)
            assertTrue(PassportPresets.byId("everyday")!!.build(day).expiryDate!! > day)
        }
    }

    @Test
    fun `each forged preset has its fault, and a cloned chip has Active Authentication`() {
        val forged = PassportPresets.all.filter { it.group == PresetGroup.FORGED }.map { it.build(today) }

        assertEquals(ChipFault.entries.filter { it != ChipFault.NONE }.toSet(), forged.map { it.chipFault }.toSet())
        forged.forEach { assertEquals(it.chipFault.needsActiveAuthentication, it.activeAuthentication) }
    }
}
