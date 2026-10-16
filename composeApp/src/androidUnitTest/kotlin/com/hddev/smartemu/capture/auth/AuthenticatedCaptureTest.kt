package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.AbortReason
import com.hddev.smartemu.capture.AccessCredentials
import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AccessStop
import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CredentialField
import com.hddev.smartemu.capture.CredentialProblemKind
import com.hddev.smartemu.capture.EmulatorTransport
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.ScriptedTransport
import com.hddev.smartemu.capture.StatusClass
import com.hddev.smartemu.capture.TagLossTransport
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.chipConfiguration
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authenticated capture against the emulated chip: the safety rules (nothing sent for bad credentials, one attempt per
 * suite, one for a CAN, stop at a refused key or a blocked status word), the abort reasons, and resuming.
 */
class AuthenticatedCaptureTest {
    private lateinit var chip: EmulatedChip

    @Before fun setUp() { chip = EmulatedChip() }
    @After fun tearDown() { chip.close() }

    private val pace2 = ChipProfiles.byId("de-passport-2023") // CAM then GM, no BAC
    private val bacOnly = ChipProfiles.byId("us-passport-2021")

    private fun finished(result: AuthenticatedCaptureResult): CaptureResult = assertIs<AuthenticatedCaptureResult.Finished>(result).result

