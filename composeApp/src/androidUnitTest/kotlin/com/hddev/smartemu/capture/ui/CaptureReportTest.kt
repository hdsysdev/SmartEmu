package com.hddev.smartemu.capture.ui

import com.hddev.smartemu.capture.AbortReason
import com.hddev.smartemu.capture.AccessAttempt
import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AccessStop
import com.hddev.smartemu.capture.ActiveAuthenticationKeyObservation
import com.hddev.smartemu.capture.AuthenticatedObservation
import com.hddev.smartemu.capture.AuthenticationResult
import com.hddev.smartemu.capture.ComObservation
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.CvcaObservation
import com.hddev.smartemu.capture.DataGroupObservation
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.RedactionManifest
import com.hddev.smartemu.capture.SecurityInfoKind
import com.hddev.smartemu.capture.SecurityInfoObservation
import com.hddev.smartemu.capture.SecurityInfosObservation
import com.hddev.smartemu.capture.SignerCertificateObservation
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.capture.SodObservation
import com.hddev.smartemu.capture.StatusClass
import com.hddev.smartemu.capture.auth.AuthenticatedSteps
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureSource
import com.hddev.smartemu.capture.FileObservation
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.capture.NotEmulatable
import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.ProbeResult
import com.hddev.smartemu.capture.ProbeTier
import com.hddev.smartemu.capture.ReaderDevice
import com.hddev.smartemu.capture.Redaction
import com.hddev.smartemu.capture.StepResult
import com.hddev.smartemu.capture.UnauthenticatedFiles
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureReportTest {

    /** EF.CardAccess as a chip writes it: PACE ECDH-GM AES-128 on P-256, and a PACE-CAM suite on brainpoolP256r1. */
    private val cardAccessHex = DERSet(
        arrayOf(
            DERSequence(
                arrayOf(ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.4.2.2"), ASN1Integer(2), ASN1Integer(12))
            ),
            DERSequence(
                arrayOf(ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.4.6.4"), ASN1Integer(2), ASN1Integer(13))
            ),
            DERSequence(arrayOf(ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.2.2"), ASN1Integer(1)))
        )
    ).encoded.joinToString("") { "%02X".format(it) }

    private fun record(
        cardAccess: FileObservation? = FileObservation(0x9000, cardAccessHex, Redaction.sizeRange(cardAccessHex.length / 2)),
        aborted: AbortReason? = null,
        uidRandom: Boolean? = null
    ) = CaptureRecord(
        captureId = "3f2a-9c1d",
        captureMonth = "2026-10",
        source = CaptureSource.ANDROID_READER,
        probePack = ProbePackRef("tierA", 1),
        reader = ReaderDevice("Pixel 9", "15 (API 35)", null, 65279, true),
        link = LinkObservation(NfcTechnology.NFC_B, "0102AB", uidRandom),
        files = UnauthenticatedFiles(cardAccess = cardAccess, atrInfo = FileObservation(0x6A82)),
        probes = listOf(
            ProbeResult(
                "select-variants", 1, ProbeTier.A,
                listOf(
                    StepResult("select-emrtd", 0x9000),
                    StepResult("get-challenge-before-auth", 0x6982),
                    StepResult("read-binary-odd-ins", null)
                )
            )
        ),
        aborted = aborted,
        fingerprint = "ab12cd34"
    )

    @Test
    fun saysCountryAndDocumentTypeWereNotRead() {
        val report = CaptureReport.render(record())
        assertContains(report, "Country: Not read (no access key used)")
        assertContains(report, "Document type: Not read (no access key used)")
    }

    @Test
    fun listsTheProtocolsEfCardAccessOffers() {
        val report = CaptureReport.render(record())
        assertContains(report, "PACE: ECDH, Generic Mapping, AES-128, NIST P-256 (parameter ID 12)")
        assertContains(report, "PACE: ECDH, Chip Authentication Mapping (PACE-CAM), AES-256, brainpoolP256r1 (parameter ID 13)")
        assertContains(report, "Terminal Authentication entries: 1")
    }

    @Test
    fun explainsAMissingEfCardAccess() {
        val report = CaptureReport.render(record(cardAccess = FileObservation(0x6A82)))
        assertContains(report, "didn't offer EF.CardAccess")
        assertContains(report, "6A82 (file not found)")
    }

    @Test
    fun survivesUnparseableEfCardAccess() {
        val report = CaptureReport.render(record(cardAccess = FileObservation(0x9000, "00FF", Redaction.sizeRange(2))))
        assertContains(report, "couldn't be parsed")
    }

    @Test
    fun tabulatesTheStatusWordsOfTheProbes() {
        val report = CaptureReport.render(record())
        assertContains(report, "select-emrtd")
        assertContains(report, "9000 (OK)")
        assertContains(report, "6982 (security status not satisfied)")
        assertContains(report, "no answer")
    }

    @Test
    fun reportsTheFingerprintAndThatNoProfilesWereCompared() {
        val report = CaptureReport.render(record())
        assertContains(report, "Fingerprint: ab12cd34")
        assertContains(report, "Compared with profiles: not yet")
    }

    @Test
    fun usesTheMatchSummaryWhenThereIsOne() {
        val match = MatchSummary("Dutch passport 2021", 3, "Matches profile Dutch passport 2021, n=3, no disagreements")
        assertContains(CaptureReport.render(record(), match), "Matches profile Dutch passport 2021, n=3")
    }

    @Test
    fun statesTheLimitsInTheFidelityContractWording() {
        val report = CaptureReport.render(record())
        assertContains(report, CaptureReport.FIDELITY_CONTRACT)
        NotEmulatable.properties.forEach { assertContains(report, it) }
    }

    @Test
    fun labelsThePhonesLimitsAsThePhones() {
        val report = CaptureReport.render(record())
        assertContains(report, "NOT THE DOCUMENT'S")
        assertContains(report, "the phone's NFC controller accepts: 65279")
    }

    @Test
    fun reportsAnUnconfirmedRandomUidWithoutAUid() {
        assertContains(CaptureReport.render(record(uidRandom = null)), "UID random: unconfirmed")
        assertContains(CaptureReport.render(record(uidRandom = false)), "UID random: no")
    }

    @Test
    fun saysWhenTheRunStoppedEarly() {
        val report = CaptureReport.render(record(aborted = AbortReason.BLOCKED_STATUS_WORD))
        assertContains(report, "stopped early (the document reported it was locked)")
        assertFalse(CaptureReport.render(record()).contains("stopped early"))
    }

    @Test
    fun listsWhatWasLeftOut() {
        val report = CaptureReport.render(record())
        assertTrue(record().redactions.isNotEmpty())
        record().redactions.forEach { assertContains(report, it.item) }
    }

    // ---- Phase 2: the authenticated part ----

    private fun authenticated(
        access: List<AccessAttempt> = listOf(
            AccessAttempt(AccessMethod.PACE, "0.4.0.127.0.7.2.2.4.2.2", PaceMappingType.GM, "AES-128", 12, succeeded = true, statusClass = StatusClass.OK),
            AccessAttempt(AccessMethod.BAC, succeeded = false, statusWord = 0x6A80, statusClass = StatusClass.NOT_SUPPORTED)
        ),
        stopped: AccessStop? = null,
        readUnder: Int? = 0,
        credential: CredentialKind = CredentialKind.MRZ
    ) = AuthenticatedObservation(
        credential = credential,
        access = access,
        accessStopped = stopped,
        readUnder = readUnder,
        com = ComObservation("0108", "040000", listOf(1, 2, 3, 14, 15), SizeRange(0, 255)),
        dataGroups = listOf(
            DataGroupObservation(1, inCom = true, inSod = true, selectStatusWord = 0x9000, readStatusWord = 0x9000, size = SizeRange(0, 255)),
            DataGroupObservation(2, inCom = true, inSod = true, selectStatusWord = 0x9000, readStatusWord = 0x9000, size = SizeRange(15360, 15615)),
            DataGroupObservation(3, inCom = true, inSod = true, selectStatusWord = 0x9000, readStatusWord = 0x6982)
        ),
        sod = SodObservation(
            size = SizeRange(1792, 2047), digestAlgorithm = "SHA-256", signatureAlgorithm = "SHA256withECDSA", ldsVersion = "0108",
            hashedDataGroups = listOf(1, 2, 3), certificateCount = 1,
            signer = SignerCertificateObservation("SHA256withECDSA", "EC", 256, "brainpoolP256r1", "DE", 36)
        ),
        dg14 = SecurityInfosObservation(
            SizeRange(256, 511),
            listOf(SecurityInfoObservation(SecurityInfoKind.CHIP_AUTHENTICATION, "0.4.0.127.0.7.2.2.3.2.4", version = 1, parameterId = 13)),
            paceCamPresent = false
        ),
        dg15 = ActiveAuthenticationKeyObservation(SizeRange(0, 255), "EC", 256, "brainpoolP256r1"),
        cvca = CvcaObservation(SizeRange(0, 255), "DE", hasAlternateReference = false),
        activeAuthentication = AuthenticationResult(true, "ECDSA-SHA256"),
        chipAuthentication = AuthenticationResult(false, "ECDH-AES-128", 0x6A80)
    )

    private fun authenticatedRecord(observation: AuthenticatedObservation = authenticated()): CaptureRecord =
        record().copy(authenticated = observation, redactions = RedactionManifest.entries + RedactionManifest.authenticatedEntries)

    @Test
    fun `a record without a key says so, and an authenticated one has its own section`() {
        assertContains(CaptureReport.render(record()), "Not done (no access key used).")
        val report = CaptureReport.render(authenticatedRecord())
        assertContains(report, "READ WITH THE DOCUMENT'S KEY")
        assertFalse(report.contains("Not done (no access key used)"))
    }

    @Test
    fun `the report states that the key and the personal data were used in memory only and are not saved`() {
        val report = CaptureReport.render(authenticatedRecord())
        assertContains(report, "used in memory only")
        assertContains(report, "NOT saved")
        assertContains(report, "Access key: MRZ, used in memory only, never saved")
        assertContains(report, "Holder details: used in memory only, never saved")
    }

    @Test
    fun `the report lists algorithms, data groups with rounded sizes, curves, and the authentication results`() {
        val report = CaptureReport.render(authenticatedRecord())
        assertContains(report, "Data groups listed: DG1, DG2, DG3, DG14, DG15")
        assertContains(report, "DG2: in EF.COM: yes; in EF.SOD: yes; select 9000 (OK); read 9000 (OK); size 15360-15615 bytes")
        assertContains(report, "Digest algorithm: SHA-256")
        assertContains(report, "Signature algorithm: SHA256withECDSA")
        assertContains(report, "Signer: key: EC, 256 bits, brainpoolP256r1")
        assertContains(report, "Signer: issuing country: DE")
        assertContains(report, "Signer: validity: 36 months")
        assertContains(report, "Active Authentication: succeeded, ECDSA-SHA256")
        assertContains(report, "Chip Authentication: did not succeed, ECDH-AES-128, answered 6A80 (incorrect data)")
        assertContains(report, "Attempt 1: PACE, Generic Mapping, AES-128, parameter ID 12: opened; the files were read in this one")
        assertContains(report, "Attempt 2: BAC: not opened, answered 6A80")
        assertContains(report, "Country: DE (country of the signing authority; the holder's is not saved)")
    }

    @Test
    fun `the DG3 refusal status word is reported`() {
        val report = CaptureReport.render(authenticatedRecord())
        assertContains(report, "DG3 (fingerprints) without Terminal Authentication: refused, 6982 (security status not satisfied)")
        assertContains(report, "DG4 (iris) without Terminal Authentication: not on this chip")
    }

    @Test
    fun `the report lists the extra items an authenticated read leaves out`() {
        val report = CaptureReport.render(authenticatedRecord())
        RedactionManifest.authenticatedEntries.forEach { assertContains(report, it.item) }
    }

    @Test
    fun `a refused key is reported in the words the screen uses`() {
        val refused = authenticated(
            access = listOf(AccessAttempt(AccessMethod.PACE, "0.4.0.127.0.7.2.2.4.2.2", PaceMappingType.GM, "AES-128", 12, succeeded = false, statusWord = 0x6300, statusClass = StatusClass.KEY_REJECTED)),
            stopped = AccessStop.KEY_REJECTED,
            readUnder = null
        ).copy(com = null, dataGroups = emptyList(), sod = null, dg14 = null, dg15 = null, cvca = null)
        val report = CaptureReport.render(authenticatedRecord(refused))
        assertContains(report, "Result: The document did not accept this key")
        assertContains(report, "answered 6300 (authentication failed)")
        assertContains(report, "Stopped because: the key was refused")
    }

    @Test
    fun `a CAN read names the CAN and never a value`() {
        val report = CaptureReport.render(authenticatedRecord(authenticated(credential = CredentialKind.CAN)))
        assertContains(report, "Access key: CAN, used in memory only, never saved")
        assertContains(report, "CAN (card access number)")
    }

    @Test
    fun `the access outcome follows the observation`() {
        fun outcome(stop: AccessStop?, opened: Boolean = false) = AuthenticatedSummary.outcome(
            authenticated(
                access = if (opened) authenticated().access else emptyList(),
                stopped = stop,
                readUnder = if (opened) 0 else null
            )
        )
        assertEquals(AccessOutcome.OPENED, outcome(null, opened = true))
        assertEquals(AccessOutcome.KEY_REFUSED, outcome(AccessStop.KEY_REJECTED))
        assertEquals(AccessOutcome.BLOCKED, outcome(AccessStop.BLOCKED))
        assertEquals(AccessOutcome.CONDITIONS_NOT_SATISFIED, outcome(AccessStop.CONDITIONS_NOT_SATISFIED))
        assertEquals(AccessOutcome.CAN_FAILED, outcome(AccessStop.CAN_SINGLE_ATTEMPT_FAILED))
        assertEquals(AccessOutcome.DECLINED, outcome(AccessStop.DECLINED_BY_USER))
        assertEquals(AccessOutcome.CAN_NEEDS_PACE, outcome(AccessStop.CAN_NEEDS_PACE))
        assertEquals(AccessOutcome.NOT_OPENED, outcome(null))
    }

    @Test
    fun `a stop after the chip opened is still an opened read`() {
        val opened = authenticated(stopped = AccessStop.CONDITIONS_NOT_SATISFIED)
        assertEquals(AccessOutcome.OPENED, AuthenticatedSummary.outcome(opened))
    }

    @Test
    fun `every outcome has a plain-language notice, and the refusal says the card was protected`() {
        AccessOutcome.entries.forEach { assertTrue(AuthenticatedSummary.notice(it).text.isNotBlank()) }
        assertEquals(
            "The document did not accept this key. Check the details and try again; stopped after one try to protect the card.",
            AuthenticatedSummary.notice(AccessOutcome.KEY_REFUSED).text
        )
        assertTrue(AuthenticatedSummary.notice(AccessOutcome.KEY_REFUSED).problem)
        assertTrue(AuthenticatedSummary.notice(AccessOutcome.BLOCKED).problem)
        assertFalse(AuthenticatedSummary.notice(AccessOutcome.OPENED).problem)
    }

    @Test
    fun `every stage of the authenticated read has a label that is not the internal id`() {
        AuthenticatedSteps.all.forEach { step ->
            assertFalse(AuthenticatedSummary.stepLabel(step).contains("authenticated/"), step)
        }
        assertEquals("select-aid/p2-0c-no-le", AuthenticatedSummary.stepLabel("select-aid/p2-0c-no-le"))
    }
}
