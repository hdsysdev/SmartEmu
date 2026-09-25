package com.hddev.smartemu

import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CardFileInputStream
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.jmrtd.AccessKeySpec
import org.jmrtd.BACKey
import org.jmrtd.DefaultFileSystem
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.protocol.AESSecureMessagingWrapper
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEResult
import org.jmrtd.protocol.ReadBinaryAPDUSender
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the emulated chip with PACE: JMRTD's reader-side PassportService reads EF.CardAccess and
 * runs PACE against [PassportHceService.processCommandApdu], as a reader would for a passport over NFC.
 */
class PaceIntegrationTest {

    private val passportData = PassportData(
        passportNumber = "SPECI2021",
        dateOfBirth = LocalDate(1965, 3, 12),
        expiryDate = LocalDate(2035, 10, 1),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "Willeke Liselotte",
        lastName = "De Bruijn",
        gender = "F",
        accessControl = AccessControl.BAC_AND_PACE
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

    private fun mrzKey(data: PassportData = passportData) =
        BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()

    private fun readPaceInfo(service: PassportService): PACEInfo {
        val cardAccess = CardAccessFile(ByteArrayInputStream(readFile(service, PassportService.EF_CARD_ACCESS)))
        return cardAccess.securityInfos.filterIsInstance<PACEInfo>().single()
    }

    /**
     * The flow of JMRTD-based readers: PACE with the parameters EF.CardAccess advertises, then select the
     * application under secure messaging.
     */
    private fun doPace(service: PassportService, key: AccessKeySpec = mrzKey(), selectApplet: Boolean = true): PACEResult {
        val paceInfo = readPaceInfo(service)
        val result = service.doPACE(
            key,
            paceInfo.objectIdentifier,
            PACEInfo.toParameterSpec(paceInfo.parameterId),
            paceInfo.parameterId
        )
        assertIs<AESSecureMessagingWrapper>(result.wrapper)
        if (selectApplet) service.sendSelectApplet(true)
        return result
    }

    private fun readDocumentNumber(service: PassportService): String =
        DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo.documentNumber

    private fun startChip(data: PassportData): PassportService {
        PassportHceService.setSharedPassportData(data)
        return HceCardService.openPassportService(hceService)
    }

    @Test
    fun `EF CardAccess advertises PACE ECDH generic mapping with AES on NIST P-256`() {
        val paceInfo = readPaceInfo(HceCardService.openPassportService(hceService))

        assertEquals(PACEInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128, paceInfo.objectIdentifier)
        assertEquals(2, paceInfo.version)
        assertEquals(PACEInfo.PARAM_ID_ECP_NIST_P256_R1, paceInfo.parameterId.toInt())
    }

    @Test
    fun `JMRTD reader performs PACE and reads the data groups over AES secure messaging`() {
        val service = HceCardService.openPassportService(hceService)

        doPace(service)

        val dg1Bytes = readFile(service, PassportService.EF_DG1)
        val mrzInfo = DG1File(ByteArrayInputStream(dg1Bytes)).mrzInfo
        assertEquals("SPECI2021", mrzInfo.documentNumber)
        assertEquals(passportData.toMrzData(), mrzInfo.toString().replace("\n", ""))

        val dg2Bytes = readFile(service, PassportService.EF_DG2)
        val sod = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))
        val sha256 = MessageDigest.getInstance("SHA-256")
        assertContentEquals(sha256.digest(dg1Bytes), sod.dataGroupHashes[1])
        assertContentEquals(sha256.digest(dg2Bytes), sod.dataGroupHashes[2])
    }

    @Test
    fun `JMRTD reader using short file identifiers reads the data groups after PACE`() {
        val service = HceCardService.openPassportService(hceService, isSFIEnabled = true)

        doPace(service)

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
        assertEquals("SPECI2021", mrzInfo.documentNumber)
    }

    @Test
    fun `PACE with the wrong MRZ is rejected and data stays protected`() {
        val service = HceCardService.openPassportService(hceService)

        assertFailsWith<CardServiceException> {
            doPace(service, mrzKey(passportData.copy(expiryDate = LocalDate(2035, 10, 2))))
        }

        service.sendSelectApplet(false)
        assertFailsWith<CardServiceException> { readFile(service, PassportService.EF_DG1) }
    }

    @Test
    fun `PACE-only chip refuses BAC`() {
        PassportHceService.setSharedPassportData(passportData.copy(accessControl = AccessControl.PACE_ONLY))
        val service = HceCardService.openPassportService(hceService)
        service.sendSelectApplet(false)

        assertFailsWith<CardServiceException> { service.doBAC(mrzKey()) }
    }

    @Test
    fun `PACE-only chip accepts PACE`() {
        PassportHceService.setSharedPassportData(passportData.copy(accessControl = AccessControl.PACE_ONLY))
        val service = HceCardService.openPassportService(hceService)

        doPace(service)

        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
        assertEquals("SPECI2021", mrzInfo.documentNumber)
    }

    @Test
    fun `BAC-only chip has no EF CardAccess and refuses PACE`() {
        PassportHceService.setSharedPassportData(passportData.copy(accessControl = AccessControl.BAC_ONLY))
        val service = HceCardService.openPassportService(hceService)

        assertFailsWith<CardServiceException> { readFile(service, PassportService.EF_CARD_ACCESS) }
        assertFailsWith<CardServiceException> {
            service.doPACE(
                mrzKey(),
                PACEInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128,
                PACEInfo.toParameterSpec(PACEInfo.PARAM_ID_ECP_NIST_P256_R1),
                null
            )
        }
    }

    @Test
    fun `JMRTD reader performs PACE with the CAN`() {
        val service = startChip(passportData.copy(can = "482613"))

        doPace(service, PACEKeySpec.createCANKey("482613"))

        assertEquals("SPECI2021", readDocumentNumber(service))
    }

    @Test
    fun `PACE with a CAN is refused when the document has none`() {
        val service = HceCardService.openPassportService(hceService)

        assertFailsWith<CardServiceException> { doPace(service, PACEKeySpec.createCANKey("482613")) }
    }

    @Test
    fun `PACE with the wrong CAN is rejected`() {
        val service = startChip(passportData.copy(can = "482613"))

        assertFailsWith<CardServiceException> { doPace(service, PACEKeySpec.createCANKey("482614")) }
    }

    @Test
    fun `JMRTD reader performs PACE with chip authentication mapping and authenticates the chip`() {
        val service = startChip(passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION, can = "482613"))
        assertEquals(PACEInfo.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128, readPaceInfo(service).objectIdentifier)

        val result = assertIs<PACECAMResult>(doPace(service, PACEKeySpec.createCANKey("482613"), selectApplet = false))
        val chipAuthenticationData = assertNotNull(result.chipAuthenticationData)

        // ICAO 9303-11 4.4.3.5: the reader takes the chip's key from EF.CardSecurity, checks its signature, and
        // checks that PK_map_PICC = CA_PICC * PK_PICC
        val cardSecurityBytes = readMasterFileUnderPace(result, PassportService.EF_CARD_SECURITY)
        verifySignedByTestDocumentSigner(cardSecurityBytes)
        val cardSecurity = CardSecurityFile(ByteArrayInputStream(cardSecurityBytes))
        val chipKey = cardSecurity.chipAuthenticationPublicKeyInfos.single().subjectPublicKey as ECPublicKey
        val mappingKey = (result.mappingResult as PACEGMMappingResult).piccMappingPublicKey as ECPublicKey
        val parameters = PACEInfo.toParameterSpec(PACEInfo.PARAM_ID_ECP_NIST_P256_R1) as ECParameterSpec

        val expectedMappingPoint = Util.toBouncyCastleECPoint(chipKey.w, parameters)
            .multiply(Util.os2i(chipAuthenticationData))
            .normalize()
        assertEquals(Util.toBouncyCastleECPoint(mappingKey.w, parameters).normalize(), expectedMappingPoint)

        // The session continues into the application, where 011D is EF.SOD
        service.sendSelectApplet(true)
        assertEquals("SPECI2021", readDocumentNumber(service))
        SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))
    }

    @Test
    fun `EF CardSecurity cannot be read without PACE`() {
        val service = startChip(passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION))

        assertFailsWith<CardServiceException> { readFile(service, PassportService.EF_CARD_SECURITY) }
    }

    /**
     * Reads an EF of the master file under the PACE session, before the application is selected. PassportService
     * reads the master file in plain, which would end the session, so this uses JMRTD's file system with the
     * PACE wrapper directly.
     */
    private fun readMasterFileUnderPace(result: PACEResult, fid: Short): ByteArray {
        val fileSystem = DefaultFileSystem(ReadBinaryAPDUSender(HceCardService(hceService)), false)
        fileSystem.setWrapper(result.wrapper)
        fileSystem.selectFile(fid)
        return CardFileInputStream(PassportService.DEFAULT_MAX_BLOCKSIZE, fileSystem).readBytes()
    }

    private fun verifySignedByTestDocumentSigner(signedDataBytes: ByteArray) {
        val signedData = CMSSignedData(signedDataBytes)
        val signer = signedData.signerInfos.signers.single()
        val certificate = signedData.certificates.getMatches(null).single() as X509CertificateHolder

        assertTrue(signer.verify(JcaSimpleSignerInfoVerifierBuilder().build(certificate)))
        assertTrue(certificate.isSignatureValid(JcaContentVerifierProviderBuilder().build(HceCardService.testCsca())))
    }

    @Test
    fun `plain command after PACE ends the secure messaging session`() {
        val service = HceCardService.openPassportService(hceService)
        doPace(service)

        // A plain SELECT of the application aborts the session, so the reader's next protected command fails
        val plainSelect = CommandAPDU(0x00, 0xA4, 0x04, 0x0C, byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01))
        assertEquals(0x9000, ResponseAPDU(hceService.processCommandApdu(plainSelect.bytes, null)).sw)

        assertFails { readFile(service, PassportService.EF_DG1) }
    }
}
