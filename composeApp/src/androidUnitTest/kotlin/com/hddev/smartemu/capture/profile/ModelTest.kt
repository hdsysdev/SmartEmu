package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.GoldenCapture
import com.hddev.smartemu.capture.NotEmulatable
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipConfiguration
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.ErrorResponses
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.Provenance
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The measured profile's place in the chip model: provenance, the badge, the limits panel, the registry. */
class ModelTest {
    private val golden = GoldenMeasuredProfile.profile
    private val germanPassport = ChipProfiles.byId("de-passport-2017")

    @AfterTest
    fun tearDown() = MeasuredProfileRegistry.clear()

    @Test
    fun `MEASURED is the strongest provenance and carries the contract`() {
        assertEquals(Provenance.MEASURED, Provenance.entries.first())
        assertEquals(
            "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures.",
            Provenance.MEASURED.description
        )
        assertEquals(Provenance.MEASURED.description, FidelityContract.STATEMENT)
    }

    @Test
    fun `a profile without measured data is exactly as it was`() {
        assertNull(germanPassport.measured)
        assertEquals(Provenance.SPECIFICATION, germanPassport.provenanceOf(ProfileAspect.SIGNATURE))
        val config = PassportData(passportNumber = "X").withChipProfile(germanPassport).chipConfiguration()
        assertNull(config.measured)
        assertNull(config.maxReadLength)
        assertEquals(germanPassport.errorResponses, config.errorResponses)
        assertTrue(config.errorResponses.byProbe.isEmpty())
    }

    @Test
    fun `ErrorResponses keeps outOfSequence as its default and adds a map by probe`() {
        assertEquals(0x6985, ErrorResponses().outOfSequence)
        assertEquals(0x6300, ErrorResponses(outOfSequence = 0x6300).outOfSequence)
        assertEquals(0x6700, ErrorResponses(byProbe = mapOf("a/b" to 0x6700)).forProbe("a/b"))
        assertNull(ErrorResponses().forProbe("a/b"))
    }

