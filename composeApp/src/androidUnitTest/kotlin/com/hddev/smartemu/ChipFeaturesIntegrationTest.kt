package com.hddev.smartemu

import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.PassportLdsFiles
import com.hddev.smartemu.utils.PassportLdsFiles.Companion.DocumentSigner
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.LDSFile
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SignatureException
import java.security.Signature
import java.security.cert.CertificateExpiredException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads the emulated chip with JMRTD's reader side to check TD1 cards, Active Authentication and each chip fault
 * as a reader would find them.
 */
class ChipFeaturesIntegrationTest {

    private val passportData = PassportData(
        passportNumber = "SPEC24681",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE",
        nationality = "SWE",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F",
        accessControl = AccessControl.BAC_ONLY
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

    /** Emulates [data], unlocks the chip with BAC and returns the reader. */
    private fun readerFor(data: PassportData): PassportService {
        PassportHceService.setSharedPassportData(data)
        val service = HceCardService.openPassportService(hceService)
        service.sendSelectApplet(false)
        service.doBAC(BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate()))
        return service
    }

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()

    private fun readSod(service: PassportService) = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))

    /** Whether [signature], in the plain r||s format, is [publicKey]'s ECDSA signature of [challenge] with SHA-256. */
    private fun verifiesPlain(publicKey: PublicKey, challenge: ByteArray, signature: ByteArray): Boolean {
        val half = signature.size / 2
        val der = DERSequence(
            arrayOf(
                ASN1Integer(BigInteger(1, signature.copyOfRange(0, half))),
                ASN1Integer(BigInteger(1, signature.copyOfRange(half, signature.size)))
            )
        ).encoded
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(der)
        }
    }

    private fun sodSignatureVerifies(sod: SODFile): Boolean {
        val encoded = sod.encoded
        val first = encoded[1].toInt() and 0xFF
        val headerLength = if (first < 0x80) 2 else 2 + (first and 0x7F)
        val signedData = CMSSignedData(encoded.copyOfRange(headerLength, encoded.size))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().build(sod.docSigningCertificate)
        return signedData.signerInfos.signers.single().verify(verifier)
    }

    @Test
    fun `an ID card's DG1 holds the three line TD1 MRZ`() {
        val card = passportData.copy(documentType = DocumentType.ID_CARD)
        val service = readerFor(card)

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo

        assertEquals(card.toMrzData(), mrzInfo.toString().replace("\n", ""))
        assertEquals(90, card.toMrzData().length)
        assertEquals("ID", mrzInfo.documentCode)
        assertEquals("SPEC24681", mrzInfo.documentNumber)
    }

    @Test
    fun `cards are signed by the ID document signer and passports by the passport one`() {
        val cardSod = readSod(readerFor(passportData.copy(documentType = DocumentType.RESIDENCE_PERMIT)))
        val passportSod = readSod(readerFor(passportData))

        assertEquals(DocumentSigner.ID_DOCUMENT.certificate, cardSod.docSigningCertificate)
        assertEquals(DocumentSigner.PASSPORT.certificate, passportSod.docSigningCertificate)
        cardSod.docSigningCertificate.verify(HceCardService.testCsca().publicKey)
        assertTrue(sodSignatureVerifies(cardSod))
    }

    @Test
    fun `COM lists DG14 and DG15 only when the chip has Active Authentication`() {
        val plain = COMFile(ByteArrayInputStream(readFile(readerFor(passportData), PassportService.EF_COM)))
        val withAa = COMFile(
            ByteArrayInputStream(readFile(readerFor(passportData.copy(activeAuthentication = true)), PassportService.EF_COM))
        )

        assertContentEquals(intArrayOf(LDSFile.EF_DG1_TAG, LDSFile.EF_DG2_TAG), plain.tagList)
        assertContentEquals(
            intArrayOf(LDSFile.EF_DG1_TAG, LDSFile.EF_DG2_TAG, LDSFile.EF_DG14_TAG, LDSFile.EF_DG15_TAG),
            withAa.tagList
        )
    }

    @Test
    fun `Active Authentication signs the reader's challenge with the DG15 key`() {
        val service = readerFor(passportData.copy(activeAuthentication = true))
        val dg14Bytes = readFile(service, PassportService.EF_DG14)
        val dg15Bytes = readFile(service, PassportService.EF_DG15)
        val aaInfo = DG14File(ByteArrayInputStream(dg14Bytes)).securityInfos.filterIsInstance<ActiveAuthenticationInfo>().single()
        val publicKey = DG15File(ByteArrayInputStream(dg15Bytes)).publicKey
        val challenge = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val result = service.doAA(publicKey, "SHA-256", "SHA256withECDSA", challenge)

        assertEquals(ActiveAuthenticationInfo.ECDSA_PLAIN_SHA256_OID, aaInfo.signatureAlgorithmOID)
        assertEquals(64, result.response.size)
        assertTrue(verifiesPlain(publicKey, challenge, result.response), "the chip should prove it has the DG15 key")
        // DG14 and DG15 are covered by EF.SOD like the other data groups
        val sod = readSod(service)
        val sha256 = MessageDigest.getInstance("SHA-256")
        assertContentEquals(sha256.digest(dg14Bytes), sod.dataGroupHashes[14])
        assertContentEquals(sha256.digest(dg15Bytes), sod.dataGroupHashes[15])
    }

    @Test
    fun `a cloned chip's Active Authentication signature does not verify`() {
        val service = readerFor(passportData.copy(activeAuthentication = true, chipFault = ChipFault.CLONED_CHIP))
        val publicKey = DG15File(ByteArrayInputStream(readFile(service, PassportService.EF_DG15))).publicKey
        val challenge = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)

        val result = service.doAA(publicKey, "SHA-256", "SHA256withECDSA", challenge)

        assertFalse(verifiesPlain(publicKey, challenge, result.response))
    }

    @Test
    fun `a chip without Active Authentication refuses INTERNAL AUTHENTICATE`() {
        val service = readerFor(passportData)
        val someKey = DocumentSigner.PASSPORT.certificate.publicKey

        // JMRTD hands back whatever data comes with an error status, so the refusal shows as no signature
        val response = runCatching { service.doAA(someKey, "SHA-256", "SHA256withECDSA", ByteArray(8)).response }

        assertTrue(response.isFailure || response.getOrNull()?.isEmpty() != false, "no signature expected")
    }

    @Test
    fun `INTERNAL AUTHENTICATE is unknown to a chip without Active Authentication and needs secure messaging`() {
        fun plainInternalAuthenticate(data: PassportData): Int {
            PassportHceService.setSharedPassportData(data)
            HceCardService.openPassportService(hceService).sendSelectApplet(false)
            val command = CommandAPDU(0x00, 0x88, 0x00, 0x00, ByteArray(8), 256)
            return ResponseAPDU(hceService.processCommandApdu(command.bytes, null)).sw
        }

        assertEquals(0x6D00, plainInternalAuthenticate(passportData))
        hceService.onDeactivated(android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS)
        assertEquals(0x6982, plainInternalAuthenticate(passportData.copy(activeAuthentication = true)))
    }

    @Test
    fun `altered details and a swapped photo no longer match their EF_SOD hashes`() {
        val sha256 = MessageDigest.getInstance("SHA-256")
        for ((fault, dataGroup) in listOf(ChipFault.ALTERED_DETAILS to 1, ChipFault.SWAPPED_PHOTO to 2)) {
            val service = readerFor(passportData.copy(chipFault = fault))
            val dg1 = readFile(service, PassportService.EF_DG1)
            val dg2 = readFile(service, PassportService.EF_DG2)
            val sod = readSod(service)
            val hashes = mapOf(1 to sha256.digest(dg1), 2 to sha256.digest(dg2))

            for (number in 1..2) {
                val matches = hashes.getValue(number).contentEquals(sod.dataGroupHashes[number])
                assertEquals(number != dataGroup, matches, "$fault: DG$number hash")
            }
            // The signature itself is still good, so the reader has to check the hashes to notice
            assertTrue(sodSignatureVerifies(sod), "$fault: SOD signature")
        }
    }

    @Test
    fun `a broken signature does not verify with the Document Signer's key`() {
        val sod = readSod(readerFor(passportData.copy(chipFault = ChipFault.BROKEN_SIGNATURE)))

        assertEquals(DocumentSigner.PASSPORT.certificate, sod.docSigningCertificate)
        assertFalse(sodSignatureVerifies(sod))
    }

    @Test
    fun `an expired Document Signer is issued by the test CSCA but no longer valid`() {
        val certificate = readSod(readerFor(passportData.copy(chipFault = ChipFault.EXPIRED_SIGNER))).docSigningCertificate

        certificate.verify(HceCardService.testCsca().publicKey)
        assertFailsWith<CertificateExpiredException> { certificate.checkValidity() }
    }

    @Test
    fun `an untrusted Document Signer does not chain to the test CSCA`() {
        val sod = readSod(readerFor(passportData.copy(chipFault = ChipFault.UNTRUSTED_SIGNER)))
        val csca = HceCardService.testCsca()

        assertTrue(sodSignatureVerifies(sod), "the SOD is signed properly, just by the wrong issuer")
        assertTrue(csca.subjectX500Principal != sod.docSigningCertificate.issuerX500Principal)
        assertFailsWith<SignatureException> { sod.docSigningCertificate.verify(csca.publicKey) }
    }

    @Test
    fun `DER signatures convert to fixed length plain signatures`() {
        // r with a leading zero byte for its sign, s short of the field size
        val r = ByteArray(32) { 0x80.toByte() }
        val s = ByteArray(31) { 0x11 }
        val der = DERSequence(arrayOf(ASN1Integer(BigInteger(1, r)), ASN1Integer(BigInteger(1, s)))).encoded

        val plain = PassportLdsFiles.derToPlainSignature(der)

        assertContentEquals(r + byteArrayOf(0) + s, plain)
    }
}
