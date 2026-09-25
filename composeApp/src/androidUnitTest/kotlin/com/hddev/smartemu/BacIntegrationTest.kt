package com.hddev.smartemu

import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.LDSFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the emulated chip with BAC: JMRTD's reader-side PassportService talks to
 * [PassportHceService.processCommandApdu] exactly as it would to a passport over NFC.
 */
class BacIntegrationTest {

    private val passportData = PassportData(
        passportNumber = "NX12345",
        dateOfBirth = LocalDate(1988, 3, 2),
        expiryDate = LocalDate(2033, 11, 30),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "Mary Jane",
        lastName = "O'Neill-Smith",
        gender = "F",
        accessControl = AccessControl.BAC_ONLY
    )

    private lateinit var hceService: PassportHceService

    @Before
    fun setUp() {
        PassportHceService.setSharedPassportData(passportData)
        hceService = PassportHceService()
        hceService.onCreate()
    }

    @After
    fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        hceService.onDestroy()
    }

    private fun openPassportService(isSFIEnabled: Boolean = false): PassportService =
        HceCardService.openPassportService(hceService, isSFIEnabled)

    private fun bacKey(data: PassportData = passportData) =
        BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()

    @Test
    fun `JMRTD reader performs BAC and reads all data groups over secure messaging`() {
        val service = openPassportService()

        // Readers probe EF.CardAccess for PACE first; a BAC-only chip has none, so they fall back to BAC
        assertFailsWith<CardServiceException> {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
        }

        service.sendSelectApplet(false)
        service.doBAC(bacKey())

        val com = COMFile(ByteArrayInputStream(readFile(service, PassportService.EF_COM)))
        assertContentEquals(intArrayOf(LDSFile.EF_DG1_TAG, LDSFile.EF_DG2_TAG), com.tagList)

        val dg1Bytes = readFile(service, PassportService.EF_DG1)
        val mrzInfo = DG1File(ByteArrayInputStream(dg1Bytes)).mrzInfo
        assertEquals("NX12345", mrzInfo.documentNumber)
        assertEquals("880302", mrzInfo.dateOfBirth)
        assertEquals("331130", mrzInfo.dateOfExpiry)
        assertEquals("ONEILL SMITH", mrzInfo.primaryIdentifier)
        assertEquals("MARY JANE", mrzInfo.secondaryIdentifier)
        assertEquals(passportData.toMrzData(), mrzInfo.toString().replace("\n", ""))

        val dg2Bytes = readFile(service, PassportService.EF_DG2)
        val faceImage = DG2File(ByteArrayInputStream(dg2Bytes)).faceInfos.single().faceImageInfos.single()
        val portrait = ImageIO.read(faceImage.imageInputStream)
        assertNotNull(portrait, "DG2 portrait should be a decodable JPEG")
        assertEquals(8, portrait.width)

        // EF.SOD hashes the data groups exactly as read and is signed by the test Document Signer
        val sod = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))
        val sha256 = MessageDigest.getInstance("SHA-256")
        assertContentEquals(sha256.digest(dg1Bytes), sod.dataGroupHashes[1])
        assertContentEquals(sha256.digest(dg2Bytes), sod.dataGroupHashes[2])
        val signedData = CMSSignedData(stripApplicationTag(sod.encoded))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().build(sod.docSigningCertificate)
        assertTrue(signedData.signerInfos.signers.single().verify(verifier), "SOD signature should verify")
        // ...whose certificate is issued by the repository's test CSCA
        val csca = HceCardService.testCsca()
        assertEquals(csca.subjectX500Principal, sod.docSigningCertificate.issuerX500Principal)
        sod.docSigningCertificate.verify(csca.publicKey)
    }
    
    @Test
    fun `chip offering both protocols still accepts BAC`() {
        PassportHceService.setSharedPassportData(passportData.copy(accessControl = AccessControl.BAC_AND_PACE))
        val service = openPassportService()
        service.sendSelectApplet(false)
        service.doBAC(bacKey())

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo

        assertEquals("NX12345", mrzInfo.documentNumber)
    }

    @Test
    fun `JMRTD reader using short file identifiers reads the data groups`() {
        val service = openPassportService(isSFIEnabled = true)
        service.sendSelectApplet(false)
        service.doBAC(bacKey())

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo

        assertEquals("NX12345", mrzInfo.documentNumber)
    }

    @Test
    fun `BAC with the wrong MRZ is rejected and data stays protected`() {
        val service = openPassportService()
        service.sendSelectApplet(false)

        assertFailsWith<CardServiceException> {
            service.doBAC(bacKey(passportData.copy(dateOfBirth = LocalDate(1988, 3, 3))))
        }

        // Without secure messaging the chip refuses to return file contents
        assertFailsWith<CardServiceException> { readFile(service, PassportService.EF_DG1) }
    }

    @Test
    fun `plain READ BINARY is refused before BAC`() {
        openPassportService().sendSelectApplet(false)
        hceService.processCommandApdu(CommandAPDU(0x00, 0xA4, 0x02, 0x0C, byteArrayOf(0x01, 0x01)).bytes, null)

        val response = ResponseAPDU(hceService.processCommandApdu(CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256).bytes, null))

        assertEquals(0x6982, response.sw)
    }

    @Test
    fun `application is not found while the simulation is stopped`() {
        PassportHceService.setSharedPassportData(null)

        assertFailsWith<CardServiceException> { openPassportService().sendSelectApplet(false) }
    }

    @Test
    fun `a new simulation's passport data is used on the next session`() {
        openPassportService().apply { sendSelectApplet(false); doBAC(bacKey()) }
        val updated = passportData.copy(passportNumber = "ZZ9876543")
        PassportHceService.setSharedPassportData(updated)
        hceService.onDeactivated(android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS)

        val service = openPassportService()
        service.sendSelectApplet(false)
        service.doBAC(bacKey(updated))

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
        assertEquals("ZZ9876543", mrzInfo.documentNumber)
    }

    /**
     * EF.SOD is the CMS SignedData wrapped in application tag 0x77.
     */
    private fun stripApplicationTag(encoded: ByteArray): ByteArray {
        val first = encoded[1].toInt() and 0xFF
        val headerLength = if (first < 0x80) 2 else 2 + (first and 0x7F)
        return encoded.copyOfRange(headerLength, encoded.size)
    }
}
