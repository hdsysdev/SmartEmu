package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.GoldenCapture
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ScriptedTransport
import com.hddev.smartemu.capture.response
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FidelityDiffTest {
    private val today = LocalDate(2026, 10, 1)

    @AfterTest
    fun tearDown() = MeasuredProfileRegistry.clear()

    /** A chip that has the made-up chip's EF.CardAccess in the master file and the SELECT of EF.CardSecurity it lacks. */
    private fun profileWithCardSecurity(): MeasuredProfile =
        ProfileMerger.merge(
            listOf(captureOf(FakeChip(hasCardSecurity = true, device = readerNamed("A"))), captureOf(FakeChip(hasCardSecurity = true, device = readerNamed("B")))),
            id = "synthetic-card-security"
        )

    @Test
    fun `an emulator that can't do what the profile says is a deviation, shown in the table`() {
        val profile = profileWithCardSecurity()
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), today = today) }
        val deviation = report.deviations.single()
        assertEquals("step:select-fid/card-security-from-master-file:statusWord", deviation.key)
        assertEquals("9000", deviation.expected)
        assertEquals("6A82", deviation.actual)
        assertFalse(report.isClean)
        val table = report.toTable()
        assertTrue(table.contains("DEVIATION"))
        assertTrue(table.lines().first().contains("synthetic-card-security on tier-a v${ProbePack.tierA.version}"))
        // Deviations come first, under the header
        assertTrue(table.lines()[3].contains("card-security-from-master-file"))
    }

    @Test
    fun `an allowed deviation is honoured and listed in the report`() {
        val profile = profileWithCardSecurity()
        val allowed = AllowedDeviations(
            deviations = listOf(
                AllowedDeviation(
                    "step:select-fid/card-security-from-master-file:*", "The emulator has no EF.CardSecurity without PACE-CAM", "TODO", expires = "2027-01-31"
                )
            )
        )
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), allowed = allowed, today = today) }
        assertTrue(report.isClean, report.toTable())
        assertEquals(1, report.entries.count { it.outcome == DiffOutcome.ALLOWED })
        assertEquals(allowed.deviations, report.allowedUsed)
        assertTrue(report.toTable().contains("Allowed deviations used:"))
        assertTrue(report.toTable().contains("The emulator has no EF.CardSecurity without PACE-CAM (owner TODO, expires 2027-01-31)"))
    }

    @Test
    fun `an expired allowed deviation allows nothing and is listed as expired`() {
        val profile = profileWithCardSecurity()
        val allowed = AllowedDeviations(
            deviations = listOf(AllowedDeviation("step:select-fid/card-security-from-master-file:statusWord", "temporary", "TODO", expires = "2026-09-30"))
        )
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), allowed = allowed, today = today) }
        assertFalse(report.isClean)
        assertEquals(allowed.deviations, report.expiredAllowances)
        assertTrue(report.toTable().contains("Expired allowed deviations"))
    }

    @Test
    fun `an allowed deviation for another profile does not apply`() {
        val allowed = AllowedDeviations(deviations = listOf(AllowedDeviation("*", "x", "TODO", profile = "other-profile")))
        assertNull(allowed.find("synthetic-card-security", "step:a/b:statusWord", today))
        assertTrue(allowed.find("other-profile", "step:a/b:statusWord", today) != null)
    }

    @Test
    fun `the glob matches a run of characters and nothing else`() {
        val d = AllowedDeviation("step:select-fid/*:statusWord", "r", "o")
        assertTrue(d.appliesTo("p", "step:select-fid/unknown-fid:statusWord"))
        assertFalse(d.appliesTo("p", "step:select-fid/unknown-fid:responseSize"))
        assertFalse(d.appliesTo("p", "x:step:select-fid/unknown-fid:statusWord"))
        assertTrue(AllowedDeviation("a:b", "r", "o").appliesTo("p", "a:b"))
        assertFalse(AllowedDeviation("a:b", "r", "o").appliesTo("p", "a:bc"))
    }

    @Test
    fun `the reviewed allowed-deviation file decodes strictly and every entry has a reason and an owner`() {
        val file = File("../docs/fidelity-allowed-deviations.json")
        val allowed = AllowedDeviations.decode(file.readText())
        allowed.deviations.forEach {
            assertTrue(it.reason.isNotBlank() && it.owner.isNotBlank(), it.field)
        }
        assertFailsWith<SerializationException> { AllowedDeviations.decode("""{"schemaVersion":1,"deviations":[{"field":"a","reason":"b","owner":"c","extra":1}]}""") }
        assertFailsWith<SerializationException> { AllowedDeviations.decode("""{"schemaVersion":2,"deviations":[]}""") }
    }

    @Test
    fun `random fields are compared by structure, timing and link are not comparable with a reason`() {
        val profile = profileWithCardSecurity()
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), today = today) }
        val challenge = report.entries.single { it.key == "step:get-challenge-lengths/le-08:responseSize" }
        assertEquals(FieldFidelity.STRUCTURAL, challenge.fidelity)
        assertEquals(DiffOutcome.MATCH, challenge.outcome)
        val timing = report.entries.single { it.key == "step:*:timing" }
        assertEquals(DiffOutcome.NOT_COMPARABLE, timing.outcome)
        assertEquals(NotComparableReasons.TIMING, timing.note)
        val link = report.entries.single { it.key == "link:*" }
        assertEquals(NotComparableReasons.LINK, link.note)
        assertEquals(FieldFidelity.COMPARED, report.entries.single { it.key == "file:cardAccess:contents" }.fidelity)
    }

    @Test
    fun `a run that does not complete compares nothing and says why`() {
        val profile = ProfileMerger.merge(listOf(GoldenCapture.record))
        var sent = 0
        val dropping = ScriptedTransport {
            if (++sent > 3) throw com.hddev.smartemu.capture.TagLostException() else response(0x9000)
        }
        val report = FidelityDiff.run(profile, dropping, today = today)
        assertFalse(report.isClean)
        assertTrue(report.entries.isEmpty())
        assertTrue(report.toTable().contains("RUN FAILED: The run was aborted: TAG_LOST"))
    }

    @Test
    fun `any probe pack is accepted`() {
        val profile = ProfileMerger.merge(listOf(captureOf(FakeChip()), captureOf(FakeChip(device = readerNamed("B")))), id = "synthetic-fake")
        val report = MeasuredEmulator.with(profile) { FidelityDiff.run(profile, it.transport(), pack = ProbePack.tierB, today = today) }
        // Tier B steps with a preamble aren't recognised by the emulator, but the profile holds no tier B results either:
        // what is compared is only what the profile has
        assertEquals(listOf(ProbePack.tierB.ref), report.probePacks)
    }
}
