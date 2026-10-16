package com.hddev.smartemu.capture

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The round trip: a profile's emulated chip, read by [CaptureRunner], must give a record from which the profile's
 * observable facts come back, and which claims nothing the capture can't see. This tests the capture engine with no
 * real document.
 */
class RoundTripHarnessTest {
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

    private lateinit var hceService: PassportHceService

    @Before
    fun setUp() {
        hceService = PassportHceService()
        hceService.onCreate()
    }

    @After
    fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        hceService.onDestroy()
    }

    private fun documentFor(profile: ChipProfile): PassportData =
        PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today)
            ?: base.withChipProfile(profile)

    private fun capture(data: PassportData): CaptureRecord {
        PassportHceService.setSharedPassportData(data)
        return assertIs<CaptureResult.Complete>(CaptureRunner().run(EmulatorTransport(hceService))).record
    }

    private fun expectedSuites(data: PassportData): List<ObservedPaceSuite> =
        data.chipConfiguration().paceSuites.map {
            ObservedPaceSuite(
                mapping = when (it.mapping) {
                    com.hddev.smartemu.data.PaceMapping.GENERIC -> "GM"
                    com.hddev.smartemu.data.PaceMapping.INTEGRATED -> "IM"
                    com.hddev.smartemu.data.PaceMapping.CHIP_AUTHENTICATION -> "CAM"
                },
                cipher = it.spec.cipher.displayName,
                parameterId = it.spec.parameterId
            )
        }

    @Test
    fun `every profile's observable facts come back from its capture`() {
        val failures = mutableListOf<String>()
        ChipProfiles.all.forEach { profile ->
            val data = documentFor(profile)
            val record = capture(data)
            val facts = ProfileRecovery.recover(record)
            val config = data.chipConfiguration()

            if (facts.paceSuites != expectedSuites(data)) failures += "${profile.id}: PACE suites ${facts.paceSuites} != ${expectedSuites(data)}"
            if (facts.answersBacChallenge != config.accessControl.supportsBac) {
                failures += "${profile.id}: BAC answered ${facts.answersBacChallenge}, profile says ${config.accessControl.supportsBac}"
            }
            val expectedOutOfSequence = if (config.accessControl.supportsBac) config.errorResponses.outOfSequence else null
            if (facts.outOfSequence != expectedOutOfSequence) {
                failures += "${profile.id}: out-of-sequence ${facts.outOfSequence?.toString(16)} != ${expectedOutOfSequence?.toString(16)}"
            }
            if (config.accessControl.supportsPace != (record.files.cardAccess?.contentsHex != null)) {
                failures += "${profile.id}: EF.CardAccess present ${record.files.cardAccess?.contentsHex != null}, profile PACE ${config.accessControl.supportsPace}"
            }
            if (record.application?.selectStatusWord != 0x9000) failures += "${profile.id}: the eMRTD application didn't select"
            if (record.aborted != null) failures += "${profile.id}: aborted ${record.aborted}"
            if (!CaptureFingerprint.isConsistent(record)) failures += "${profile.id}: fingerprint inconsistent"
            val lint = RedactionLint.check(CaptureJson.encode(record))
            if (lint.isNotEmpty()) failures += "${profile.id}: lint $lint"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the profiles cover PACE only, BAC only, CAM and other mappings`() {
        val configs = ChipProfiles.all.map { documentFor(it).chipConfiguration() }
        assertTrue(configs.any { it.accessControl.supportsPace && !it.accessControl.supportsBac }, "PACE only")
        assertTrue(configs.any { it.accessControl.supportsBac && !it.accessControl.supportsPace }, "BAC only")
        assertTrue(configs.any { c -> c.paceSuites.any { it.mapping == com.hddev.smartemu.data.PaceMapping.CHIP_AUTHENTICATION } }, "CAM")
        assertTrue(configs.any { it.paceSuites.size > 1 }, "other mappings")
    }

    @Test
    fun `a chip without BAC shows no out-of-sequence status word, though its profile has one`() {
        val profile = ChipProfiles.all.first { documentFor(it).chipConfiguration().accessControl.let { a -> a.supportsPace && !a.supportsBac } }
        val data = documentFor(profile)
        val record = capture(data)
        assertNull(ProfileRecovery.recover(record).outOfSequence)
        // What the emulator answers instead is that the command is unknown, which isn't the profile's fact
        assertEquals(0x6D00, record.bacCommands?.externalAuthenticate)
    }

    @Test
    fun `the aspects a capture can't see are listed and have no field to be claimed in`() {
        val observable = ProfileRecovery.observability.filterValues { it.first != ProfileRecovery.Visibility.NOT_OBSERVABLE }.keys
        assertEquals(ProfileAspect.entries.toSet(), observable + ProfileRecovery.unobservable)
        assertEquals(
            setOf(
                ProfileAspect.ACTIVE_AUTHENTICATION, ProfileAspect.EXTENDED_ACCESS_CONTROL, ProfileAspect.DATA_GROUPS,
                ProfileAspect.SIGNATURE, ProfileAspect.MRZ
            ),
            ProfileRecovery.unobservable
        )
        println("Unobservable from an unauthenticated capture:")
        ProfileRecovery.observability.forEach { (aspect, why) -> println("  ${aspect.displayName}: ${why.first} - ${why.second}") }
        // The recovered facts hold only PACE suites, BAC and the out-of-sequence word
        assertEquals(
            setOf("paceSuites", "answersBacChallenge", "outOfSequence"),
            RecoveredFacts::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        )
    }

    @Test
    fun `the probe pack runs end to end and its results are keyed by probe and pack version`() {
        val record = capture(documentFor(ChipProfiles.all.first { documentFor(it).chipConfiguration().accessControl.supportsPace }))
        assertEquals(ProbePack.tierA.probes.map { it.id }, record.probes.map { it.probeId })
        record.probes.forEach { assertEquals(ProbePack.tierA.version, it.packVersion) }
        val steps = record.probes.flatMap { it.steps }
        assertEquals(ProbePack.tierA.stepCount, steps.size)
        steps.forEach { assertTrue(it.statusWord != null, "${it.stepId} got no answer") }
        assertEquals(0x9000, steps.first { it.stepId == "card-access-p1-02-p2-0c" }.statusWord)
    }

    @Test
    fun `the same chip gives the same fingerprint on every capture`() {
        val data = documentFor(ChipProfiles.all.first { documentFor(it).chipConfiguration().accessControl.supportsBac })
        val first = capture(data)
        val second = capture(data)
        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(first.captureId != second.captureId)
    }

    @Test
    fun `different kinds of chip give different fingerprints`() {
        val fingerprints = ChipProfiles.all.map { capture(documentFor(it)).fingerprint }
        assertTrue(fingerprints.toSet().size > 3, "only ${fingerprints.toSet().size} distinct fingerprints")
    }
}
