package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The hold-out rule: build from 1..N-1, check on N; a replay that matches only its own capture earns nothing. */
class HoldOutTest {
    @AfterTest
    fun tearDown() = MeasuredProfileRegistry.clear()

    private fun fakeCapture(model: String, chip: (com.hddev.smartemu.capture.ReaderDevice) -> FakeChip = { FakeChip(device = it) }) =
        captureOf(chip(readerNamed(model)))

    /** Runs the evaluation with one emulator per profile it asks for, closing them after. */
    private fun evaluate(captures: List<CaptureRecord>): HoldOutResult {
        val open = mutableListOf<MeasuredEmulator>()
        try {
            return HoldOutEvaluator.evaluate(captures, emulatorFor = { MeasuredEmulator(it).also(open::add).transport() }, id = "synthetic-fake")
        } finally {
            open.forEach { it.close() }
        }
    }

    @Test
    fun `a profile built from and checked against the same capture earns nothing`() {
        val capture = fakeCapture("Phone A")
        val result = evaluate(listOf(capture, capture))
        assertTrue(result.measuredKeys.isEmpty())
        assertTrue(result.profile.measuredFields.isEmpty())
        assertTrue(result.profile.needsMoreCaptures)
        assertEquals(null, result.heldOutCaptureId)
        assertNotNull(result.award.note)
    }

    @Test
    fun `the award itself refuses a held-out capture the profile was built from`() {
        val capture = fakeCapture("Phone A")
        val built = ProfileMerger.merge(listOf(capture))
        val award = HoldOut.award(built, capture, capture)
        assertTrue(award.awarded.isEmpty())
        assertTrue(award.note!!.contains("one the profile was built from"))
        // Even a perfect replay of a capture the profile contains earns nothing
        val two = ProfileMerger.merge(listOf(capture, fakeCapture("Phone B")))
        assertTrue(HoldOut.award(two, capture, capture).awarded.isEmpty())
    }

    @Test
    fun `a single capture earns nothing and needs more captures`() {
        val result = evaluate(listOf(fakeCapture("Phone A")))
        assertTrue(result.profile.measuredFields.isEmpty())
        assertTrue(result.profile.needsMoreCaptures && result.profile.needsReview)
        assertEquals(FidelityBadge.Level.CAPTURED_NOT_VERIFIED, FidelityBadge.of(result.profile).level)
        assertTrue(result.award.note!!.startsWith("Needs more captures"))
    }