    @Test
    fun `an aspect is MEASURED only when all its checkable fields are, per field not per profile`() {
        val withMeasured = germanPassport.withMeasured(golden)
        // Only two ACCESS_CONTROL fields earned MEASURED, and others in the aspect did not
        assertEquals(Provenance.SPECIFICATION, withMeasured.provenanceOf(ProfileAspect.SIGNATURE))
        assertEquals(germanPassport.provenanceOf(ProfileAspect.ACCESS_CONTROL), withMeasured.provenanceOf(ProfileAspect.ACCESS_CONTROL))

        val allAccessControl = golden.checkableFields.filter { it.aspect == "ACCESS_CONTROL" }.map { it.key }.toSet()
        val promoted = golden.withMeasuredFields(allAccessControl, MeasuredEvidence(golden.captureIds, 3, golden.probePacks, "c"))
        assertEquals(Provenance.MEASURED, germanPassport.withMeasured(promoted).provenanceOf(ProfileAspect.ACCESS_CONTROL))
        // PACE cryptography is never measured by an unauthenticated probe
        assertEquals(germanPassport.provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY), germanPassport.withMeasured(promoted).provenanceOf(ProfileAspect.PACE_CRYPTOGRAPHY))
    }

    @Test
    fun `fields that can't be checked are never marked MEASURED`() {
        val all = golden.fields.map { it.key }.toSet()
        val marked = golden.withMeasuredFields(all, MeasuredEvidence(golden.captureIds, 3, golden.probePacks, "c"))
        assertTrue(marked.fields.filter { it.fidelity == FieldFidelity.NOT_COMPARABLE }.none { it.measured })
    }

    @Test
    fun `the measured statuses overlay the curated ones in the configuration, but only for fields that earned MEASURED`() {
        val oosKey = "external-authenticate-out-of-sequence/lc-40-le-28"
        val curated = germanPassport.copyWith(errorResponses = ErrorResponses(byProbe = mapOf("x/y" to 0x6A80, "read-binary-order/no-file-selected" to 0x1111)))
        // Seen in the captures, never confirmed by the hold-out: nothing is installed, the emulator keeps its own answers
        val unproven = PassportData(passportNumber = "X").withChipProfile(curated.withMeasured(golden)).let { data ->
            MeasuredProfileRegistry.install(listOf(curated.withMeasured(golden)))
            data.chipConfiguration()
        }
        assertEquals(0x6A80, unproven.errorResponses.forProbe("x/y"))
        assertEquals(0x1111, unproven.errorResponses.forProbe("read-binary-order/no-file-selected"), "no MEASURED field, so the curated word stands")
        assertNull(unproven.errorResponses.forProbe(oosKey))
        assertEquals(0x6985, unproven.errorResponses.outOfSequence)
        assertSame(golden, unproven.measured)
        MeasuredProfileRegistry.clear()

        // The same profile with the fields marked MEASURED installs exactly those
        val proven = golden.withMeasuredFields(
            setOf("step:$oosKey:statusWord", "bac:externalAuthenticate"), MeasuredEvidence(golden.captureIds, 3, golden.probePacks, "c")
        )
        val config = PassportData(passportNumber = "X").withChipProfile(curated.withMeasured(proven)).let { data ->
            MeasuredProfileRegistry.install(listOf(curated.withMeasured(proven)))
            data.chipConfiguration()
        }
        assertEquals(0x6A80, config.errorResponses.forProbe("x/y"))
        assertEquals(golden.observations.bacExternalAuthenticate, config.errorResponses.outOfSequence)
        assertEquals(golden.observations.statusWords[oosKey], config.errorResponses.forProbe(oosKey))
        // Only that step: the other measured status words of the profile are not installed
        assertEquals(setOf("x/y", "read-binary-order/no-file-selected", oosKey), config.errorResponses.byProbe.keys)
        assertSame(proven, config.measured)
    }

    @Test
    fun `ErrorResponses withMeasured installs nothing for a profile with no MEASURED field`() {
        assertEquals(ErrorResponses(), ErrorResponses().withMeasured(golden))
        assertEquals(ErrorResponses(), ErrorResponses().withMeasured(golden.forEvaluation().withoutMeasured()))
        assertTrue(ErrorResponses().withMeasured(golden.forEvaluation()).byProbe.size == golden.observations.statusWords.size)
    }

    @Test
    fun `the registry gives a document the profile with measured data`() {
        MeasuredProfileRegistry.link(listOf(golden.copy(chipProfileId = "de-passport-2017")))
        val data = PassportData(passportNumber = "X").withChipProfile(germanPassport)
        assertEquals("de-passport-2017", data.chipProfile.id)
        assertNotNull(data.chipProfile.measured)
        assertNull(germanPassport.measured, "the curated profile itself isn't changed")
        MeasuredProfileRegistry.clear()
        assertNull(data.chipProfile.measured)
    }

    @Test
    fun `a measured profile with no curated link becomes a profile of its own`() {
        MeasuredProfileRegistry.link(listOf(golden))
        val profile = ChipProfiles.byId("measured-golden-profile")
        assertEquals("measured-golden-profile", profile.id)
        // The golden capture shows EF.CardAccess and GET CHALLENGE answered
        assertEquals(AccessControl.BAC_AND_PACE, profile.accessControl)
        assertNotSame(germanPassport, profile)
        assertEquals("generic", ChipProfiles.byId("unknown-id").id)
    }

    @Test
    fun `the badge says Measured, n only for a profile with a MEASURED field`() {
        assertEquals("Not measured", FidelityBadge.of(germanPassport).label)
        assertEquals(FidelityBadge.Level.NOT_MEASURED, FidelityBadge.of(germanPassport).level)
        val badge = FidelityBadge.of(germanPassport.withMeasured(golden))
        assertEquals("Measured, n=3", badge.label)
        assertEquals(FidelityBadge.Level.MEASURED, badge.level)
        assertEquals(2, badge.measuredFields)
        assertTrue(badge.detail.startsWith("Indistinguishable from the real chip at APDU level on probe pack v1, on fields marked compared, from 3 captures."))

        val single = ProfileMerger.merge(listOf(GoldenCapture.record))
        val singleBadge = FidelityBadge.of(single)
        assertEquals(FidelityBadge.Level.CAPTURED_NOT_VERIFIED, singleBadge.level)
        assertTrue(singleBadge.label.contains("needs more captures"))
        assertFalse(singleBadge.label.startsWith("Measured"))

        val unverified = ProfileMerger.merge(listOf(GoldenCapture.record, GoldenCapture.record.copy(captureId = "44444444-4444-4444-8444-444444444444")))
        assertEquals("Captured, n=2: not verified", FidelityBadge.of(unverified).label)
    }

    @Test
    fun `the emulation limits list the not-emulatable properties first`() {
        val plain = EmulationLimits.of(germanPassport)
        assertEquals(NotEmulatable.properties.map { "Not emulatable on Android HCE: $it" }, plain.lines.take(NotEmulatable.properties.size))
        assertTrue(plain.items.filter { it.kind != EmulationLimit.Kind.NOT_EMULATABLE }.all { it.kind == EmulationLimit.Kind.PROFILE_NOTE })

        val limits = EmulationLimits.of(germanPassport.withMeasured(golden))
        val kinds = limits.items.map { it.kind }.toSet()
        assertTrue(EmulationLimit.Kind.NOT_COMPARABLE in kinds)
        assertTrue(EmulationLimit.Kind.NOT_VERIFIED in kinds)
        assertTrue(EmulationLimit.Kind.DISAGREEMENT in kinds)
        assertTrue(EmulationLimit.Kind.NOT_PROBED in kinds)
        assertTrue(limits.lines.any { it.contains(NotComparableReasons.TIMING) })
        assertTrue(limits.lines.any { it.contains(NotComparableReasons.LINK) })
    }

    @Test
    fun `a config built without measured data compiles against the old constructor shape`() {
        // The measured field is last and defaulted, so existing construction sites keep working
        val config: ChipConfiguration = PassportData(passportNumber = "X", issuingCountry = "DEU", dateOfBirth = LocalDate(1990, 1, 1)).chipConfiguration()
        assertNull(config.measured)
    }
}
