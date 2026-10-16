package com.hddev.smartemu

import android.nfc.cardemulation.HostApduService
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PaceSuite
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SessionCipher
import com.hddev.smartemu.utils.PaceProtocol
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CardFileInputStream
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.tlv.TLVUtil
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.protocol.AESSecureMessagingWrapper
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEProtocol
import org.jmrtd.protocol.PACEResult
import org.jmrtd.protocol.ReadBinaryAPDUSender
import org.jmrtd.protocol.SecureMessagingWrapper
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.IvParameterSpec
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

    /** EF.CardAccess's PACEInfos in the file's order, which JMRTD's CardAccessFile, holding them in a set, loses. */
    private fun readPaceInfos(service: PassportService): List<PACEInfo> {
        val securityInfos = ASN1Set.getInstance(readFile(service, PassportService.EF_CARD_ACCESS))
        return securityInfos.map { ASN1Sequence.getInstance(it) }
            .filter { ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id.startsWith(SecurityInfo.ID_PACE) }
            .map {
                PACEInfo(
                    ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id,
                    ASN1Integer.getInstance(it.getObjectAt(1)).intValueExact(),
                    ASN1Integer.getInstance(it.getObjectAt(2)).value
                )
            }
    }

    /**
     * Runs [block] with Bouncy Castle as the first JCE provider. In Integrated Mapping's pseudo-random function, JMRTD's
     * reader names its keys' algorithm with the whole transformation, "AES/CBC/NoPadding", and keys two-key 3DES with
     * 16 bytes; the JVM's own provider refuses both.
     */
    private fun <T> withBouncyCastleFirst(block: () -> T): T {
        val registered = Security.getProvider("BC")
        val position = Security.getProviders().indexOf(registered) + 1
        Security.removeProvider("BC")
        Security.insertProviderAt(registered ?: BouncyCastleProvider(), 1)
        try {
            return block()
        } finally {
            Security.removeProvider("BC")
            if (registered != null) Security.insertProviderAt(registered, position)
        }
    }

    private fun readPaceInfo(service: PassportService): PACEInfo = readPaceInfos(service).single()

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

    /** A new reader session with a chip for [data]: the field drops first, as between two taps. */
    private fun startChip(data: PassportData): PassportService {
        hceService.onDeactivated(HostApduService.DEACTIVATION_LINK_LOSS)
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
    fun `JMRTD reader performs PACE on every ICAO curve with every cipher chosen in developer settings`() {
        for (curve in EcCurve.entries) {
            for (cipher in SessionCipher.entries) {
                val service = startChip(passportData.copy(paceCurve = curve, paceCipher = cipher))
                val paceInfo = readPaceInfo(service)
                assertEquals(curve.paceParameterId, paceInfo.parameterId.toInt(), "$curve")
                assertEquals(PaceProtocol.oid(PaceMapping.GENERIC, cipher), paceInfo.objectIdentifier, "$cipher")

                val parameters = PACEInfo.toParameterSpec(paceInfo.parameterId)
                service.doPACE(mrzKey(), paceInfo.objectIdentifier, parameters, paceInfo.parameterId)
                service.sendSelectApplet(true)
                assertEquals("SPECI2021", readDocumentNumber(service), "$curve $cipher")
            }
        }
    }

    @Test
    fun `JMRTD reader authenticates the chip with chip authentication mapping on every ICAO curve`() {
        for (curve in EcCurve.entries) {
            val data = passportData.copy(
                paceMapping = PaceMapping.CHIP_AUTHENTICATION, paceCurve = curve, paceCipher = SessionCipher.AES_256
            )
            val service = startChip(data)
            val result = assertIs<PACECAMResult>(doPace(service, selectApplet = false))

            val cardSecurity = CardSecurityFile(
                ByteArrayInputStream(readMasterFileUnderPace(result, PassportService.EF_CARD_SECURITY))
            )
            val chipKey = cardSecurity.chipAuthenticationPublicKeyInfos.single().subjectPublicKey as ECPublicKey
            val mappingKey = (result.mappingResult as PACEGMMappingResult).piccMappingPublicKey as ECPublicKey
            val parameters = PACEInfo.toParameterSpec(curve.paceParameterId) as ECParameterSpec
            val expectedMappingPoint = Util.toBouncyCastleECPoint(chipKey.w, parameters)
                .multiply(Util.os2i(assertNotNull(result.chipAuthenticationData)))
                .normalize()
            assertEquals(Util.toBouncyCastleECPoint(mappingKey.w, parameters).normalize(), expectedMappingPoint, "$curve")
        }
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

    /** JMRTD's PACE with [paceInfo] from EF.CardAccess, then DG1 read in the session it set up. */
    private fun assertJmrtdReadsWith(
        service: PassportService,
        paceInfo: PACEInfo,
        label: String,
        parameterId: BigInteger? = paceInfo.parameterId
    ): PACEResult {
        val result = jmrtdPace(service, paceInfo, parameterId)
        service.sendSelectApplet(true)
        assertEquals("SPECI2021", readDocumentNumber(service), label)
        return result
    }

    /** JMRTD's PACE with the MRZ and [paceInfo]'s protocol, naming [parameterId] in MSE:Set AT. */
    private fun jmrtdPace(service: PassportService, paceInfo: PACEInfo, parameterId: BigInteger? = paceInfo.parameterId): PACEResult {
        val doPace = {
            service.doPACE(mrzKey(), paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), parameterId)
        }
        return if (PACEInfo.toMappingType(paceInfo.objectIdentifier) == PACEInfo.MappingType.IM) {
            withBouncyCastleFirst(doPace)
        } else {
            doPace()
        }
    }

    @Test
    fun `JMRTD reader performs PACE with generic and integrated mapping in every DH group with every cipher`() {
        for (group in DhGroup.entries) {
            for (mapping in listOf(PaceMapping.GENERIC, PaceMapping.INTEGRATED)) {
                for (cipher in SessionCipher.entries) {
                    // JMRTD sends integrated mapping's nonce t as long as s: 32 bytes rather than AES-192's 24
                    if (mapping == PaceMapping.INTEGRATED && cipher == SessionCipher.AES_192) continue
                    val label = "$mapping $group $cipher"
                    val service = startChip(
                        passportData.copy(paceMapping = mapping, paceDhGroup = group, paceCipher = cipher)
                    )
                    val paceInfo = readPaceInfo(service)
                    assertEquals(group.paceParameterId, paceInfo.parameterId.toInt(), label)
                    assertEquals(PaceProtocol.oid(mapping, cipher, dh = true), paceInfo.objectIdentifier, label)

                    assertJmrtdReadsWith(service, paceInfo, label)
                }
            }
        }
    }

    @Test
    fun `JMRTD reader performs PACE with integrated mapping on every curve it works on`() {
        for (curve in EcCurve.entries.filter { it.supportsIntegratedMapping }) {
            for (cipher in SessionCipher.entries.filter { it != SessionCipher.AES_192 }) {
                val label = "$curve $cipher"
                val service = startChip(
                    passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceCurve = curve, paceCipher = cipher)
                )
                val paceInfo = readPaceInfo(service)
                assertEquals(PaceProtocol.oid(PaceMapping.INTEGRATED, cipher), paceInfo.objectIdentifier, label)

                assertJmrtdReadsWith(service, paceInfo, label)
            }
        }
    }

    @Test
    fun `JMRTD's integrated mapping with AES-192 is refused for its overlong nonce t`() {
        val service = startChip(
            passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceCipher = SessionCipher.AES_192)
        )
        val paceInfo = readPaceInfo(service)

        val failure = assertFailsWith<CardServiceException> { jmrtdPace(service, paceInfo) }
        assertEquals(0x6A80, failure.sw)
    }

    @Test
    fun `a reader following ICAO 9303 performs integrated mapping with AES-192 and reads the data groups`() {
        val chips = listOf(
            passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceCurve = EcCurve.BRAINPOOL_P384R1),
            passportData.copy(paceMapping = PaceMapping.INTEGRATED, paceDhGroup = DhGroup.MODP_2048_224)
        )
        for (data in chips) {
            val service = startChip(data.copy(paceCipher = SessionCipher.AES_192))
            val paceInfo = readPaceInfo(service)

            val wrapper = integratedMappingAsSpecified(paceInfo)

            val select = CommandAPDU(0x0C, 0xA4, 0x04, 0x0C, byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01))
            assertEquals(0x9000, wrapper.unwrap(transmit(wrapper.wrap(select))).sw)
            val fileSystem = DefaultFileSystem(ReadBinaryAPDUSender(HceCardService(hceService)), false)
            fileSystem.setWrapper(wrapper)
            fileSystem.selectFile(PassportService.EF_DG1)
            val dg1 = DG1File(CardFileInputStream(PassportService.DEFAULT_MAX_BLOCKSIZE, fileSystem))
            assertEquals("SPECI2021", dg1.mrzInfo.documentNumber, paceInfo.objectIdentifier)
        }
    }

    private fun transmit(command: CommandAPDU): ResponseAPDU = ResponseAPDU(hceService.processCommandApdu(command.bytes, null))

    /** Sends a PACE command in plain and returns its response data, which must come with 9000. */
    private fun transmitPlain(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
        val response = transmit(CommandAPDU(cla, ins, p1, p2, data, if (data.size > 233) 65536 else 256))
        assertEquals(0x9000, response.sw, "${Integer.toHexString(ins)} answered ${Integer.toHexString(response.sw)}")
        return response.data
    }

    private fun generalAuthenticate(last: Boolean, vararg dataObjects: ByteArray): ByteArray = TLVUtil.unwrapDO(
        0x7C,
        transmitPlain(if (last) 0x00 else 0x10, 0x86, 0x00, 0x00, TLVUtil.wrapDO(0x7C, dataObjects.fold(byteArrayOf()) { a, b -> a + b }))
    )

    /**
     * PACE with integrated mapping as ICAO 9303-11 4.4.3.3 words it, with JMRTD's helpers but a nonce t of exactly
     * the key size, where JMRTD's reader makes it as long as s. Returns the secure messaging session.
     */
    private fun integratedMappingAsSpecified(paceInfo: PACEInfo): SecureMessagingWrapper {
        val oid = paceInfo.objectIdentifier
        val cipherAlgorithm = PACEInfo.toCipherAlgorithm(oid)
        val keyLength = PACEInfo.toKeyLength(oid)
        val agreementAlgorithm = PACEInfo.toKeyAgreementAlgorithm(oid)
        val staticParameters = PACEInfo.toParameterSpec(paceInfo.parameterId)
        val passwordKey = PACEProtocol.deriveStaticPACEKey(mrzKey(), oid)

        transmitPlain(
            0x00, 0x22, 0xC1, 0xA4,
            Util.toOIDBytes(oid) + TLVUtil.wrapDO(0x83, byteArrayOf(PassportService.MRZ_PACE_KEY_REFERENCE)) +
                TLVUtil.wrapDO(0x84, Util.i2os(paceInfo.parameterId))
        )
        val z = TLVUtil.unwrapDO(0x80, generalAuthenticate(false))
        val nonceCipher = Util.getCipher("$cipherAlgorithm/CBC/NoPadding")
        nonceCipher.init(Cipher.DECRYPT_MODE, passwordKey, IvParameterSpec(ByteArray(nonceCipher.blockSize)))
        val s = nonceCipher.doFinal(z)
        assertEquals(32, s.size, "nonce s for AES-192")

        val t = ByteArray(keyLength / 8).also { SecureRandom().nextBytes(it) }
        assertContentEquals(byteArrayOf(0x82.toByte(), 0x00), generalAuthenticate(false, TLVUtil.wrapDO(0x81, t)))
        val ephemeralParameters = if (staticParameters is ECParameterSpec) {
            PACEProtocol.mapNonceIMWithECDH(s, t, cipherAlgorithm, staticParameters)
        } else {
            PACEProtocol.mapNonceIMWithDH(s, t, cipherAlgorithm, staticParameters as DHParameterSpec)
        }

        val keyPairGenerator = Util.getKeyPairGenerator(agreementAlgorithm)
        keyPairGenerator.initialize(ephemeralParameters)
        val readerKeyPair = keyPairGenerator.generateKeyPair()
        val chipKey = PACEProtocol.decodePublicKeyFromSmartCard(
            TLVUtil.unwrapDO(0x84, generalAuthenticate(false, TLVUtil.wrapDO(0x83, PACEProtocol.encodePublicKeyForSmartCard(readerKeyPair.public)))),
            ephemeralParameters
        )
        val keyAgreement = Util.getKeyAgreement(agreementAlgorithm)
        keyAgreement.init(readerKeyPair.private)
        keyAgreement.doPhase(PACEProtocol.updateParameterSpec(chipKey, readerKeyPair.private), true)
        val sharedSecret = keyAgreement.generateSecret()
        val encKey = Util.deriveKey(sharedSecret, cipherAlgorithm, keyLength, Util.ENC_MODE)
        val macKey = Util.deriveKey(sharedSecret, cipherAlgorithm, keyLength, Util.MAC_MODE)

        val chipToken = generalAuthenticate(true, TLVUtil.wrapDO(0x85, PACEProtocol.generateAuthenticationToken(oid, macKey, chipKey)))
        assertContentEquals(PACEProtocol.generateAuthenticationToken(oid, macKey, readerKeyPair.public), TLVUtil.unwrapDO(0x86, chipToken))
        return AESSecureMessagingWrapper(encKey, macKey, PassportService.DEFAULT_MAX_BLOCKSIZE, true, 0L)
    }

    /** The document's own CAM suite on NIST P-256, then IM on brainpoolP384r1, DH GM and CAM on brainpoolP256r1. */
    private val multiSuiteData = passportData.copy(
        paceMapping = PaceMapping.CHIP_AUTHENTICATION,
        extraPaceSuites = listOf(
            PaceSuite(PaceMapping.INTEGRATED, PaceSpec(curve = EcCurve.BRAINPOOL_P384R1, cipher = SessionCipher.AES_256)),
            PaceSuite(PaceMapping.GENERIC, PaceSpec(dhGroup = DhGroup.MODP_1024_160)),
            PaceSuite(PaceMapping.CHIP_AUTHENTICATION, PaceSpec(curve = EcCurve.BRAINPOOL_P256R1))
        )
    )

    @Test
    fun `JMRTD reader performs PACE with each suite of a chip that offers several`() {
        val offered = readPaceInfos(startChip(multiSuiteData))
        assertEquals(
            listOf(12, 16, 0, 13) to listOf(
                PaceProtocol.oid(PaceMapping.CHIP_AUTHENTICATION),
                PaceProtocol.oid(PaceMapping.INTEGRATED, SessionCipher.AES_256),
                PaceProtocol.oid(PaceMapping.GENERIC, dh = true),
                PaceProtocol.oid(PaceMapping.CHIP_AUTHENTICATION)
            ),
            offered.map { it.parameterId.toInt() } to offered.map { it.objectIdentifier }
        )

        for (index in offered.indices) {
            val service = startChip(multiSuiteData)
            val paceInfo = readPaceInfos(service)[index]
            val parameterId = paceInfo.parameterId.toInt()
            val result = jmrtdPace(service, paceInfo)

            if (result is PACECAMResult) {
                // ICAO 9303-11 4.4.5.5: the chip's key for a CAM suite is the one whose key ID is its parameter ID
                val cardSecurity = CardSecurityFile(
                    ByteArrayInputStream(readMasterFileUnderPace(result, PassportService.EF_CARD_SECURITY))
                )
                assertEquals(4, cardSecurity.paceInfos.size)
                val keyInfos = cardSecurity.chipAuthenticationPublicKeyInfos
                assertEquals(setOf(12, 13), keyInfos.map { it.keyId.toInt() }.toSet())
                val chipKey = keyInfos.single { it.keyId.toInt() == parameterId }.subjectPublicKey as ECPublicKey
                val mappingKey = (result.mappingResult as PACEGMMappingResult).piccMappingPublicKey as ECPublicKey
                val parameters = PACEInfo.toParameterSpec(paceInfo.parameterId) as ECParameterSpec
                val expectedMappingPoint = Util.toBouncyCastleECPoint(chipKey.w, parameters)
                    .multiply(Util.os2i(assertNotNull(result.chipAuthenticationData)))
                    .normalize()
                assertEquals(Util.toBouncyCastleECPoint(mappingKey.w, parameters).normalize(), expectedMappingPoint, "$parameterId")
            }
            service.sendSelectApplet(true)
            assertEquals("SPECI2021", readDocumentNumber(service), "suite $index")
        }
    }

    @Test
    fun `extra suites can come before the document's own in EF CardAccess`() {
        val offered = readPaceInfos(startChip(multiSuiteData.copy(extraPaceSuitesFirst = true)))

        assertEquals(listOf(16, 0, 13, 12), offered.map { it.parameterId.toInt() })
    }

    @Test
    fun `a reader that names no domain parameters is refused by a chip that offers several`() {
        val service = startChip(multiSuiteData)
        val paceInfo = readPaceInfos(service).first()

        val failure = assertFailsWith<CardServiceException> { jmrtdPace(service, paceInfo, parameterId = null) }
        assertEquals(0x6A80, failure.sw)
    }
}
