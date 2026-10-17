package com.hddev.smartemu.capture.readers

import com.hddev.smartemu.capture.profile.ProfileRepository
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.SessionCipher
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.CardServiceProtocolException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reader compatibility matrix: every curated profile (and every measured one in profiles/) is read by JMRTD with
 * SCUBA and Bouncy Castle, through the same HceCardService path the integration tests use. The result is pinned in
 * docs/reader-compatibility.json: a step that passed there and no longer does fails this test.
 *
 * Update the baseline on purpose, after triage: `UPDATE_READER_BASELINE=1` rewrites the JSON and the page.
 * `UPDATE_GENERATED_DOCS=1` rewrites the page only.
 */
class ReaderCompatibilityTest {
    private val page = File("../${ReaderMatrix.DOC_FILE}")
    private val baselineFile = File("../${ReaderMatrix.BASELINE_FILE}")

    private fun step(kind: StepKind, detail: String? = null, evidence: String? = null) = StepOutcome(kind, detail, evidence)

    private fun fakeResults(vararg steps: Pair<String, StepOutcome>) = ReaderResults(
        profiles = listOf(ProfileRun("p1", linkedMapOf(*steps))), titles = mapOf("p1" to "Test profile"), variants = emptyList()
    )

    // --- the mechanics, without the emulator ----------------------------------------------------------------------

