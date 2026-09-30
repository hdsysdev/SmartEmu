package com.hddev.smartemu

import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.KeySpec
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.tlv.TLVUtil
import kotlinx.coroutines.launch
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.CVCAFile
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads the chip of every [ChipProfile] with JMRTD's reader side, with the cryptography adapted and exact: access
 * control, passive authentication against the country's test CSCA, Chip Authentication, Active Authentication, the
 * locked fingerprints and the profile's MRZ and error conventions.
 */
class ChipProfilesIntegrationTest {

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

    /** The profile's ready-made document, or the base holder on a document of its kind. */
    private fun documentFor(profile: ChipProfile, exact: Boolean = false): PassportData {
        val data = PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today)
            ?: base.withChipProfile(profile)
        return data.copy(exactCryptography = exact)
    }

    private fun readFile(service: PassportService, fid: Short): ByteArray =
        service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()

    /** Emulates [data] and unlocks it as JMRTD-based readers do: PACE if EF.CardAccess offers it, else BAC. */
    private fun unlock(data: PassportData): PassportService {
        PassportHceService.setSharedPassportData(data)
        val service = HceCardService.openPassportService(hceService)
        val key = BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate())
        if (data.accessControl.supportsPace) {
            val paceInfo = CardAccessFile(ByteArrayInputStream(readFile(service, PassportService.EF_CARD_ACCESS)))
                .securityInfos.filterIsInstance<PACEInfo>().single()
            service.doPACE(key, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), paceInfo.parameterId)
            service.sendSelectApplet(true)
        } else {
            service.sendSelectApplet(false)
            service.doBAC(key)
        }
        return service
    }

    private fun cardAccessParameterId(data: PassportData): Int {
        PassportHceService.setSharedPassportData(data)
        val service = HceCardService.openPassportService(hceService)
        return CardAccessFile(ByteArrayInputStream(readFile(service, PassportService.EF_CARD_ACCESS)))
            .securityInfos.filterIsInstance<PACEInfo>().single().parameterId.toInt()
    }

    /** The test CSCA that issued [signer], found by the country suffix of its file name. */
    private fun cscaFor(profile: ChipProfile): X509Certificate {
        val country = profile.sod.passportSigner.removePrefix("smartemu-test-ds").substringBefore("-rsa")
        val name = "smartemu-test-csca$country"
        return File("../test-pki/csca/$name.cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private fun assertPassiveAuthentication(profile: ChipProfile, service: PassportService, dataGroups: List<Int>) {
        val sod = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD)))
        val signer = sod.docSigningCertificate
        signer.verify(cscaFor(profile).publicKey, Util.getBouncyCastleProvider())

        val encoded = sod.encoded
        val first = encoded[1].toInt() and 0xFF
        val headerLength = if (first < 0x80) 2 else 2 + (first and 0x7F)
        val signedData = CMSSignedData(encoded.copyOfRange(headerLength, encoded.size))
        val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(Util.getBouncyCastleProvider()).build(signer)
        assertTrue(signedData.signerInfos.signers.single().verify(verifier), "SOD signature of ${profile.id}")

        assertEquals(profile.sod.digestAlgorithm, sod.digestAlgorithm)
        when (profile.sod.signerKey) {
            is KeySpec.Rsa -> assertTrue(signer.publicKey is RSAPublicKey)
            is KeySpec.Ec -> assertTrue(signer.publicKey is ECPublicKey)
        }
        val hashes = sod.dataGroupHashes
        assertEquals(dataGroups.toSet(), hashes.keys)
        for (number in dataGroups.filter { it != 3 }) {
            val content = readFile(service, (PassportService.EF_DG1 + number - 1).toShort())
            assertContentEquals(
                MessageDigest.getInstance(sod.digestAlgorithm).digest(content),
                hashes[number],
                "DG$number hash of ${profile.id}"
            )
        }
    }

    @Test
    fun `every profile reads and passes passive authentication, adapted and exact`() {
        for (profile in ChipProfiles.all) {
            for (exact in listOf(false, true)) {
                val data = documentFor(profile, exact)
                val service = unlock(data)
                val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
                assertEquals(data.toMrzData(), mrzInfo.toString().replace("\n", ""), "MRZ of ${profile.id}")
                assertPassiveAuthentication(profile, service, data.chipConfiguration().dataGroups)
            }
        }
    }

    @Test
    fun `adapted Brainpool PACE runs on the NIST curve and exact on Brainpool`() {
        val profile = ChipProfiles.byId("de-passport-2017")
        assertEquals(EcCurve.BRAINPOOL_P256R1, profile.pace.curve)

        assertEquals(EcCurve.NIST_P256.paceParameterId, cardAccessParameterId(documentFor(profile, exact = false)))
        assertEquals(EcCurve.BRAINPOOL_P256R1.paceParameterId, cardAccessParameterId(documentFor(profile, exact = true)))
        assertTrue(documentFor(profile, exact = false).chipConfiguration().adaptations.isNotEmpty())
        assertTrue(documentFor(profile, exact = true).chipConfiguration().adaptations.isEmpty())
    }

    @Test
    fun `the German MRZ has the issuing state D and the chip signs with Brainpool`() {
        val profile = ChipProfiles.byId("de-passport-2017")
        val data = documentFor(profile)
        val service = unlock(data)

        val mrz = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo.toString()
        assertTrue(mrz.startsWith("P<D<<MUSTERMANN<<ERIKA"), mrz)
        val signerKey = SODFile(ByteArrayInputStream(readFile(service, PassportService.EF_SOD))).docSigningCertificate.publicKey
        assertEquals(32, ((signerKey as ECPublicKey).params.curve.field.fieldSize + 7) / 8)
    }

    @Test
    fun `Chip Authentication restarts secure messaging and the data stays readable`() {
        for (profile in ChipProfiles.all.filter { it.chipAuthentication != null }) {
            for (exact in listOf(false, true)) {
                val data = documentFor(profile, exact)
                val service = unlock(data)
                val dg14 = DG14File(ByteArrayInputStream(readFile(service, PassportService.EF_DG14)))
                val caInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationInfo>().single()
                val keyInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().single()

                val result = service.doEACCA(keyInfo.keyId, caInfo.objectIdentifier, keyInfo.objectIdentifier, keyInfo.subjectPublicKey)

                assertNotNull(result.wrapper)
                val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
                assertEquals(data.passportNumber, mrzInfo.documentNumber, "DG1 after Chip Authentication on ${profile.id}")
            }
        }
    }

    @Test
    fun `Chip Authentication and a refused Terminal Authentication leave the read complete and unlocked by PACE`() {
        val events = java.util.Collections.synchronizedList(mutableListOf<com.hddev.smartemu.data.NfcEvent>())
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            PassportHceService.nfcEvents.collect { events += it }
        }
        try {
            val data = documentFor(ChipProfiles.byId("de-passport-2017"))
            val service = unlock(data)
            val dg14 = DG14File(ByteArrayInputStream(readFile(service, PassportService.EF_DG14)))
            val caInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationInfo>().single()
            val keyInfo = dg14.securityInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().single()
            service.doEACCA(keyInfo.keyId, caInfo.objectIdentifier, keyInfo.objectIdentifier, keyInfo.subjectPublicKey)

            // MSE:Set DST naming a CVCA the chip doesn't know, as a terminal of another issuer would
            val reference = "XXOTHERCVCA01".toByteArray()
            val setDst = CommandAPDU(0x00, 0x22, 0x81, 0xB6, TLVUtil.wrapDO(0x83, reference))
            val wrapped = service.wrapper.wrap(setDst)
            val response = service.wrapper.unwrap(ResponseAPDU(hceService.processCommandApdu(wrapped.bytes, null)))
            assertEquals(0x6A88, response.sw)

            listOf(PassportService.EF_COM, PassportService.EF_DG1, PassportService.EF_DG2, PassportService.EF_SOD).forEach {
                readFile(service, it)
            }
            val record = assertNotNull(com.hddev.smartemu.data.ReadRecord.of(events.toList(), data))
            assertEquals("PACE-GM", record.accessProtocol)
            assertEquals(com.hddev.smartemu.data.ReadOutcome.COMPLETE, record.outcome)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `fingerprints stay locked behind Terminal Authentication and EF CVCA names the test CVCA`() {
        val profile = ChipProfiles.byId("de-passport-2017")
        val service = unlock(documentFor(profile))

        val error = assertFailsWith<CardServiceException> { readFile(service, PassportService.EF_DG3) }
        assertEquals(0x6982, error.sw)
        val cvca = CVCAFile(ByteArrayInputStream(readFile(service, PassportService.EF_CVCA)))
        assertEquals("DETESTCVCA00001", cvca.caReference.name)
    }

    @Test
    fun `the Dutch passport holds DG11 and DG12 and the personal number in the MRZ`() {
        val profile = ChipProfiles.byId("nl-passport")
        val data = documentFor(profile)
        val service = unlock(data)

        val dg11 = DG11File(ByteArrayInputStream(readFile(service, PassportService.EF_DG11)))
        val dg12 = DG12File(ByteArrayInputStream(readFile(service, PassportService.EF_DG12)))
        val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo

        assertEquals("999999990", dg11.personalNumber)
        assertEquals(listOf("Den Haag"), dg11.placeOfBirth)
        assertEquals("Burg. van Den Haag", dg12.issuingAuthority)
        assertEquals("999999990", mrzInfo.personalNumber)
    }

    @Test
    fun `Active Authentication signs with the profile's key, ECDSA and RSA`() {
        for (profile in ChipProfiles.all.filter { it.activeAuthentication != null }) {
            val service = unlock(documentFor(profile))
            val publicKey = DG15File(ByteArrayInputStream(readFile(service, PassportService.EF_DG15))).publicKey
            val challenge = ByteArray(8) { it.toByte() }
            when (val key = profile.activeAuthentication) {
                is KeySpec.Ec -> {
                    val response = service.doAA(publicKey, key.curve.digestAlgorithm, "SHA256withECDSA", challenge).response
                    assertTrue(verifiesPlain(publicKey, key.curve, challenge, response), "AA on ${profile.id}")
                }
                is KeySpec.Rsa -> {
                    assertEquals(key.bits, (publicKey as RSAPublicKey).modulus.bitLength())
                    val response = service.doAA(publicKey, "SHA-1", "SHA1WithRSA/ISO9796-2", challenge).response
                    assertTrue(verifiesIso9796(publicKey, challenge, response), "AA on ${profile.id}")
                }
                null -> error("unreachable")
            }
        }
    }

    @Test
    fun `EXTERNAL AUTHENTICATE with no challenge gets the profile's status word`() {
        val expected = mapOf(
            "generic" to 0x6985, "de-passport-2005" to 0x6985, "nl-passport-2006" to 0x6982,
            "es-passport-2008-study" to 0x6300
        )
        for ((id, statusWord) in expected) {
            val data = documentFor(ChipProfiles.byId(id)).copy(accessControl = com.hddev.smartemu.data.AccessControl.BAC_ONLY)
            PassportHceService.setSharedPassportData(data)
            val service = HceCardService.openPassportService(hceService)
            service.sendSelectApplet(false)
            val response = ResponseAPDU(
                hceService.processCommandApdu(CommandAPDU(0x00, 0x82, 0x00, 0x00, ByteArray(40), 40).bytes, null)
            )
            assertEquals(statusWord, response.sw, id)
        }
    }

    @Test
    fun `new hybrid passport profiles can also be read with BAC fallback`() {
        for (id in listOf("es-passport-third-generation")) {
            val profile = ChipProfiles.byId(id)
            assertEquals(id, profile.id)
            val data = documentFor(profile)
            PassportHceService.setSharedPassportData(data)
            val service = HceCardService.openPassportService(hceService)
            service.sendSelectApplet(false)
            service.doBAC(BACKey(data.passportNumber, data.mrzDateOfBirth(), data.mrzExpiryDate()))
            val mrzInfo = DG1File(ByteArrayInputStream(readFile(service, PassportService.EF_DG1))).mrzInfo
            assertEquals(data.toMrzData(), mrzInfo.toString().replace("\n", ""), id)
            assertPassiveAuthentication(profile, service, data.chipConfiguration().dataGroups)
        }
    }

    @Test
    fun `the Dutch ID card uses the document code I`() {
        val data = documentFor(ChipProfiles.byId("nl-id-card"))
        assertEquals(DocumentType.ID_CARD, data.documentType)
        val mrzInfo = DG1File(ByteArrayInputStream(readFile(unlock(data), PassportService.EF_DG1))).mrzInfo
        assertEquals("I", mrzInfo.documentCode)
    }

    private fun verifiesPlain(publicKey: PublicKey, curve: EcCurve, challenge: ByteArray, signature: ByteArray): Boolean {
        val half = signature.size / 2
        assertEquals(curve.fieldSize, half)
        val der = DERSequence(
            arrayOf(
                ASN1Integer(BigInteger(1, signature.copyOfRange(0, half))),
                ASN1Integer(BigInteger(1, signature.copyOfRange(half, signature.size)))
            )
        ).encoded
        return Signature.getInstance("${curve.digestAlgorithm.replace("-", "")}withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(der)
        }
    }

    /** Verifies an ISO 9796-2 scheme 1 signature with SHA-1, as JMRTD-based readers verify RSA AA. */
    private fun verifiesIso9796(publicKey: PublicKey, challenge: ByteArray, signature: ByteArray): Boolean {
        val plaintext = Cipher.getInstance("RSA/NONE/NoPadding", Util.getBouncyCastleProvider()).run {
            init(Cipher.DECRYPT_MODE, publicKey)
            doFinal(signature)
        }
        val m1 = Util.recoverMessage(20, plaintext)
        return Signature.getInstance("SHA1WithRSA/ISO9796-2", Util.getBouncyCastleProvider()).run {
            initVerify(publicKey)
            update(m1 + challenge)
            verify(signature)
        }
    }
}
