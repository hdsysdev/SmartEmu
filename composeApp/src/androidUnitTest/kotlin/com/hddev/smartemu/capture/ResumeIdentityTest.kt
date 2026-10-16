package com.hddev.smartemu.capture

import com.hddev.smartemu.capture.auth.EmulatedChip
import com.hddev.smartemu.data.ChipProfiles
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A resume after a lost tag must be of the same document: the application's answer and the public files are read
 * again and compared with the draft's, with no UID involved. Two emulated chips of different profiles stand for two
 * documents.
 */
class ResumeIdentityTest {
    private lateinit var chip: EmulatedChip
    private val runner = CaptureRunner()

    @Before fun setUp() { chip = EmulatedChip() }
    @After fun tearDown() { chip.close() }

    private fun draftOf(profileId: String, loseAt: Int = 90): CaptureResult.Aborted {
        val data = chip.documentFor(ChipProfiles.byId(profileId))
        return assertIs<CaptureResult.Aborted>(runner.run(TagLossTransport(chip.transport(data), loseAt)))
    }

    private fun transportOf(profileId: String) = chip.transport(chip.documentFor(ChipProfiles.byId(profileId)))

    @Test
    fun `the same document resumes under the same capture id`() {
        val aborted = draftOf("de-passport-2023")
        val resumed = assertIs<CaptureResult.Complete>(runner.run(transportOf("de-passport-2023"), resumeFrom = aborted.draft))
        assertEquals(ResumeOutcome.RESUMED, resumed.resumeOutcome)
        assertEquals(aborted.record.captureId, resumed.record.captureId)
        assertNull(resumed.record.aborted)
        assertEquals(ProbePack.tierA.probes.map { it.id }, resumed.record.probes.map { it.probeId })
    }

    @Test
    fun `another document throws the draft away and starts again under a new capture id`() {
        // PACE-only, BAC-only, a CAM chip and an ID card: each pair differs in the application answer or the public files
        val pairs = listOf(
            "de-passport-2023" to "us-passport-2021",
            "us-passport-2021" to "de-passport-2023",
            "de-passport-2023" to "nl-passport",
            "nl-passport" to "fr-id-card-2024"
        )
        for ((first, second) in pairs) {
            val aborted = draftOf(first)
            val result = assertIs<CaptureResult.Complete>(runner.run(transportOf(second), resumeFrom = aborted.draft), "$first then $second")
            assertEquals(ResumeOutcome.DIFFERENT_DOCUMENT, result.resumeOutcome, "$first then $second")
            assertNotEquals(aborted.record.captureId, result.record.captureId, "$first then $second")
            // What is in the record is the second document's, all of it: the same as a fresh capture of it
            val fresh = assertIs<CaptureResult.Complete>(runner.run(transportOf(second))).record
            assertEquals(fresh.files, result.record.files, "$first then $second")
            assertEquals(fresh.application, result.record.application)
            assertEquals(fresh.bacCommands, result.record.bacCommands)
            assertEquals(fresh.probes.map { p -> p.probeId to p.steps.map { it.statusWord } }, result.record.probes.map { p -> p.probeId to p.steps.map { it.statusWord } })
            assertEquals(fresh.fingerprint, result.record.fingerprint)
        }
    }

    @Test
    fun `what the discarded draft remembered is discarded with it`() {
        // The first document's draft says an EXTERNAL AUTHENTICATE was sent; the second document has had none
        val aborted = draftOf("de-passport-2023")
        assertTrue(aborted.draftState.externalAuthenticateSent)
        val other = chip.transport(chip.documentFor(ChipProfiles.byId("us-passport-2021")))
        val watching = com.hddev.smartemu.capture.auth.WatchingTransport(other)
        val result = assertIs<CaptureResult.Complete>(runner.run(watching, resumeFrom = aborted.draft))
        assertEquals(ResumeOutcome.DIFFERENT_DOCUMENT, result.resumeOutcome)
        assertEquals(2, watching.count(com.hddev.smartemu.capture.auth.WatchingTransport.INS_EXTERNAL_AUTHENTICATE), "the new document's own out-of-sequence probe runs")
        assertTrue(result.record.probes.any { it.probeId == "external-authenticate-out-of-sequence" })
    }

    @Test
    fun `a draft with nothing public in it can't tell, and resumes`() {
        // The tag is lost on the very first command: the record has no application and no file
        val aborted = draftOf("de-passport-2023", loseAt = 1)
        assertNull(aborted.record.application)
        val resumed = assertIs<CaptureResult.Complete>(runner.run(transportOf("us-passport-2021"), resumeFrom = aborted.draft))
        assertEquals(ResumeOutcome.RESUMED, resumed.resumeOutcome)
        assertEquals(aborted.record.captureId, resumed.record.captureId)
    }

    @Test
    fun `no draft is a new run`() {
        val result = assertIs<CaptureResult.Complete>(runner.run(transportOf("de-passport-2023")))
        assertEquals(ResumeOutcome.NEW_RUN, result.resumeOutcome)
    }

    @Test
    fun `the check reads the public files again, and no UID is asked for or kept`() {
        val aborted = draftOf("de-passport-2023")
        val watching = com.hddev.smartemu.capture.auth.WatchingTransport(transportOf("de-passport-2023"))
        runner.run(watching, resumeFrom = aborted.draft)
        // Only SELECT, READ BINARY and the probes' own commands are sent: nothing that asks a chip for an identifier
        assertTrue(watching.commands.none { it.size > 1 && (it[1].toInt() and 0xFF) in setOf(0xCA, 0xCB) })
    }
}
