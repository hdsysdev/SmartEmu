package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.AuthenticationResult
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.ComObservation
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.DataGroupObservation
import com.hddev.smartemu.capture.SodObservation
import com.hddev.smartemu.capture.GoldenCapture
import com.hddev.smartemu.capture.ProbeResult
import com.hddev.smartemu.capture.Redaction
import com.hddev.smartemu.capture.StepResult
import com.hddev.smartemu.capture.TimingSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileMergerTest {
    private val base = GoldenCapture.record

    private fun capture(
        n: Int,
        model: String = "Reader $n",
        steps: List<ProbeResult> = base.probes,
        fingerprint: String = base.fingerprint
    ): CaptureRecord = base.copy(
        captureId = "00000000-0000-4000-8000-00000000000$n",
        reader = readerNamed(model),
        probes = steps,
        fingerprint = fingerprint
    )

    private fun withStep(probeId: String, stepId: String, change: (StepResult) -> StepResult): List<ProbeResult> =
        base.probes.map { probe ->
            if (probe.probeId != probeId) probe else probe.copy(steps = probe.steps.map { if (it.stepId == stepId) change(it) else it })
        }

    @Test
    fun `captures that agree merge into one profile with their evidence`() {
        val profile = ProfileMerger.merge(listOf(capture(1), capture(2)), id = "synthetic-test")
        assertEquals(2, profile.sampleCount)
        assertEquals(listOf(capture(1).captureId, capture(2).captureId), profile.captureIds)
        assertEquals(base.fingerprint, profile.generation)
        assertEquals(base.files.cardAccess!!.contentsHex, profile.observations.cardAccessHex)
        assertEquals(0x6985, profile.observations.bacExternalAuthenticate)
        assertEquals(0x9000, profile.observations.statusWords["read-binary-max-length/le-df"])
        assertEquals(Redaction.probeSizeRange(22), profile.observations.responseSizes["read-binary-max-length/le-df"])
        assertTrue(profile.disagreements.isEmpty())
        assertFalse(profile.needsReview)
        assertFalse(profile.needsMoreCaptures)
        // Each field says which captures it was read from, and none is MEASURED before a hold-out
        assertEquals(profile.captureIds, profile.field("file:cardAccess:contents")!!.captureIds)
        assertTrue(profile.measuredFields.isEmpty())
    }

    @Test
    fun `a single capture is held for review and needs more captures`() {
        val profile = ProfileMerger.merge(listOf(capture(1)))
        assertEquals(1, profile.sampleCount)
        assertTrue(profile.needsReview)
        assertTrue(profile.needsMoreCaptures)
    }

    @Test
    fun `the same capture seen twice counts once`() {
        val profile = ProfileMerger.merge(listOf(capture(1), capture(1)))
        assertEquals(1, profile.sampleCount)
    }

    @Test
    fun `a field the captures disagree on is flagged, not averaged, and left out`() {
        val other = capture(2, model = "Other phone", steps = withStep("read-binary-max-length", "le-df") { it.copy(statusWord = 0x6700) })
        val profile = ProfileMerger.merge(listOf(capture(1, model = "Pixel"), other))
        val disagreement = assertNotNull(profile.disagreements.singleOrNull { it.field == "step:read-binary-max-length/le-df:statusWord" })
        assertEquals(setOf("9000", "6700"), disagreement.values.map { it.value }.toSet())
        assertTrue(disagreement.acrossDevices, "each value came from its own device model")
        assertNull(profile.observations.statusWords["read-binary-max-length/le-df"])
        assertNull(profile.field("step:read-binary-max-length/le-df:statusWord"))
        // What they agree on is still merged
        assertEquals(0x6985, profile.observations.statusWords["external-authenticate-out-of-sequence/lc-40-le-28"])
    }

    @Test
    fun `a disagreement inside one device model is not marked across devices`() {
        val other = capture(2, model = "Pixel", steps = withStep("read-binary-max-length", "le-df") { it.copy(statusWord = 0x6700) })
        val profile = ProfileMerger.merge(listOf(capture(1, model = "Pixel"), other))
        assertFalse(profile.disagreements.single { it.field.endsWith("le-df:statusWord") }.acrossDevices)
    }

    @Test
    fun `a step whose repeats disagree within a capture is flagged and merged from nowhere`() {
        val unstable = capture(2, steps = withStep("read-binary-max-length", "le-df") { it.copy(repeatsAgree = false) })
        val profile = ProfileMerger.merge(listOf(capture(1), unstable))
        assertNull(profile.observations.statusWords["read-binary-max-length/le-df"])
        assertNull(profile.observations.responseSizes["read-binary-max-length/le-df"])
        assertTrue(profile.disagreements.any { it.field == "step:read-binary-max-length/le-df:statusWord" && it.reason.contains("within one capture") })
    }

    @Test
    fun `timing is aggregated as min median max and is not compared`() {
        val slow = capture(2, steps = withStep("read-binary-max-length", "le-df") { it.copy(timing = TimingSummary(20, 25, 40, 3)) })
        val profile = ProfileMerger.merge(listOf(capture(1), slow))
        val timing = profile.observations.timing.getValue("read-binary-max-length/le-df")
        assertEquals(12, timing.minMs)
        assertEquals(25, timing.medianMs)
        assertEquals(40, timing.maxMs)
        assertEquals(6, timing.samples)
        assertEquals(FieldFidelity.NOT_COMPARABLE, profile.field("step:read-binary-max-length/le-df:timing")!!.fidelity)
    }

    @Test
    fun `the reader's controller limits are never merged into a profile`() {
        val profile = ProfileMerger.merge(listOf(capture(1), capture(2)))
        val text = MeasuredProfileJson.encode(profile)
        assertFalse(text.contains("controllerMaxTransceiveLength"))
        assertFalse(text.contains("controllerExtendedLengthSupported"))
        assertFalse(text.contains("65279"))
    }

    @Test
    fun `different link observations are flagged and informational`() {
        val other = capture(2).copy(link = base.link.copy(historicalBytes = "8100"))
        val profile = ProfileMerger.merge(listOf(capture(1), other))
        assertNull(profile.observations.link)
        assertTrue(profile.disagreements.any { it.field == "link" })
        // The link fields are listed as not emulatable, never claimed
        assertTrue(profile.fields.none { it.key.startsWith("link:") })
    }

    @Test
    fun `link fields are listed as not comparable with the reason`() {
        val profile = ProfileMerger.merge(listOf(capture(1), capture(2)))
        val link = profile.field("link:technology")!!
        assertEquals(FieldFidelity.NOT_COMPARABLE, link.fidelity)
        assertEquals(NotComparableReasons.LINK, link.reason)
        assertTrue(profile.notEmulatable.any { it.contains("NFC-B") })
    }

    @Test
    fun `mergeAll groups captures by fingerprint into generations`() {
        val a1 = capture(1)
        val a2 = capture(2)
        val b = capture(3, fingerprint = "fedcba9876543210")
        val profiles = ProfileMerger.mergeAll(listOf(a1, b, a2))
        assertEquals(2, profiles.size)
        assertEquals(base.fingerprint, profiles[0].generation)
        assertEquals(2, profiles[0].sampleCount)
        assertEquals("fedcba9876543210", profiles[1].generation)
        assertEquals("generation-fedcba9876543210", profiles[1].id)
    }

    @Test
    fun `captures of different generations merged by hand flag the generation`() {
        val profile = ProfileMerger.merge(listOf(capture(1), capture(2, fingerprint = "fedcba9876543210")))
        assertTrue(profile.disagreements.any { it.field == "generation" })
    }

    @Test
    fun `the largest read is the largest probed Le answered in full, and unknown for a short file`() {
        // GoldenCapture's EF.CardAccess is 22 bytes: no probed Le fits, so nothing was cut short
        assertNull(ProfileMerger.merge(listOf(capture(1), capture(2))).observations.maxReadLength)
        val longFile = "31820128" + "30".repeat(296)
        val steps = listOf(
            ProbeResult(
                "read-binary-max-length", 1, com.hddev.smartemu.capture.ProbeTier.A, listOf(
                    StepResult("le-df", 0x9000, Redaction.probeSizeRange(223)),
                    StepResult("le-e0", 0x9000, Redaction.probeSizeRange(224)),
                    StepResult("le-ff", 0x9000, Redaction.probeSizeRange(223)),
                    StepResult("le-00", 0x9000, Redaction.probeSizeRange(223))
                )
            )
        )
        fun long(n: Int) = capture(n, steps = steps).let { it.copy(files = it.files.copy(cardAccess = it.files.cardAccess!!.copy(contentsHex = longFile))) }
        val profile = ProfileMerger.merge(listOf(long(1), long(2)))
        // 255 came back as 223 bytes, so the limit is the 224 of the step before it
        assertEquals(224, profile.observations.maxReadLength)
    }

    private fun authenticated(digest: String = "SHA-256", aa: Boolean = true) = AuthenticatedObservation(
        credential = CredentialKind.MRZ,
        com = ComObservation(ldsVersion = "0107", unicodeVersion = "040000", dataGroups = listOf(1, 2, 14)),
        dataGroups = listOf(DataGroupObservation(number = 3, inCom = false, inSod = false, readStatusWord = 0x6982)),
        sod = SodObservation(digestAlgorithm = digest, signatureAlgorithm = "SHA256withECDSA", hashedDataGroups = listOf(1, 2, 14)),
        activeAuthentication = AuthenticationResult(aa, "ECDSA-SHA256")
    )

    @Test
    fun `what authenticated captures agree on is merged as informational structure, and what they differ on is flagged`() {
        val a = capture(1).copy(authenticated = authenticated())
        val b = capture(2).copy(authenticated = authenticated(digest = "SHA-384"))
        val profile = ProfileMerger.merge(listOf(a, b))
        val merged = profile.observations.authenticated
        assertEquals("0107", merged["auth:com.ldsVersion"])
        assertEquals("1,2,14", merged["auth:com.dataGroups"])
        assertEquals("inCom=false,inSod=false,select=null,read=6982,size=null", merged["auth:dg3"])
        assertNull(merged["auth:sod.digestAlgorithm"], "the digests differ, so it is flagged and left out")
        assertTrue(profile.disagreements.any { it.field == "auth:sod.digestAlgorithm" })
        // Behind authentication: listed as not comparable, never claimable
        val field = profile.field("auth:com.ldsVersion")!!
        assertEquals(FieldFidelity.NOT_COMPARABLE, field.fidelity)
        assertEquals(NotComparableReasons.AUTHENTICATED, field.reason)
        assertTrue(profile.measuredFields.isEmpty())
        assertTrue(profile.checkableFields.none { it.key.startsWith("auth:") })
        // No access credential or key is part of what is flattened
        assertTrue(merged.keys.all { it.startsWith("auth:") })
    }
}
