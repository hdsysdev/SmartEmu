package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.GoldenCapture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChipProfileEvidenceDocTest {
    private val generated = File("../${ChipProfileEvidenceDoc.FILE}")

    @Test
    fun `the page is generated from the JSON and says not to edit it`() {
        val text = ChipProfileEvidenceDoc.render(listOf(GoldenMeasuredProfile.profile))
        assertTrue(text.startsWith("<!--\nGENERATED FILE. Do not edit."))
        assertTrue(text.contains("> ${FidelityContract.STATEMENT}"))
        assertTrue(text.contains("| `golden-profile` | `${GoldenMeasuredProfile.profile.generation}` | 3 | tier-a v1 | 2 of "))
        assertTrue(text.contains("| `file:cardAccess:contents` | compared | MEASURED (n=3, held out 33333333-3333-4333-8333-333333333333) | 3 |"))
        assertTrue(text.contains("| `step:read-binary-max-length/le-df:timing` | not comparable | not comparable: ${NotComparableReasons.TIMING} | 3 |"))
        assertTrue(text.contains("| `link:technology` | not comparable | not comparable: ${NotComparableReasons.LINK} | 3 |"))
        assertTrue(text.contains("### Disagreements"))
        assertTrue(text.contains("`step:read-binary-max-length/extended-le-ffff:statusWord`"))
        assertTrue(text.contains("- NFC-B: HCE offers NFC-A only"))
    }

    @Test
    fun `it is deterministic and independent of the order of the profiles`() {
        val a = GoldenMeasuredProfile.profile
        val b = a.copy(id = "another-profile")
        assertEquals(ChipProfileEvidenceDoc.render(listOf(a, b)), ChipProfileEvidenceDoc.render(listOf(b, a)))
    }

    @Test
    fun `a single-capture profile is flagged for review and is not called measured`() {
        val one = ProfileMerger.merge(listOf(GoldenCapture.record), id = "synthetic-single")
        val text = ChipProfileEvidenceDoc.render(listOf(one))
        assertTrue(text.contains("Needs review"))
        assertTrue(text.contains("Needs more captures"))
        assertFalse(text.contains("| MEASURED ("))
        assertTrue(text.contains("Captured, n=1: needs more captures"))
    }

    @Test
    fun `with no profiles the page says so`() {
        assertTrue(ChipProfileEvidenceDoc.render(emptyList()).contains("No measured profile has been merged yet"))
    }

    @Test
    fun `the committed page is what the profiles directory renders to`() {
        val profiles = ProfileRepository(File("../profiles")).profiles()
        val rendered = ChipProfileEvidenceDoc.render(profiles)
        if (System.getenv("UPDATE_GENERATED_DOCS") == "1") generated.writeText(rendered)
        assertEquals(
            rendered, generated.readText(),
            "docs/chip-profile-evidence.measured.md is out of date: run with UPDATE_GENERATED_DOCS=1 to regenerate it"
        )
    }
}