    @Test
    fun `credentials that fail validation are rejected and no command is sent`() {
        val transport = ScriptedTransport { error("nothing should be sent") }
        val bad = AccessCredentials.Mrz.of("L898902C3", "741332", "340415", '5', '0', com.hddev.smartemu.capture.MrzCheck.checkDigit("340415"))

        val result = AuthenticatedCapture().run(transport, bad)

        val problems = assertIs<AuthenticatedCaptureResult.Rejected>(result).problems
        assertEquals(
            setOf(CredentialField.DOCUMENT_NUMBER to CredentialProblemKind.CHECK_DIGIT_MISMATCH, CredentialField.DATE_OF_BIRTH to CredentialProblemKind.NOT_A_DATE),
            problems.map { it.credentialField to it.kind }.toSet()
        )
        assertTrue(transport.sent.isEmpty(), "no APDU for invalid credentials")
        assertTrue(bad.isClosed, "and they are erased")
        val badCan = AccessCredentials.Can.of("12")
        assertIs<AuthenticatedCaptureResult.Rejected>(AuthenticatedCapture().run(transport, badCan))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `credentials are erased when the run ends, and kept if the caller asks`() {
        val data = chip.documentFor(bacOnly)
        val mrz = chip.mrz(data)
        AuthenticatedCapture().run(chip.transport(data), mrz)
        assertTrue(mrz.isClosed)

        val kept = chip.mrz(data)
        AuthenticatedCapture().run(chip.transport(data), kept, closeCredentials = false)
        assertFalse(kept.isClosed)
        kept.close()
    }

    @Test
    fun `an MRZ tries every suite once and then BAC, and reads in the first session that opens`() {
        val data = chip.documentFor(pace2)
        val watching = WatchingTransport(chip.transport(data))
        val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
        val auth = assertNotNull(record.authenticated)

        // CAM first, as the chip lists them; then GM; then BAC, which this PACE-only chip doesn't do
        assertEquals(listOf(PaceMappingType.CAM, PaceMappingType.GM, null), auth.access.map { it.mapping })
        assertEquals(listOf(true, true, false), auth.access.map { it.succeeded })
        assertEquals(AccessMethod.BAC, auth.access.last().method)
        assertEquals(StatusClass.NOT_SUPPORTED, auth.access.last().statusClass)
        assertEquals(0, auth.readUnder)
        assertNull(auth.accessStopped)
        // One MSE:Set AT per suite, and one more for Chip Authentication
        assertEquals(3, watching.count(WatchingTransport.INS_MSE))
        assertTrue(CaptureFingerprint.isConsistent(record))
        assertEquals(emptyList(), RedactionLint.check(CaptureJson.encode(record)))
    }

    @Test
    fun `a refused MRZ stops after the first suite with no BAC and no second suite`() {
        val data = chip.documentFor(pace2)
        val wrong = mrzWithDigits(data.passportNumber, data.mrzDateOfBirth(), "341231")
        val watching = WatchingTransport(chip.transport(data))

        val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, wrong))).record
        val auth = assertNotNull(record.authenticated)

        assertEquals(1, auth.access.size)
        assertEquals(StatusClass.KEY_REJECTED, auth.access.single().statusClass)
        assertEquals(0x6300, auth.access.single().statusWord)
        assertEquals(AccessStop.KEY_REJECTED, auth.accessStopped)
        assertNull(auth.readUnder)
        assertNull(auth.sod)
        assertEquals(1, watching.count(WatchingTransport.INS_MSE), "one PACE attempt, not one per suite")
    }

    @Test
    fun `a refused MRZ on a BAC chip sends one wrong cryptogram`() {
        val data = chip.documentFor(bacOnly)
        val wrong = mrzWithDigits(data.passportNumber, data.mrzDateOfBirth(), "341231")
        val watching = WatchingTransport(chip.transport(data))

        val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, wrong))).record
        val auth = assertNotNull(record.authenticated)

        assertEquals(listOf(AccessMethod.BAC), auth.access.map { it.method })
        // The emulated US chip refuses a wrong key with its profile's 6985; others say 6300. Either one ends the run
        assertTrue(auth.access.single().statusClass in setOf(StatusClass.KEY_REJECTED, StatusClass.CONDITIONS_NOT_SATISFIED))
        assertNotNull(auth.accessStopped)
        assertFalse(auth.access.single().succeeded)
        // The only EXTERNAL AUTHENTICATE the chip saw is that one attempt: neither the bac-commands extractor's nor tier-a's
        // out-of-sequence ones were sent before it, so a wrong key is the first thing its BAC counter sees
        assertEquals(1, watching.count(WatchingTransport.INS_EXTERNAL_AUTHENTICATE))
        assertTrue(record.probes.none { it.probeId == "external-authenticate-out-of-sequence" })
        assertNull(record.bacCommands?.externalAuthenticate)
    }

    @Test
    fun `a suite the chip doesn't support is skipped and the next is tried`() {
        val data = chip.documentFor(pace2)
        val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
            // The first MSE:Set AT is answered "referenced data not found", as a chip without that suite would
            if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_MSE && firstMse) { firstMse = false; statusOnly(0x6A80) } else response
        }
        firstMse = true

        val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
        val auth = assertNotNull(record.authenticated)

        assertEquals(listOf(false, true), auth.access.take(2).map { it.succeeded })
        assertEquals(StatusClass.NOT_SUPPORTED, auth.access[0].statusClass)
        assertEquals(1, auth.readUnder, "read in the first session that opened")
        assertNull(auth.accessStopped)
    }

    private var firstMse = true

    @Test
    fun `a counter status word after a suite stops everything`() {
        for ((sw, stop) in listOf(0x63C1 to AccessStop.KEY_REJECTED, 0x6985 to AccessStop.CONDITIONS_NOT_SATISFIED)) {
            val data = chip.documentFor(pace2)
            val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
                if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_MSE) statusOnly(sw) else response
            }
            val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
            val auth = assertNotNull(record.authenticated)
            assertEquals(1, auth.access.size, "%04X".format(sw))
            assertEquals(stop, auth.accessStopped, "%04X".format(sw))
            assertEquals(1, watching.count(WatchingTransport.INS_MSE), "%04X".format(sw))
        }
    }

    @Test
    fun `a blocked status word ends the run and keeps the attempt`() {
        for (sw in listOf(0x63C0, 0x6983, 0x6984)) {
            val data = chip.documentFor(pace2)
            val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
                if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_MSE) statusOnly(sw) else response
            }
            val aborted = assertIs<CaptureResult.Aborted>(finished(AuthenticatedCapture().run(watching, chip.mrz(data))))
            assertEquals(AbortReason.BLOCKED_STATUS_WORD, aborted.reason, "%04X".format(sw))
            val auth = assertNotNull(aborted.record.authenticated)
            assertEquals(sw, auth.access.single().statusWord)
            assertEquals(AccessStop.BLOCKED, auth.accessStopped)
            // Nothing is sent after the blocked answer
            assertEquals(WatchingTransport.INS_MSE, watching.commands.last()[1].toInt() and 0xFF, "%04X".format(sw))
        }
    }

    @Test
    fun `a CAN is confirmed once, then tried once on one suite, and a failure ends it`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        var asked = 0
        val watching = WatchingTransport(chip.transport(data))

        // A wrong CAN: the confirmation is asked once, and the first suite is the only attempt
        val wrong = AccessCredentials.Can.of("271828")
        val record = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(watching, wrong, confirmCanAttempt = { asked++; true }))
        ).record
        val auth = assertNotNull(record.authenticated)
        assertEquals(1, asked)
        assertEquals(1, auth.access.size)
        assertEquals(AccessStop.CAN_SINGLE_ATTEMPT_FAILED, auth.accessStopped)
        assertEquals(1, watching.count(WatchingTransport.INS_MSE))
        assertEquals(com.hddev.smartemu.capture.CredentialKind.CAN, auth.credential)
    }

    @Test
    fun `a CAN that is right opens every suite and reads the chip`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        var asked = 0
        val record = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(chip.transport(data), AccessCredentials.Can.of("314159"), confirmCanAttempt = { asked++; true }))
        ).record
        val auth = assertNotNull(record.authenticated)
        assertEquals(1, asked)
        assertEquals(listOf(true, true), auth.access.map { it.succeeded })
        assertNotNull(auth.sod)
        assertTrue(auth.access.none { it.method == AccessMethod.BAC }, "a CAN never does BAC")
    }

    @Test
    fun `a declined CAN confirmation sends nothing with the CAN`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        val watching = WatchingTransport(chip.transport(data))

        val aborted = assertIs<CaptureResult.Aborted>(
            finished(AuthenticatedCapture().run(watching, AccessCredentials.Can.of("314159"), confirmCanAttempt = { false }))
        )

        assertEquals(AbortReason.USER_CANCELLED, aborted.reason)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        assertNull(aborted.record.authenticated)
    }

    @Test
    fun `the default confirmation declines, so a CAN is never tried by accident`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        val watching = WatchingTransport(chip.transport(data))
        val aborted = assertIs<CaptureResult.Aborted>(finished(AuthenticatedCapture().run(watching, AccessCredentials.Can.of("314159"))))
        assertEquals(AbortReason.USER_CANCELLED, aborted.reason)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
    }

    @Test
    fun `a CAN on a chip with no PACE is not asked about and not tried`() {
        val data = chip.documentFor(bacOnly)
        var asked = 0
        val watching = WatchingTransport(chip.transport(data))
        val record = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(watching, AccessCredentials.Can.of("314159"), confirmCanAttempt = { asked++; true }))
        ).record
        assertEquals(0, asked)
        assertEquals(AccessStop.CAN_NEEDS_PACE, record.authenticated?.accessStopped)
        assertEquals(emptyList(), record.authenticated?.access)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
    }

    @Test
    fun `the MRZ is not asked for confirmation`() {
        val data = chip.documentFor(pace2)
        var asked = 0
        AuthenticatedCapture().run(chip.transport(data), chip.mrz(data), confirmCanAttempt = { asked++; true })
        assertEquals(0, asked)
    }

    @Test
    fun `a lost tag aborts without an authenticated part, and a resume reads on`() {
        val data = chip.documentFor(pace2)
        val phase1 = WatchingTransport(chip.transport(data)).also { CaptureRunner().run(it) }.commands.size

        val lossy = TagLossTransport(chip.transport(data), loseAt = phase1 + 6)
        val aborted = assertIs<CaptureResult.Aborted>(finished(AuthenticatedCapture().run(lossy, chip.mrz(data))))
        assertEquals(AbortReason.TAG_LOST, aborted.reason)
        assertNull(aborted.record.authenticated)

        val watching = WatchingTransport(chip.transport(data))
        val resumed = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(watching, chip.mrz(data), resumeFrom = aborted.draft))
        ).record
        assertNotNull(resumed.authenticated?.sod)
        assertEquals(aborted.record.captureId, resumed.captureId, "the same capture")
        assertTrue(watching.commands.size < phase1 + 80, "phase 1 isn't sent again")
    }

    @Test
    fun `progress runs over the unauthenticated steps and then the authenticated stages, ending at done`() {
        val data = chip.documentFor(bacOnly)
        val seen = mutableListOf<Triple<String, Int, Int>>()
        val capture = AuthenticatedCapture()
        capture.run(chip.transport(data), chip.mrz(data), progress = { name, done, total -> seen += Triple(name, done, total) })
        assertTrue(seen.all { it.third == capture.totalSteps })
        assertEquals(Triple("done", capture.totalSteps, capture.totalSteps), seen.last())
        assertEquals(1, seen.count { it.first == "done" })
        assertTrue(seen.any { it.first == AuthenticatedSteps.EF_SOD })
        assertEquals(seen.map { it.second }, seen.map { it.second }.sorted(), "never goes backwards")
    }

    @Test
    fun `the default extractors stay unauthenticated`() {
        val data = chip.documentFor(pace2)
        val record = assertIs<CaptureResult.Complete>(CaptureRunner().run(chip.transport(data))).record
        assertNull(record.authenticated)
        assertFalse(CaptureJson.encode(record).contains("\"authenticated\""), "a record without one is the text it always was")
    }

    @Test
    fun `the digest covers the authenticated facts and the generation key does not, so a holder cannot split a generation`() {
        val data = chip.documentFor(ChipProfiles.byId("nl-passport"))
        val first = chip.record(data)
        val second = chip.record(data)
        assertEquals(first.fingerprint, second.fingerprint, "random challenges and capture ids don't change it")
        assertEquals(first.observationDigest, second.observationDigest)
        assertTrue(first.captureId != second.captureId)

        // The authenticated read is per holder and per phone (a key typed, a sod read): it is in the digest, never in the key
        val withoutAuth = first.copy(authenticated = null)
        assertEquals(CaptureFingerprint.of(first), CaptureFingerprint.of(withoutAuth), "no authenticated field is in the generation key")
        assertTrue(CaptureFingerprint.digestOf(withoutAuth) != first.observationDigest, "the authenticated part is in the digest")
        val otherAlgorithm = first.copy(authenticated = first.authenticated!!.copy(sod = first.authenticated!!.sod!!.copy(digestAlgorithm = "SHA-512")))
        assertEquals(CaptureFingerprint.of(otherAlgorithm), first.fingerprint)
        assertTrue(CaptureFingerprint.digestOf(otherAlgorithm) != first.observationDigest)
        // Holder-dependent structure: a different DG2 size bucket, signer validity or access outcome leaves the key where it was
        val holder = first.copy(
            authenticated = first.authenticated!!.copy(
                dataGroups = first.authenticated!!.dataGroups.map { if (it.number == 2) it.copy(size = com.hddev.smartemu.capture.SizeRange(3840, 4095)) else it },
                sod = first.authenticated!!.sod!!.copy(signer = first.authenticated!!.sod!!.signer!!.copy(validityMonths = 999)),
                access = first.authenticated!!.access.map { it.copy(succeeded = false, statusClass = com.hddev.smartemu.capture.StatusClass.KEY_REJECTED) }
            )
        )
        assertEquals(first.fingerprint, CaptureFingerprint.of(holder))
        assertTrue(CaptureFingerprint.digestOf(holder) != first.observationDigest)
        // A record with no authenticated part is consistent when sealed
        val plain = assertIs<CaptureResult.Complete>(CaptureRunner().run(chip.transport(data))).record
        assertTrue(CaptureFingerprint.isConsistent(plain))
        assertEquals(first.fingerprint, plain.fingerprint, "the same chip, with or without the authenticated read")
    }

    @Test
    fun `different kinds of chip give different digests, and a generation key where their public files differ`() {
        val records = ChipProfiles.all.map { chip.record(chip.documentFor(it)) }
        assertTrue(records.map { it.observationDigest }.toSet().size > 10, "only ${records.map { it.observationDigest }.toSet().size} distinct digests")
        assertTrue(records.map { it.fingerprint }.toSet().size > 1, "every kind of chip has one generation key")
    }

    // ---- the MRZ attempt policy ----

    @Test
    fun `an MRZ stops at any failure once authentication began, not only at a refused key`() {
        for (sw in listOf(0x6300, 0x6982, 0x6A80, 0x6988, 0x6985, 0x6700, 0x6F00)) {
            val data = chip.documentFor(pace2)
            // The first GENERAL AUTHENTICATE, which is after MSE:Set AT, is refused
            val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
                if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_GENERAL_AUTHENTICATE) statusOnly(sw) else response
            }
            val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
            val auth = assertNotNull(record.authenticated)
            assertEquals(1, auth.access.size, "%04X: one attempt, no second suite".format(sw))
            assertEquals(sw, auth.access.single().statusWord)
            assertNotNull(auth.accessStopped, "%04X".format(sw))
            assertEquals(1, watching.count(WatchingTransport.INS_MSE), "%04X".format(sw))
            assertEquals(0, watching.count(WatchingTransport.INS_EXTERNAL_AUTHENTICATE), "%04X: and no BAC after it".format(sw))
            assertNull(auth.readUnder)
        }
    }

    @Test
    fun `before any key-dependent command only a plain not-supported lets the next suite be tried`() {
        // At MSE:Set AT: "the chip doesn't do that" goes on to the next suite, and then BAC
        for (sw in listOf(0x6A80, 0x6A81, 0x6D00)) {
            val data = chip.documentFor(pace2)
            val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
                if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_MSE) statusOnly(sw) else response
            }
            val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
            val auth = assertNotNull(record.authenticated)
            assertEquals(2, watching.count(WatchingTransport.INS_MSE), "%04X: both suites were tried".format(sw))
            assertTrue(auth.access.size >= 2 && auth.access.take(2).all { it.statusClass == StatusClass.NOT_SUPPORTED })
        }
        // Anything else at MSE:Set AT ends it
        for (sw in listOf(0x6982, 0x6988, 0x6985, 0x6700, 0x6300)) {
            val data = chip.documentFor(pace2)
            val watching = WatchingTransport(chip.transport(data)) { _, command, response ->
                if ((command[1].toInt() and 0xFF) == WatchingTransport.INS_MSE) statusOnly(sw) else response
            }
            val record = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(watching, chip.mrz(data)))).record
            val auth = assertNotNull(record.authenticated)
            assertEquals(1, auth.access.size, "%04X".format(sw))
            assertEquals(1, watching.count(WatchingTransport.INS_MSE), "%04X".format(sw))
            assertNotNull(auth.accessStopped, "%04X".format(sw))
        }
    }

    // ---- the CAN attempt, a lost tag and a resume ----

    private fun phase1Commands(data: com.hddev.smartemu.data.PassportData): Int =
        WatchingTransport(chip.transport(data)).also { CaptureRunner().forAuthenticatedRead().run(it) }.commands.size

    @Test
    fun `a CAN attempt is noted in the draft before PACE starts, and is not repeated after a lost tag unless confirmed again`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        val lossy = TagLossTransport(chip.transport(data), loseAt = phase1Commands(data) + 6)
        val aborted = assertIs<CaptureResult.Aborted>(
            finished(AuthenticatedCapture().run(lossy, AccessCredentials.Can.of("314159"), confirmCanAttempt = { true }))
        )
        assertEquals(AbortReason.TAG_LOST, aborted.reason)
        assertTrue(aborted.draftState.canAttemptStarted, "the attempt began before the tag went")
        assertTrue(aborted.draft.state.canAttemptStarted)

        // A resume with the ordinary confirmation is not enough: the first-try question is not asked, and the default says no
        val declined = WatchingTransport(chip.transport(data))
        val stopped = assertIs<CaptureResult.Aborted>(
            finished(
                AuthenticatedCapture().run(
                    declined, AccessCredentials.Can.of("314159"),
                    confirmCanAttempt = { error("the ordinary question must not be the one asked") }, resumeFrom = aborted.draft
                )
            )
        )
        assertEquals(AbortReason.USER_CANCELLED, stopped.reason)
        assertEquals(0, declined.count(WatchingTransport.INS_MSE), "nothing was sent with the CAN")
        assertTrue(stopped.draftState.canAttemptStarted, "and it stays noted")

        // With the explicit confirmation it is tried, once
        var asked = 0
        val allowed = WatchingTransport(chip.transport(data))
        val record = assertIs<CaptureResult.Complete>(
            finished(
                AuthenticatedCapture().run(
                    allowed, AccessCredentials.Can.of("314159"),
                    confirmCanAttempt = { error("the ordinary question must not be the one asked") },
                    resumeFrom = aborted.draft, confirmInterruptedCanAttempt = { asked++; true }
                )
            )
        ).record
        assertEquals(1, asked)
        assertNotNull(record.authenticated?.sod)
        assertEquals(aborted.record.captureId, record.captureId)
    }

    @Test
    fun `a CAN not yet begun when the tag is lost is asked about the ordinary way on resume`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        val lossy = TagLossTransport(chip.transport(data), loseAt = phase1Commands(data) - 2)
        val aborted = assertIs<CaptureResult.Aborted>(finished(AuthenticatedCapture().run(lossy, AccessCredentials.Can.of("314159"), confirmCanAttempt = { true })))
        assertEquals(false, aborted.draftState.canAttemptStarted)
        var asked = 0
        val record = assertIs<CaptureResult.Complete>(
            finished(
                AuthenticatedCapture().run(
                    chip.transport(data), AccessCredentials.Can.of("314159"),
                    confirmCanAttempt = { asked++; true }, resumeFrom = aborted.draft,
                    confirmInterruptedCanAttempt = { error("not interrupted") }
                )
            )
        ).record
        assertEquals(1, asked)
        assertNotNull(record.authenticated?.sod)
    }

    @Test
    fun `a CAN attempt is noted in the draft state even when it succeeds`() {
        val data = chip.documentFor(pace2).copy(can = "314159")
        val result = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(chip.transport(data), AccessCredentials.Can.of("314159"), confirmCanAttempt = { true }))
        )
        assertTrue(result.draftState.canAttemptStarted)
    }

    @Test
    fun `an MRZ read never marks a CAN attempt`() {
        val data = chip.documentFor(pace2)
        val result = assertIs<CaptureResult.Complete>(finished(AuthenticatedCapture().run(chip.transport(data), chip.mrz(data))))
        assertEquals(false, result.draftState.canAttemptStarted)
    }

    @Test
    fun `a different document after a lost tag is read afresh and nothing is tried with the key`() {
        val first = chip.documentFor(pace2)
        val other = chip.documentFor(bacOnly)
        val lossy = TagLossTransport(chip.transport(first), loseAt = 40)
        val aborted = assertIs<CaptureResult.Aborted>(finished(AuthenticatedCapture().run(lossy, chip.mrz(first))))

        val watching = WatchingTransport(chip.transport(other))
        val result = assertIs<CaptureResult.Complete>(
            finished(AuthenticatedCapture().run(watching, chip.mrz(first), resumeFrom = aborted.draft))
        )
        assertEquals(com.hddev.smartemu.capture.ResumeOutcome.DIFFERENT_DOCUMENT, result.resumeOutcome)
        assertTrue(result.record.captureId != aborted.record.captureId)
        assertNull(result.record.authenticated)
        assertEquals(0, watching.count(WatchingTransport.INS_MSE))
        assertEquals(0, watching.count(WatchingTransport.INS_EXTERNAL_AUTHENTICATE))
    }

    // ---- Integrated Mapping and the registered provider ----

    @Test
    fun `PACE-IM with a CAN is skipped, not tried, when the registered BC is not the app's Bouncy Castle`() {
        val platform = object : java.security.Provider("BC", 1.0, "a stand-in for the platform's stripped copy") {}
        val before = java.security.Security.getProviders().toList()
        val previous = java.security.Security.getProvider("BC")
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(platform)
        try {
            val profile = ChipProfiles.byId("fr-id-card-2024")
            val data = chip.documentFor(profile).copy(can = "314159")
            val watching = WatchingTransport(chip.transport(data))
            val record = assertIs<CaptureResult.Complete>(
                finished(AuthenticatedCapture().run(watching, AccessCredentials.Can.of("314159"), confirmCanAttempt = { true }))
            ).record
            val auth = assertNotNull(record.authenticated)
            val im = auth.access.filter { it.mapping == PaceMappingType.IM }
            assertTrue(im.isNotEmpty(), "the chip offers PACE-IM, and it is recorded")
            im.forEach {
                assertEquals(false, it.succeeded)
                assertNull(it.statusWord, "not attempted: no command, so no status word")
            }
            assertTrue(auth.access.any { it.mapping == PaceMappingType.GM && it.succeeded }, "the GM suite still opened the chip")
            assertNull(auth.accessStopped, "a skipped suite is not a failed attempt")
            // The registered provider is as it was
            assertTrue(java.security.Security.getProvider("BC") === platform)
        } finally {
            java.security.Security.removeProvider("BC")
            previous?.let { java.security.Security.addProvider(it) }
        }
        assertEquals(before.map { it.name }.toSet(), java.security.Security.getProviders().map { it.name }.toSet())
    }

    @Test
    fun `the provider order is restored after an Integrated Mapping attempt, with any BC registered`() {
        val data = chip.documentFor(ChipProfiles.byId("fr-id-card-2024"))
        val before = java.security.Security.getProviders().map { it.name to it.javaClass.name }
        chip.record(data)
        assertEquals(before, java.security.Security.getProviders().map { it.name to it.javaClass.name })
    }
}