    @Test
    fun `two captures earn MEASURED per field, on a capture the replay was not built from`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B")
        val result = evaluate(listOf(a, b))
        assertEquals(b.captureId, result.heldOutCaptureId)
        assertEquals(listOf(a.captureId), result.builtFromCaptureIds)
        assertTrue(result.award.rejected.isEmpty(), result.award.rejected.joinToString("\n"))
        assertTrue(result.replay!!.isClean, result.replay.toTable())
        val measured = result.profile.measuredFields
        assertTrue(measured.size > 100, "only ${measured.size} fields earned MEASURED")
        val cardAccess = result.profile.field("file:cardAccess:contents")!!
        assertTrue(cardAccess.measured)
        assertEquals(listOf(a.captureId, b.captureId), cardAccess.evidence!!.captureIds)
        assertEquals(2, cardAccess.evidence.sampleCount)
        assertEquals(b.captureId, cardAccess.evidence.heldOutCaptureId)
        assertEquals(result.profile.probePacks, cardAccess.evidence.probePacks)
        // Fields that can't be checked never earn it
        assertTrue(result.profile.fields.filter { it.fidelity == FieldFidelity.NOT_COMPARABLE }.none { it.measured })
        val badge = FidelityBadge.of(result.profile)
        assertEquals("Measured, n=2", badge.label)
    }

    @Test
    fun `a field the held-out capture differs on does not earn MEASURED, the rest do`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B")
        // The held-out capture is a real capture of the same generation in which one step answered differently: the
        // generation key doesn't hash probe answers, so recomputing it (as the builder does) leaves it as it was
        val differing = CaptureFingerprint.seal(
            b.copy(
                probes = b.probes.map { probe ->
                    probe.copy(steps = probe.steps.map { if (it.stepId == "le-04" && probe.probeId == "read-binary-wrong-length") it.copy(statusWord = 0x6C04) else it })
                }
            )
        )
        assertEquals(a.fingerprint, differing.fingerprint, "same generation")
        assertTrue(a.observationDigest != differing.observationDigest, "but not the same observations")
        val built = ProfileMerger.merge(listOf(a))
        val award = HoldOut.award(built, differing, replay = a)
        assertFalse("step:read-binary-wrong-length/le-04:statusWord" in award.awarded)
        assertTrue("step:read-binary-wrong-length/le-01:statusWord" in award.awarded)
        val rejected = award.rejected.single { it.key == "step:read-binary-wrong-length/le-04:statusWord" }
        assertEquals("6C04", rejected.expected)
        assertEquals("The profile had 9000", rejected.note)
    }

    @Test
    fun `a replay that does not reproduce the held-out capture earns nothing for that field`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B")
        val built = ProfileMerger.merge(listOf(a))
        // The profile predicts b; the replay (as if the emulator had ignored the profile) gives something else
        val replay = a.copy(files = a.files.copy(cardAccess = a.files.cardAccess!!.copy(contentsHex = "3100")))
        val award = HoldOut.award(built, b, replay)
        assertFalse("file:cardAccess:contents" in award.awarded)
        assertEquals("The replay did not reproduce it", award.rejected.single { it.key == "file:cardAccess:contents" }.note)
        assertTrue("bac:getChallenge" in award.awarded)
    }

    @Test
    fun `nothing is awarded across generations`() {
        val a = fakeCapture("Phone A")
        // Another EF.CardAccess is another kind of chip
        val other = fakeCapture("Phone B") { FakeChip(device = it, cardAccess = FakeChip.defaultCardAccess().copyOf().also { bytes -> bytes[bytes.size - 1] = 0x11 }) }
        assertTrue(a.fingerprint != other.fingerprint)
        val result = evaluate(listOf(a, other))
        assertTrue(result.award.awarded.isEmpty())
        assertTrue(result.award.note!!.contains("another generation"))
        assertTrue(result.profile.measuredFields.isEmpty())
    }

    @Test
    fun `apply marks nothing below two captures even if told to`() {
        val one = ProfileMerger.merge(listOf(fakeCapture("Phone A")))
        val marked = HoldOut.apply(one, HoldOutAward(setOf("file:cardAccess:contents"), emptyList()), "x")
        assertTrue(marked.measuredFields.isEmpty())
    }

    @Test
    fun `captures that answer the probes differently are still one generation`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B") { FakeChip(device = it, outOfSequenceStatusWord = 0x6985, maxRead = 223) }
        val noExtended = captureOf(FakeChip(device = readerNamed("Phone C", extended = false), extendedLength = false))
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.fingerprint, noExtended.fingerprint)
        assertTrue(a.observationDigest != b.observationDigest && a.observationDigest != noExtended.observationDigest)
        assertTrue(a.fingerprint.matches(Regex("[0-9A-F]{16}")))
    }

    @Test
    fun `two real captures of one generation that disagree so that field is not MEASURED and the others are`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B") { FakeChip(device = it, outOfSequenceStatusWord = 0x6985) }
        val result = evaluate(listOf(a, b))
        val key = "step:external-authenticate-out-of-sequence/lc-40-le-28:statusWord"
        assertEquals(b.captureId, result.heldOutCaptureId)
        assertFalse(key in result.measuredKeys, "the profile (built from a) said 6982, b said 6985")
        assertFalse("bac:externalAuthenticate" in result.measuredKeys)
        val rejected = result.award.rejected.single { it.key == key }
        assertEquals("6985", rejected.expected)
        assertEquals("The profile had 6982", rejected.note)
        assertFalse(result.profile.field(key)?.measured == true)
        // Everything the two captures agree on is earned
        assertTrue("file:cardAccess:contents" in result.measuredKeys)
        assertTrue("bac:getChallenge" in result.measuredKeys)
        assertTrue(result.profile.field("file:cardAccess:contents")!!.measured)
        // The full profile of both has the disagreement, and with it the error responses aspect is not MEASURED
        assertTrue(result.profile.disagreements.any { it.field == key })
        assertFalse(result.profile.isMeasured(com.hddev.smartemu.data.ProfileAspect.ERROR_RESPONSES))
    }

    @Test
    fun `when the built-from captures disagree with each other the field has no value to predict`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B") { FakeChip(device = it, outOfSequenceStatusWord = 0x6985) }
        val c = fakeCapture("Phone C")
        val result = evaluate(listOf(a, b, c))
        val key = "step:external-authenticate-out-of-sequence/lc-40-le-28:statusWord"
        assertEquals(c.captureId, result.heldOutCaptureId)
        assertFalse(key in result.measuredKeys)
        assertEquals("The profile had no value", result.award.rejected.single { it.key == key }.note)
        assertTrue("file:cardAccess:contents" in result.measuredKeys)
    }

    @Test
    fun `the held-out capture is from a reader the others did not use where there is one`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B")
        val a2 = fakeCapture("Phone A")
        assertEquals(b.captureId, HoldOutEvaluator.chooseHeldOut(listOf(a, b, a2)).captureId)
        assertEquals(a2.captureId, HoldOutEvaluator.chooseHeldOut(listOf(a, a2)).captureId, "same reader throughout the last")
        val twoPhones = evaluate(listOf(a, b))
        assertTrue(twoPhones.heldOutFromDifferentReader)
        assertTrue(twoPhones.profile.measuredFields.all { it.evidence!!.heldOutFromDifferentReader })
        val samePhone = evaluate(listOf(a, a2))
        assertFalse(samePhone.heldOutFromDifferentReader)
        assertTrue(samePhone.profile.measuredFields.isNotEmpty(), "preferred, not required")
        assertTrue(samePhone.profile.measuredFields.none { it.evidence!!.heldOutFromDifferentReader })
        assertTrue(samePhone.heldOutCaptureId != null && samePhone.heldOutCaptureId !in samePhone.builtFromCaptureIds)
    }

    @Test
    fun `withheld steps are classified, some the emulator's own rules already answer, some only the replay does`() {
        val result = evaluate(listOf(fakeCapture("Phone A"), fakeCapture("Phone B")))
        val award = result.award
        assertEquals(HoldOut.FOLDS, award.folds)
        assertTrue(award.matchesDefault.isNotEmpty(), "the emulator's generic rules get some steps right")
        assertTrue(award.needsReplay.isNotEmpty(), "the made-up chip differs from the emulator in some steps: only the replay answers them")
        assertTrue(award.matchesDefault.intersect(award.needsReplay).isEmpty())
        assertTrue((award.matchesDefault + award.needsReplay).all { it in award.awarded && it.startsWith("step:") || it == "bac:externalAuthenticate" })
        // The flag is on the profile's fields, only on fields that earned MEASURED
        val flagged = result.profile.fields.filter { it.matchesDefault }.map { it.key }.toSet()
        assertEquals(award.matchesDefault, flagged)
        assertTrue(result.profile.fields.filter { it.matchesDefault }.all { it.measured })
        // Classifying never decides: everything that was awarded is still MEASURED
        assertEquals(award.awarded, result.profile.measuredFields.map { it.key }.toSet())
        // The made-up chip's read limit of 255 and B1 are not what the emulator does by default
        assertTrue("step:read-binary-order/odd-ins-card-access:statusWord" in award.needsReplay)
    }

    @Test
    fun `the folds split the steps deterministically and every step is withheld in exactly one`() {
        val profile = ProfileMerger.merge(listOf(fakeCapture("Phone A")))
        val all = profile.observations.statusWords.keys
        val perFold = (0 until HoldOut.FOLDS).map { HoldOut.withheldIn(profile, it).first }
        assertEquals(all, perFold.flatten().toSet())
        assertEquals(all.size, perFold.sumOf { it.size }, "no step in two folds")
        assertTrue(perFold.all { it.isNotEmpty() }, "every fold withholds something")
        // Pinned: the hash is part of the contract, a change moves steps between folds
        assertEquals(listOf(1, 0, 1), listOf("a/b", "read-binary-max-length/le-ff", "read-binary-order/odd-ins-card-access").map(HoldOut::foldOf))
    }

    @Test
    fun `a withheld run decides nothing so the award is the same with or without it`() {
        val a = fakeCapture("Phone A")
        val b = fakeCapture("Phone B") { FakeChip(device = it, outOfSequenceStatusWord = 0x6985) }
        val built = ProfileMerger.merge(listOf(a))
        val with = HoldOut.award(built, b, a, withheld = listOf(WithheldReplay(setOf("bac:getChallenge"), a)))
        val without = HoldOut.award(built, b, a)
        assertEquals(without.awarded, with.awarded)
        assertEquals(without.rejected, with.rejected)
    }
}
