package com.hddev.smartemu.capture.probes

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.AbortReason
import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.ProbeTier
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.SacrificialCardNotAcknowledgedException
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The packs against the emulated chip (as Android's HCE stack drives it) over every chip profile. */
class EmulatorPacksTest {
    private val today = LocalDate(2026, 9, 30)

    private val base = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F"
    )

    private lateinit var service: PassportHceService

    @Before
    fun setUp() {
        service = PassportHceService()
        service.onCreate()
    }

    @After
    fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        service.onDestroy()
    }

    private fun documentFor(profile: ChipProfile): PassportData =
        PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today) ?: base.withChipProfile(profile)

    private fun transportFor(profile: ChipProfile): RecordingTransport {
        PassportHceService.setSharedPassportData(documentFor(profile))
        return RecordingTransport(EmulatorTransport(service))
    }

    @Test
    fun `every step of tier b yields a status word on every profile and the emulator never throws`() {
        val problems = mutableListOf<String>()
        ChipProfiles.all.forEach { profile ->
            val transport = transportFor(profile)
            val result = CaptureRunner(pack = ProbePack.tierB).run(transport)
            val record = assertIs<CaptureResult.Complete>(result, profile.id).record
            problems += transport.chipFailures.map { "${profile.id}: emulator threw on $it" }
            val steps = record.probes.flatMap { it.steps }
            if (steps.size != ProbePack.tierB.stepCount) problems += "${profile.id}: ${steps.size} of ${ProbePack.tierB.stepCount} steps"
            steps.filter { it.statusWord == null }.forEach { problems += "${profile.id}: no status word for ${it.stepId}" }
            record.probes.forEach { probe ->
                if (probe.tier != ProbeTier.B || probe.packVersion != 1) problems += "${profile.id}: ${probe.probeId} tier/version"
                if (ProbePacks.probeOf(probe) == null) problems += "${profile.id}: ${probe.probeId} not in the registry"
            }
            if (!CaptureFingerprint.isConsistent(record)) problems += "${profile.id}: fingerprint"
            RedactionLint.check(CaptureJson.encode(record)).takeIf { it.isNotEmpty() }?.let { problems += "${profile.id}: lint $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `tier a and b run in one session, each under its own pack`() {
        val profile = ChipProfiles.all.first()
        val transport = transportFor(profile)
        val runner = CaptureRunner(pack = ProbePack.tierA, additionalPacks = listOf(ProbePack.tierB))
        val progress = mutableListOf<Int>()
        val record = assertIs<CaptureResult.Complete>(runner.run(transport) { _, done, _ -> progress += done }).record
        assertEquals(ProbePackRef("tier-a", 2), record.probePack)
        assertEquals(listOf(ProbePackRef("tier-b", 1)), record.additionalProbePacks)
        assertEquals(
            ProbePack.tierA.probes.map { it.id } + ProbePack.tierB.probes.map { it.id },
            record.probes.map { it.probeId }
        )
        assertEquals(ProbePack.tierA.stepCount + ProbePack.tierB.stepCount, record.probes.sumOf { it.steps.size })
        assertEquals(runner.totalSteps + 1, progress.size)
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(record)))
        assertTrue(transport.chipFailures.isEmpty(), transport.chipFailures.toString())

        // The tier-a part is the same as a tier-a only run, step for step, so adding tier b disturbs nothing before it
        val aloneTransport = transportFor(profile)
        val alone = assertIs<CaptureResult.Complete>(CaptureRunner().run(aloneTransport)).record
        assertEquals(
            alone.probes.map { p -> p.steps.map { it.statusWord } },
            record.probes.take(alone.probes.size).map { p -> p.steps.map { it.statusWord } }
        )
    }

    @Test
    fun `a tier a and b capture resumes after a lost tag under the same packs`() {
        val profile = ChipProfiles.all.first()
        val runner = CaptureRunner(pack = ProbePack.tierA, additionalPacks = listOf(ProbePack.tierB))
        val lossy = com.hddev.smartemu.capture.TagLossTransport(transportFor(profile), loseAt = ProbePack.tierA.stepCount * 6)
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(lossy))
        assertEquals(AbortReason.TAG_LOST, aborted.reason)
        val resumed = assertIs<CaptureResult.Complete>(runner.run(transportFor(profile), resumeFrom = aborted.draft)).record
        assertEquals(ProbePack.tierA.stepCount + ProbePack.tierB.stepCount, resumed.probes.sumOf { it.steps.size })
        assertEquals(aborted.record.captureId, resumed.captureId)
        // A draft of a different pack list is not resumed
        val other = assertIs<CaptureResult.Complete>(CaptureRunner(pack = ProbePack.tierA).run(transportFor(profile), resumeFrom = aborted.draft)).record
        assertTrue(other.captureId != aborted.record.captureId)
    }

    @Test
    fun `tier b sends no PIN or PUK command and no EXTERNAL AUTHENTICATE to the emulator`() {
        ChipProfiles.all.forEach { profile ->
            val transport = transportFor(profile)
            assertIs<CaptureResult.Complete>(CaptureRunner(pack = ProbePack.tierB).run(transport))
            val instructions = transport.sent.map { it.ins() }
            assertTrue(instructions.none { it in setOf(0x20, 0x24, 0x2C) }, profile.id)
            assertTrue(instructions.count { it == 0x82 } <= 2, profile.id)
            // And every command sent is one a step, its setup or the extractors can produce
            val documented = ProbePack.tierB.allSteps().flatMap { (_, step) -> step.allCommands() }.map { it.toList() }.toSet()
            val extractorInstructions = setOf(0xA4, 0xB0, 0x84, 0x82)
            transport.sent.forEach { command ->
                assertTrue(command.toList() in documented || command.ins() in extractorInstructions, "${profile.id}: undocumented ${command.toList()}")
            }
        }
    }

    @Test
    fun `tier c refuses without the acknowledgement and sends nothing`() {
        val transport = transportFor(ChipProfiles.all.first())
        val runner = CaptureRunner(pack = ProbePack.tierC)
        val refusal = assertFailsWith<SacrificialCardNotAcknowledgedException> { runner.run(transport) }
        assertEquals(listOf(ProbePackRef("tier-c", 1)), refusal.packs)
        assertTrue(transport.sent.isEmpty())
        // As an additional pack, and via the progress overload
        val combined = CaptureRunner(pack = ProbePack.tierA, additionalPacks = listOf(ProbePack.tierB, ProbePack.tierC))
        assertFailsWith<SacrificialCardNotAcknowledgedException> { combined.run(transport) { _, _, _ -> } }
        assertTrue(transport.sent.isEmpty())
        // A and B alone, and the default runner, need no acknowledgement
        assertNull(runCatching { CaptureRunner(pack = ProbePack.tierB).totalSteps }.exceptionOrNull())
    }

    @Test
    fun `tier c with the acknowledgement sends only what it documents, at most five wrong attempts`() {
        val documented = ProbePack.tierC.allSteps().flatMap { (_, step) -> step.allCommands() }.map { it.toList() }.toSet()
        ChipProfiles.all.forEach { profile ->
            val transport = transportFor(profile)
            val runner = CaptureRunner(extractors = emptyList(), pack = ProbePack.tierC, acknowledgeSacrificialCard = true)
            val result = runner.run(transport)
            val record = (result as? CaptureResult.Complete)?.record ?: (result as CaptureResult.Aborted).record
            assertTrue(transport.chipFailures.isEmpty(), "${profile.id}: ${transport.chipFailures}")
            transport.sent.forEach { assertTrue(it.toList() in documented, "${profile.id}: undocumented ${it.toList()}") }
            assertTrue(transport.sent.count { it.ins() == 0x82 } <= 5, profile.id)
            assertTrue(transport.sent.none { it.ins() in setOf(0x20, 0x24, 0x2C) }, profile.id)
            assertEquals(ProbePackRef("tier-c", 1), record.probePack)
            record.probes.forEach { assertEquals(ProbeTier.C, it.tier) }
            if (record.aborted == null) {
                assertEquals(ProbePack.tierC.stepCount, record.probes.sumOf { it.steps.size }, profile.id)
            } else {
                assertEquals(AbortReason.BLOCKED_STATUS_WORD, record.aborted, profile.id)
            }
        }
    }

    @Test
    fun `tier c stops at a blocked status word`() {
        val chip = com.hddev.smartemu.capture.ScriptedTransport { command -> if (command.ins() == 0x82) com.hddev.smartemu.capture.response(0x6983) else com.hddev.smartemu.capture.response(0x9000) }
        val runner = CaptureRunner(extractors = emptyList(), pack = ProbePack.tierC, acknowledgeSacrificialCard = true)
        val aborted = assertIs<CaptureResult.Aborted>(runner.run(chip))
        assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason)
        assertEquals(1, chip.sent.count { it.ins() == 0x82 }, "nothing is sent after the first blocked answer")
    }

    @Test
    fun `status words the emulator gives tier b on a PACE profile`() {
        // Not an assertion on the emulator's choices: the table is printed so that a change in them shows in the test log
        val pace = ChipProfiles.all.first { documentFor(it).chipConfiguration().accessControl.supportsPace }
        val transport = transportFor(pace)
        val record = assertIs<CaptureResult.Complete>(CaptureRunner(pack = ProbePack.tierB).run(transport)).record
        println("tier-b status words, profile ${pace.id}:")
        record.probes.forEach { probe -> println("  ${probe.probeId}: " + probe.steps.joinToString { "${it.stepId}=%04X".format(it.statusWord ?: 0) }) }
    }
}