    @Test
    fun `a pass that no longer passes is a regression, an improvement or a changed unsupported reason is not`() {
        val before = fakeResults(Steps.BAC to StepOutcome.pass, "pace:x" to step(StepKind.UNSUPPORTED, "feature"), Steps.EF_SOD to step(StepKind.FAIL, "boom"))
        val baseline = ReaderMatrix.baselineOf(before)

        assertEquals(emptyList(), ReaderMatrix.regressions(baseline, before))
        val worse = fakeResults(Steps.BAC to step(StepKind.FAIL, "broke"), "pace:x" to StepOutcome.pass, Steps.EF_SOD to step(StepKind.FAIL, "boom"))
        val problems = ReaderMatrix.regressions(baseline, worse)
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { it.contains("step 'bac' passed before and now is fail: broke") })
        assertTrue(problems.any { it.contains("new failure in step 'bac'") })
        val better = fakeResults(Steps.BAC to StepOutcome.pass, "pace:x" to StepOutcome.pass, Steps.EF_SOD to StepOutcome.pass)
        assertEquals(emptyList(), ReaderMatrix.regressions(baseline, better))
    }

    @Test
    fun `a missing step, a removed profile and an unpinned profile are reported`() {
        val baseline = ReaderMatrix.baselineOf(fakeResults(Steps.BAC to StepOutcome.pass))
        assertTrue(ReaderMatrix.regressions(baseline, fakeResults()).single().contains("step 'bac' passed before and now is missing"))
        assertTrue(ReaderMatrix.regressions(baseline, ReaderResults(emptyList(), emptyMap(), emptyList())).single().contains("not run"))
        val extra = ReaderResults(fakeResults(Steps.BAC to StepOutcome.pass).profiles + ProfileRun("p2", linkedMapOf(Steps.BAC to StepOutcome.pass)), emptyMap(), emptyList())
        assertTrue(ReaderMatrix.regressions(baseline, extra).single().contains("p2: not in the baseline"))
    }

    @Test
    fun `unsupported needs a limit with a source that matches the exception actually thrown`() {
        assertFailsWith<IllegalArgumentException> { ReaderLimit("feature", " ", { true }, { true }) }
        val limit = KnownReaderLimits.jmrtd.single()
        assertTrue(limit.source.isNotBlank())
        val refused = CardServiceProtocolException("PICC side exception in mapping nonce step", 2, 0x6A80)
        assertTrue(limit.matchesFailure(refused))
        assertFalse(limit.matchesFailure(CardServiceProtocolException("other", 2, 0x6982)), "another status word is a failure, not a known limit")
        assertFalse(limit.matchesFailure(CardServiceException("wrapped", 0x6A80)), "only the protocol exception of the mapping step")
        assertFalse(limit.matchesFailure(IllegalStateException("anything")))
    }

    @Test
    fun `the page labels unsupported with its feature and the evidence, and fails in bold`() {
        val text = ReaderMatrix.render(
            fakeResults(
                "pace:PACE-IM ECDH NIST P-256 AES-192" to step(StepKind.UNSUPPORTED, "PACE Integrated Mapping with AES-192", "CardServiceProtocolException [SW=6A80]"),
                Steps.EF_SOD to step(StepKind.FAIL, "boom")
            )
        )
        assertTrue(text.startsWith(ReaderMatrix.BEGIN_MARKER))
        assertTrue(text.contains("GENERATED. Do not edit"))
        assertTrue(text.contains("reader unsupported: PACE Integrated Mapping with AES-192 [1]"))
        assertTrue(text.contains("1. The exception the reader threw: `CardServiceProtocolException [SW=6A80]`"))
        assertTrue(text.contains("**fail: boom**"))
    }

    @Test
    fun `inject keeps the hand-written header`() {
        val first = ReaderMatrix.render(fakeResults(Steps.BAC to StepOutcome.pass))
        val updated = ReaderMatrix.inject("# Header\n\nRules.\n\n$first\nFooter\n", ReaderMatrix.render(fakeResults(Steps.BAC to step(StepKind.NOT_APPLICABLE))))
        assertTrue(updated.startsWith("# Header\n\nRules.\n\n"))
        assertTrue(updated.endsWith("\nFooter\n"))
        assertEquals(1, Regex(Regex.escape(ReaderMatrix.BEGIN_MARKER)).findAll(updated).count())
    }

    // --- the run --------------------------------------------------------------------------------------------------

    @Test
    fun `every profile completes with JMRTD and nothing that passed in the baseline regresses`() {
        val results = results
        assertEquals(ChipProfiles.all.size + measuredProfiles.size, results.profiles.size)
        if (System.getenv("UPDATE_READER_BASELINE") == "1") {
            baselineFile.writeText(ReaderMatrix.encodeBaseline(ReaderMatrix.baselineOf(results)))
        }
        val problems = ReaderMatrix.regressions(ReaderMatrix.decodeBaseline(baselineFile.readText()), results)
        assertTrue(problems.isEmpty(), "Reader compatibility regressed (triage, then UPDATE_READER_BASELINE=1 only if it is intended):\n" + problems.joinToString("\n"))
    }

    @Test
    fun `a failed step names its exception, never a bare fail`() {
        results.profiles.flatMap { run -> run.steps.values }.filter { it.kind == StepKind.FAIL }.forEach {
            assertTrue(!it.detail.isNullOrBlank(), "a failure has a reason")
        }
        results.variants.filter { it.outcome.kind == StepKind.UNSUPPORTED }.forEach {
            assertTrue(!it.outcome.evidence.isNullOrBlank(), "${it.domain} ${it.mapping}: an unsupported label has the exception behind it")
        }
    }

    @Test
    fun `JMRTD's Integrated Mapping with AES-192 is labelled unsupported on every domain, from the exception it threw`() {
        val im192 = results.variants.filter { it.mapping == PaceMapping.INTEGRATED && it.cipher == SessionCipher.AES_192 && !it.adapted }
        assertTrue(im192.isNotEmpty())
        im192.forEach {
            assertEquals(StepKind.UNSUPPORTED, it.outcome.kind, "${it.domain}: ${it.outcome.text}")
            assertEquals("PACE Integrated Mapping with AES-192", it.outcome.detail)
            assertTrue(assertNotNull(it.outcome.evidence).contains("SW=6A80"), it.outcome.evidence)
        }
        // And no other combination is labelled unsupported or fails
        assertEquals(emptyList(), results.variants.filter { it.outcome.kind == StepKind.FAIL }.map { "${it.domain} ${it.mapping} ${it.cipher}: ${it.outcome.text}" })
    }

    @Test
    fun `the emulator, not the reader, is what changes a PACE variant it can't offer`() {
        val adapted = results.variants.filter { it.adapted }
        // Integrated Mapping on NIST P-224 and PACE-CAM on a DH group are offered as something else
        assertTrue(adapted.any { it.domain == "NIST P-224" && it.mapping == PaceMapping.INTEGRATED })
        assertTrue(adapted.any { it.domain.startsWith("DH ") && it.mapping == PaceMapping.CHIP_AUTHENTICATION })
        adapted.forEach { assertEquals(StepKind.PASS, it.outcome.kind, "${it.domain} ${it.mapping} ${it.cipher}") }
    }

    @Test
    fun `the committed page is what the run renders to`() {
        val rendered = ReaderMatrix.inject(page.readText(), ReaderMatrix.render(results))
        if (System.getenv("UPDATE_GENERATED_DOCS") == "1" || System.getenv("UPDATE_READER_BASELINE") == "1") page.writeText(rendered)
        assertEquals(rendered, page.readText(), "docs/reader-compatibility.md is out of date: run with UPDATE_GENERATED_DOCS=1 to regenerate it")
    }

    private val measuredProfiles get() = ProfileRepository(File("../profiles")).profiles()

    private val results: ReaderResults get() = cached ?: ReaderCompatibilityRunner.run(measuredProfiles).also { cached = it }

    private companion object {
        /** One run for the class: about two hundred JMRTD sessions. */
        var cached: ReaderResults? = null
    }
}
