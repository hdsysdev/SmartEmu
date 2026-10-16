package com.hddev.smartemu.capture

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureRunnerTest {
    private val runner = CaptureRunner()

    private val selectMasterFile = hexBytes("00A4000C023F00")

    /**
     * A chip that refuses everything but the master file, which it selects: every step has an answer, so a run goes
     * through the whole pack. (One that refuses the master file too has its master-file steps left out: see below.)
     */
    private fun refusingChip(device: ReaderDevice = testReader) =
        ScriptedTransport(device) { command -> if (command.contentEquals(selectMasterFile)) response(0x9000) else response(0x6A82) }

    private fun ByteArray.ins() = this[1].toInt() and 0xFF

    @Test
    fun `runs every extractor and probe step`() {
        val transport = refusingChip()
        val result = assertIs<CaptureResult.Complete>(runner.run(transport))
        val record = result.record
        assertNull(record.aborted)
        assertEquals(ProbePack.tierA.probes.map { it.id }, record.probes.map { it.probeId })
        assertEquals(ProbePack.tierA.stepCount, record.probes.sumOf { it.steps.size })
        assertEquals(0x6A82, record.files.cardAccess?.selectStatusWord)
        assertEquals(0x6A82, record.application?.selectStatusWord)
        assertEquals(BacCommandObservation(0x6A82, 0x6A82), record.bacCommands)
        assertEquals(testReader, record.reader)
        assertTrue(CaptureFingerprint.isConsistent(record))
    }

    @Test
    fun `reports progress before each step and at the end`() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        runner.run(refusingChip()) { name, done, total -> calls += Triple(name, done, total) }
        val total = runner.totalSteps
        assertEquals(total + 1, calls.size)
        assertEquals(Triple("ef-card-access", 0, total), calls.first())
        assertEquals(Triple("done", total, total), calls.last())
        assertEquals((0 until total).toList(), calls.dropLast(1).map { it.second })
        // tier-a v2 starts with its out-of-sequence EXTERNAL AUTHENTICATE probe
        assertEquals("external-authenticate-out-of-sequence/lc-40-le-28", calls[defaultExtractors().size].first)
    }

    @Test
    fun `the progress callback can be named, positional or trailing`() {
        var called = 0
        runner.run(refusingChip(), progress = { _: String, _: Int, _: Int -> called++ }, resumeFrom = null)
        runner.run(refusingChip(), { _: String, _: Int, _: Int -> called++ })
        runner.run(refusingChip()) { _, _, _ -> called++ }
        assertEquals(3 * (runner.totalSteps + 1), called)
    }

    @Test
    fun `a lost tag aborts with TAG_LOST and the draft resumes under the same id`() {
        val full = assertIs<CaptureResult.Complete>(runner.run(refusingChip())).record

        val lossy = TagLossTransport(refusingChip(), loseAt = 60)
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(lossy))
        assertEquals(AbortReason.TAG_LOST, aborted.reason)
        assertEquals(AbortReason.TAG_LOST, aborted.record.aborted)
        assertTrue(aborted.record.probes.size < full.probes.size)
        assertTrue(aborted.record.probes.isNotEmpty(), "the probes done before the loss are kept")

        val second = refusingChip()
        val resumed = assertIs<CaptureResult.Complete>(runner.run(second, resumeFrom = aborted.draft)).record
        assertEquals(aborted.record.captureId, resumed.captureId)
        assertNull(resumed.aborted)
        assertEquals(full.probes.size, resumed.probes.size)
        assertEquals(full.fingerprint, resumed.fingerprint)
        // Only the unfinished part was sent again
        val fullSent = refusingChip().also { runner.run(it) }.sent.size
        assertTrue(second.sent.size < fullSent, "${second.sent.size} commands on resume, $fullSent on a full run")
    }

    @Test
    fun `a probe cut short by a lost tag is absent, not partial`() {
        val sent = refusingChip().also { runner.run(it) }.sent.size
        // Lose the tag at each exchange in turn: a probe is in the record whole or not at all
        for (loseAt in 1..sent step 7) {
            val result = runner.run(TagLossTransport(refusingChip(), loseAt))
            val record = (result as CaptureResult.Aborted).record
            record.probes.forEach { probe ->
                val expected = ProbePack.tierA.probes.first { it.id == probe.probeId }.steps.size
                assertEquals(expected, probe.steps.size, "${probe.probeId} when lost at $loseAt")
            }
        }
    }

    @Test
    fun `a blocked status word aborts at once and is not resumed`() {
        val transport = ScriptedTransport { command ->
            if (command.ins() == 0x84) response(0x6983) else response(0x6A82)
        }
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(transport))
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason)
        // The GET CHALLENGE of the bac-commands observation is the pack's own `le-08` step, so the run stopped there
        assertEquals(0x6983, aborted.record.bacCommands?.getChallenge)
        assertEquals("get-challenge-lengths", aborted.record.probes.last().probeId)
        assertEquals(listOf("le-08"), aborted.record.probes.last().steps.map { it.stepId })
        val sentAtAbort = transport.sent.size

        val again = assertIs<CaptureResult.Aborted>(runner.run(transport, resumeFrom = aborted.draft))
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, again.reason)
        assertEquals(sentAtAbort, transport.sent.size, "nothing is sent to a blocked document")
    }

    @Test
    fun `a blocked status word in a probe is recorded with the step that got it, and no step follows`() {
        val transport = ScriptedTransport { command ->
            // 6984 for the Le 04 GET CHALLENGE probe step only
            if (command.contentEquals(hexBytes("0084000004"))) response(0x6984) else response(0x6A82)
        }
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(transport))
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason)
        val last = aborted.record.probes.last()
        assertEquals("get-challenge-lengths", last.probeId)
        assertEquals(0x6984, last.steps.last().statusWord)
        assertEquals("le-04", last.steps.last().stepId)
        assertTrue(transport.sent.none { it.contentEquals(hexBytes("0084000007")) })
    }

    @Test
    fun `extended-length steps are skipped on a reader that can't send them`() {
        val record = (runner.run(refusingChip(testReader.copy(controllerExtendedLengthSupported = false))) as CaptureResult.Complete).record
        val stepIds = record.probes.flatMap { it.steps }.map { it.stepId }
        assertTrue("le-df" in stepIds)
        assertTrue(stepIds.none { it.startsWith("extended") })
    }

    @Test
    fun `repeats are timed and a flaky chip is flagged`() {
        var calls = 0
        val transport = ScriptedTransport { command ->
            if (command.contentEquals(hexBytes("0084000007"))) response(if (calls++ == 0) 0x6700 else 0x6A82) else response(0x6A82)
        }
        val record = (runner.run(transport) as CaptureResult.Complete).record
        val step = record.probes.first { it.probeId == "get-challenge-lengths" }.steps.first { it.stepId == "le-07" }
        assertEquals(false, step.repeatsAgree)
        assertEquals(0x6700, step.statusWord)
        assertEquals(3, step.timing?.samples)
        val outOfSequence = record.probes.first { it.probeId == "external-authenticate-out-of-sequence" }.steps
        assertTrue(outOfSequence.all { it.timing?.samples == 1 })
    }

    @Test
    fun `a transport that cannot tell its link is recorded as NFC-A`() {
        val transport = object : ApduTransport {
            override fun transmit(command: ByteArray) = response(0x6A82)
            override fun readerDevice() = testReader
        }
        assertEquals(NfcTechnology.NFC_A, (runner.run(transport) as CaptureResult.Complete).record.link.technology)
    }

    @Test
    fun `the month comes from the clock and has no day`() {
        val clock = object : kotlin.time.Clock {
            override fun now() = kotlin.time.Instant.parse("2026-07-31T23:59:59Z")
        }
        val record = (CaptureRunner(clock = clock).run(refusingChip()) as CaptureResult.Complete).record
        assertEquals("2026-07", record.captureMonth)
        assertNotEquals("", record.captureId)
    }

    // ---- EXTERNAL AUTHENTICATE ----

    private val externalAuthenticateProbe = "external-authenticate-out-of-sequence"

    @Test
    fun `a whole run sends two EXTERNAL AUTHENTICATE, the first right after a fresh SELECT and before any GET CHALLENGE`() {
        val transport = refusingChip()
        runner.run(transport)
        val instructions = transport.sent.map { it.ins() }
        assertEquals(2, instructions.count { it == 0x82 }, "the extractor adds none: its observation comes from the pack's own steps")
        val first = instructions.indexOf(0x82)
        assertTrue(first < instructions.indexOf(0x84), "no GET CHALLENGE before the first EXTERNAL AUTHENTICATE")
        assertContentEquals(Apdu.selectEmrtdApplication, transport.sent[first - 1], "directly after a fresh SELECT of the application")
        // The run's first command is a read of the public files, and the pack's first probe is the EXTERNAL AUTHENTICATE one
        assertEquals(externalAuthenticateProbe, ProbePack.tierA.probes.first().id)
    }

    @Test
    fun `the bac-commands observation of tier-a v2 is the answer of the pack's own two steps`() {
        val transport = ScriptedTransport { command ->
            when {
                command.contentEquals(selectMasterFile) -> response(0x9000)
                command.ins() == 0x82 -> response(0x6985)
                command.contentEquals(hexBytes("0084000008")) -> response(0x9000, ByteArray(8))
                else -> response(0x6A82)
            }
        }
        val record = (runner.run(transport) as CaptureResult.Complete).record
        assertEquals(BacCommandObservation(getChallenge = 0x9000, externalAuthenticate = 0x6985), record.bacCommands)
        // Nothing of the old extractor's: only the pack's own le-08 step (three repeats) sent a GET CHALLENGE with Le 08
        assertEquals(3, transport.sent.count { it.contentEquals(hexBytes("0084000008")) })
    }

    @Test
    fun `tier-a v1 still runs as it was published, with its extractor's EXTERNAL AUTHENTICATE`() {
        val transport = refusingChip()
        val record = (CaptureRunner(pack = ProbePack.tierAV1).run(transport) as CaptureResult.Complete).record
        assertEquals(ProbePackRef("tier-a", 1), record.probePack)
        assertEquals(3, transport.sent.count { it.ins() == 0x82 }, "v1: the extractor's one and the probe's two")
        assertEquals(BacCommandObservation(0x6A82, 0x6A82), record.bacCommands)
        assertEquals("select-aid", record.probes.first().probeId)
    }

    @Test
    fun `6300 after an EXTERNAL AUTHENTICATE ends the run at once in tier a`() {
        val transport = ScriptedTransport { command -> if (command.ins() == 0x82) response(0x6300) else response(0x6A82) }
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(transport))
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason)
        assertEquals(1, transport.sent.count { it.ins() == 0x82 }, "no second one after the first was refused with a counter-like answer")
        assertEquals(0x82, transport.sent.last().ins())
        assertEquals(0x6300, aborted.record.probes.single().steps.single().statusWord)
        // A draft that ended like that is not resumed
        val again = refusingChip()
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, assertIs<CaptureResult.Aborted>(runner.run(again, resumeFrom = aborted.draft)).reason)
        assertTrue(again.sent.isEmpty())
    }

    @Test
    fun `6300 to a command that is not an EXTERNAL AUTHENTICATE is an ordinary refusal`() {
        val transport = ScriptedTransport { command -> if (command.ins() == 0x84) response(0x6300) else response(0x6A82) }
        assertIs<CaptureResult.Complete>(runner.run(transport))
    }

    @Test
    fun `any 63Cx ends the run in tier a and tier b, whatever the command`() {
        for (statusWord in listOf(0x63C0, 0x63C1, 0x63C2, 0x63C3, 0x63CF)) {
            for (pack in listOf(ProbePack.tierA, ProbePack.tierB)) {
                val transport = ScriptedTransport { command -> if (command.ins() == 0x84) response(statusWord) else response(0x6A82) }
                val aborted = assertIs<CaptureResult.Aborted>(CaptureRunner(extractors = emptyList(), pack = pack).run(transport), "%04X in %s".format(statusWord, pack.id))
                assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason)
                // Nothing at all follows the answer
                assertEquals(0x84, transport.sent.last().ins())
            }
        }
    }

    @Test
    fun `tier c carries on through 6300 and 63C2 and stops at 63C1 or lower`() {
        val tierC = CaptureRunner(extractors = emptyList(), pack = ProbePack.tierC, acknowledgeSacrificialCard = true)
        fun run(vararg answers: Int): ScriptedTransport {
            var attempt = 0
            val transport = ScriptedTransport { command ->
                if (command.ins() == 0x82) response(answers[minOf(attempt++, answers.lastIndex)]) else response(0x6A82)
            }
            tierC.run(transport)
            return transport
        }
        // Wrong keys are expected to get 6300: all five attempts are made
        assertEquals(TierCProbePack.WRONG_ATTEMPTS, run(0x6300).sent.count { it.ins() == 0x82 })
        assertEquals(TierCProbePack.WRONG_ATTEMPTS, run(0x63C4, 0x63C3, 0x63C2, 0x6300, 0x6300).sent.count { it.ins() == 0x82 })
        // The counter at 1 or 0 ends it at that attempt
        assertEquals(3, run(0x6300, 0x63C2, 0x63C1).sent.count { it.ins() == 0x82 })
        assertEquals(2, run(0x6300, 0x63C0).sent.count { it.ins() == 0x82 })
        // Blocked and invalidated end it at once
        assertEquals(1, run(0x6983).sent.count { it.ins() == 0x82 })
        assertEquals(1, run(0x6984).sent.count { it.ins() == 0x82 })
    }

    private class LoseOnExternalAuthenticate(private val inner: ApduTransport, private val nth: Int) : ApduTransport {
        private var seen = 0
        override fun transmit(command: ByteArray): ByteArray {
            if ((command[1].toInt() and 0xFF) == 0x82 && ++seen == nth) throw TagLostException()
            return inner.transmit(command)
        }
        override fun linkInfo() = inner.linkInfo()
        override fun readerDevice() = inner.readerDevice()
    }

    @Test
    fun `a resume after a tag lost around an EXTERNAL AUTHENTICATE never sends one again`() {
        for (nth in 1..2) {
            val aborted = assertIs<CaptureResult.Aborted>(runner.run(LoseOnExternalAuthenticate(refusingChip(), nth)), "EA $nth")
            assertEquals(AbortReason.TAG_LOST, aborted.reason)
            assertTrue(aborted.draftState.externalAuthenticateSent, "the draft remembers it, even if the answer never came")
            assertTrue(aborted.record.probes.none { it.probeId == externalAuthenticateProbe }, "a probe cut short is not in the record")

            val second = refusingChip()
            val resumed = assertIs<CaptureResult.Complete>(runner.run(second, resumeFrom = aborted.draft))
            assertEquals(ResumeOutcome.RESUMED, resumed.resumeOutcome)
            assertEquals(0, second.sent.count { it.ins() == 0x82 }, "no EXTERNAL AUTHENTICATE on resume (EA $nth)")
            assertEquals(aborted.record.captureId, resumed.record.captureId)
            // The probe is missing from the record, and the rest is there
            assertEquals(ProbePack.tierA.probes.map { it.id } - externalAuthenticateProbe, resumed.record.probes.map { it.probeId })
            assertNull(resumed.record.bacCommands?.externalAuthenticate)
            assertEquals(0x6A82, resumed.record.bacCommands?.getChallenge)
            assertTrue(resumed.draftState.externalAuthenticateSent, "and it stays remembered")
        }
    }

    @Test
    fun `a resume after a tag lost before any EXTERNAL AUTHENTICATE sends them`() {
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(TagLossTransport(refusingChip(), loseAt = 3)))
        assertEquals(false, aborted.draftState.externalAuthenticateSent)
        val second = refusingChip()
        val resumed = assertIs<CaptureResult.Complete>(runner.run(second, resumeFrom = aborted.draft)).record
        assertEquals(2, second.sent.count { it.ins() == 0x82 })
        assertEquals(ProbePack.tierA.probes.map { it.id }, resumed.probes.map { it.probeId })
    }

    // ---- SELECT of the master file refused ----

    @Test
    fun `a chip that refuses the master file has its master-file steps recorded as not run, with the reason`() {
        val transport = ScriptedTransport { response(0x6A82) }
        val record = (runner.run(transport) as CaptureResult.Complete).record
        val selectAid = record.probes.first { it.probeId == "select-aid" }
        assertEquals(8, selectAid.steps.size, "every step is listed")
        selectAid.steps.forEach {
            assertNull(it.statusWord, "${it.stepId} did not run")
            assertEquals(0x6A82, it.setupStatusWord, "the SELECT of the master file says why")
        }
        // The partial-AID step's command is sent by no other step, and it was never sent
        assertTrue(transport.sent.none { it.contentEquals(hexBytes("00A4040C05A000000247")) })
        // Steps that start from the application still run: that SELECT is by AID
        val getChallenge = record.probes.first { it.probeId == "get-challenge-lengths" }.steps.first { it.stepId == "le-08" }
        assertEquals(0x6A82, getChallenge.statusWord)
        assertTrue(transport.sent.any { it.contentEquals(hexBytes("0084000008")) })
        assertTrue(CaptureFingerprint.isConsistent(record))
    }

    @Test
    fun `a chip that refuses the master file but selects EF CardAccess still has its read steps run`() {
        val transport = ScriptedTransport { command ->
            if (command.contentEquals(hexBytes("00A4020C02011C"))) response(0x9000) else response(0x6A82)
        }
        val record = (runner.run(transport) as CaptureResult.Complete).record
        val read = record.probes.first { it.probeId == "read-binary-wrong-length" }.steps.first { it.stepId == "le-04" }
        assertEquals(0x6A82, read.statusWord)
        assertEquals(0x9000, read.setupStatusWord)
    }
}
