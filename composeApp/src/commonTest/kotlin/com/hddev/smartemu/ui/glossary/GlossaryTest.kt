package com.hddev.smartemu.ui.glossary

import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.ui.components.tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlossaryTest {

    private fun idsIn(text: String) = Glossary.find(text).map { it.term.id }

    private fun wordsIn(text: String) = Glossary.find(text).map { text.substring(it.range) }

    @Test
    fun `a PACE suite splits into its mapping, curve and cipher`() {
        assertEquals(listOf("PACE", "GM", "NIST", "AES"), idsIn("PACE-GM NIST P-256 AES-128"))
        assertEquals(listOf("PACE", "GM", "NIST P-256", "AES-128"), wordsIn("PACE-GM NIST P-256 AES-128"))
    }

    @Test
    fun `short abbreviations don't match inside longer ones`() {
        assertEquals(listOf("CSCA"), idsIn("signed under the test CSCA"))
        assertEquals(listOf("CAN"), idsIn("MRZ or CAN").drop(1))
        assertEquals(listOf("ECDSA", "BRAINPOOL"), idsIn("ECDSA brainpoolP256r1"))
        assertEquals(listOf("BRAINPOOL"), idsIn("GM on BP-256").drop(1))
        assertEquals(listOf("RSA"), idsIn("RSASSA-PSS"))
        assertTrue(idsIn("DATA, CAT, TAX, AAA").isEmpty())
    }

    @Test
    fun `data groups and files match whole`() {
        assertEquals(listOf("DG14"), idsIn("EF.DG14"))
        assertEquals(listOf("DG1", "DG2", "DG14"), idsIn("DG1, DG2, DG14"))
        assertEquals(listOf("SOD"), idsIn("EF.SOD"))
        assertEquals(listOf("EF.CardAccess", "PACE_SUITE"), idsIn("EF.CardAccess offers 2 more PACE suites"))
    }

    @Test
    fun `each term is marked once, where it first appears`() {
        val text = "PACE-GM NIST P-256 AES-128\nPACE-CAM NIST P-384 AES-256"
        assertEquals(listOf("PACE", "GM", "NIST", "AES", "CAM"), idsIn(text))
        assertEquals(text.indexOf("CAM"), Glossary.find(text).last().range.first)
    }

    @Test
    fun `every term finds itself and its related terms exist`() {
        Glossary.terms.forEach { term ->
            assertTrue(term.id in idsIn(term.term), "${term.id} isn't found in \"${term.term}\"")
            term.related.forEach { assertNotNull(Glossary[it], "${term.id} relates to unknown $it") }
        }
        assertEquals(Glossary.terms.size, Glossary.terms.map { it.id }.toSet().size, "Duplicate ids")
    }

    @Test
    fun `every tag a profile shows explains its abbreviations`() {
        ChipProfiles.all.flatMap { it.tags }.map { it.text }.distinct().forEach { tag ->
            val explained = Glossary.find(tag).joinToString("") { tag.substring(it.range) }
            val letters = tag.filter { it.isLetter() }.replace("only", "")
            assertEquals(letters, explained.filter { it.isLetter() }, "\"$tag\" has an unexplained abbreviation")
        }
    }
}
