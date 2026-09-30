package com.hddev.smartemu.utils

import com.hddev.smartemu.data.ChipConfiguration
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.KeySpec
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SessionCipher
import com.hddev.smartemu.data.chipConfiguration
import net.sf.scuba.data.Gender
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.ActiveAuthenticationInfo
import org.jmrtd.lds.CVCAFile
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.LDSFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.TerminalAuthenticationInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso19794.FingerInfo
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The elementary files of the emulated eMRTD application (LDS1), encoded with JMRTD's LDS classes so that
 * readers parse exactly what a real passport would return. What the chip holds follows its [ChipConfiguration].
 *
 * EF.CardAccess, which advertises PACE, is present only when the access control allows PACE. It is the only file
 * readable without authentication. With PACE-CAM the chip also has a static key pair, whose public key is in
 * EF.CardSecurity. Both files live in the master file (MF) rather than the eMRTD application; EF.CardSecurity
 * and EF.SOD share file identifier 011D, so which one a SELECT addresses depends on the current DF.
 *
 * EF.DG14 has a copy of the PACEInfo, the Chip Authentication key and, for ECDSA, the Active Authentication
 * algorithm; EF.DG15 the Active Authentication public key. A chip with Terminal Authentication has fingerprints in
 * EF.DG3, which no reader can unlock, and EF.CVCA naming the test CVCA a terminal certificate would have to chain to.
 * EF.DG11 and EF.DG12 hold the additional personal and document details, where the profile has them.
 *
 * EF.SOD and EF.CardSecurity are signed by a test Document Signer in the repository's test-pki directory, issued by
 * the SmartEmu Test CSCA of the profile's country or the generic one: one for passports and one for ID cards and
 * residence permits. Passive authentication succeeds for readers that trust that CSCA, unless
 * [PassportData.chipFault] breaks it on purpose.
 */
class PassportLdsFiles private constructor(
    val configuration: ChipConfiguration,
    private val masterFiles: Map<Short, ByteArray>,
    private val applicationFiles: Map<Short, ByteArray>,
    /** The chip's static key pair for PACE-CAM, or null if the chip does not offer CAM. */
    val chipAuthenticationKeyPair: KeyPair?,
    /** The chip's static key pair for EAC Chip Authentication, whose public key is in EF.DG14, or null without CA. */
    val eacChipAuthenticationKeyPair: KeyPair?,
    /**
     * The private key the chip answers INTERNAL AUTHENTICATE with, or null if it has no Active Authentication. It
     * matches the public key in EF.DG15, unless the chip is a clone.
     */
    private val activeAuthenticationKey: PrivateKey?
) {

    companion object {
        /** The recoverable part of an RSA Active Authentication message is filled to capacity; SHA-1 digests. */
        private const val RSA_AA_DIGEST_LENGTH = 20
        private const val RSA_AA_SIGNATURE_ALGORITHM = "SHA1withRSA/ISO9796-2"

        private val SFI_TO_FID = mapOf(
            PassportService.SFI_CARD_ACCESS.toInt() to PassportService.EF_CARD_ACCESS,
            PassportService.SFI_CARD_SECURITY.toInt() to PassportService.EF_CARD_SECURITY,
            PassportService.SFI_COM.toInt() to PassportService.EF_COM,
            PassportService.SFI_DG1.toInt() to PassportService.EF_DG1,
            PassportService.SFI_DG2.toInt() to PassportService.EF_DG2,
            PassportService.SFI_DG3.toInt() to PassportService.EF_DG3,
            PassportService.SFI_DG11.toInt() to PassportService.EF_DG11,
            PassportService.SFI_DG12.toInt() to PassportService.EF_DG12,
            PassportService.SFI_DG14.toInt() to PassportService.EF_DG14,
            PassportService.SFI_DG15.toInt() to PassportService.EF_DG15,
            PassportService.SFI_SOD.toInt() to PassportService.EF_SOD,
            PassportService.SFI_CVCA.toInt() to PassportService.EF_CVCA
        )

        /** ISO 3166 alpha-2 codes of the profiles' countries, for the CVCA reference; others use the test state UT. */
        private val ALPHA_2 = mapOf("DEU" to "DE", "NLD" to "NL", "GBR" to "GB", "USA" to "US")

        /**
         * 8x8 mid-grey baseline JPEG used as the DG2 portrait placeholder.
         */
        private val PLACEHOLDER_PORTRAIT_JPEG = bytesOf(
            0xFF, 0xD8,                                     // SOI
            0xFF, 0xDB, 0x00, 0x43, 0x00,                   // DQT, table 0, all coefficients 1
            *IntArray(64) { 0x01 },
            0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x08, 0x00, 0x08, 0x01, 0x01, 0x11, 0x00, // SOF0, 8x8, 1 component
            0xFF, 0xC4, 0x00, 0x14, 0x00, 0x01, *IntArray(15), 0x00,  // DHT DC0: one 1-bit code, category 0
            0xFF, 0xC4, 0x00, 0x14, 0x10, 0x01, *IntArray(15), 0x00,  // DHT AC0: one 1-bit code, EOB
            0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, // SOS
            0x3F,                                           // DC diff 0, EOB, padded with 1 bits
            0xFF, 0xD9                                      // EOI
        )

        private const val PLACEHOLDER_PORTRAIT_SIZE = 8

        /**
         * A Document Signer in the test-pki directory: a key and the certificate a test CSCA issued for it, loaded
         * from the resources `<name>.key.pem` and `<name>.cert.pem`.
         */
        class DocumentSigner private constructor(val resourceName: String) {

            private val keyAndCertificate: Pair<PrivateKey, X509Certificate> by lazy {
                loadDocumentSigner("/$resourceName.cert.pem", "/$resourceName.key.pem")
            }

            val privateKey: PrivateKey get() = keyAndCertificate.first
            val certificate: X509Certificate get() = keyAndCertificate.second

            /** The JCA name of the signature algorithm this signer's key makes with [digestAlgorithm]. */
            fun signatureAlgorithm(digestAlgorithm: String): String {
                val digest = digestAlgorithm.replace("-", "")
                return if (privateKey is RSAPrivateKey) "${digest}withRSA" else "${digest}withECDSA"
            }

            companion object {
                private val signers = ConcurrentHashMap<String, DocumentSigner>()

                fun named(resourceName: String): DocumentSigner =
                    signers.getOrPut(resourceName) { DocumentSigner(resourceName) }

                /** Signs generic passports; its document type list is P. */
                val PASSPORT: DocumentSigner get() = named("smartemu-test-ds")

                /** Signs generic ID cards and residence permits; its document type list is ID and IR. */
                val ID_DOCUMENT: DocumentSigner get() = named("smartemu-test-ds-id")

                /** Issued by the SmartEmu Test CSCA, but valid for a day only, long past. */
                val EXPIRED: DocumentSigner get() = named("smartemu-test-ds-expired")

                /** Issued by the SmartEmu Unknown CSCA, which no reader trusts. */
                val UNTRUSTED: DocumentSigner get() = named("smartemu-test-ds-untrusted")
            }
        }

        /**
         * The certificate EF.SOD is signed with for a generic passport with no fault.
         */
        val documentSignerCertificate: X509Certificate get() = DocumentSigner.PASSPORT.certificate

        /**
         * The Document Signer the chip's EF.SOD and EF.CardSecurity are signed by: the profile's, unless a fault
         * calls for an expired or untrusted one.
         */
        fun documentSignerFor(passportData: PassportData): DocumentSigner = when (passportData.chipFault) {
            ChipFault.EXPIRED_SIGNER -> DocumentSigner.EXPIRED
            ChipFault.UNTRUSTED_SIGNER -> DocumentSigner.UNTRUSTED
            else -> {
                val sod = passportData.chipProfile.sod
                DocumentSigner.named(if (passportData.documentType.isCard) sod.cardSigner else sod.passportSigner)
            }
        }

        /**
         * Builds the files of the chip [PassportData.chipConfiguration] describes, with new key pairs for PACE-CAM,
         * Chip Authentication and Active Authentication where it has them. [PassportData.chipFault] spoils EF.SOD or
         * the Active Authentication key as it describes.
         */
        fun create(passportData: PassportData): PassportLdsFiles {
            val configuration = passportData.chipConfiguration()
            val fault = passportData.chipFault
            val paceInfo = if (configuration.supportsPace) {
                PaceProtocol.paceInfo(configuration.paceMapping, configuration.pace)
            } else {
                null
            }

            var activeAuthenticationKey: PrivateKey? = null
            var activeAuthenticationPublicKey: java.security.PublicKey? = null
            configuration.activeAuthentication?.let { spec ->
                val keyPair = generateKeyPair(spec)
                activeAuthenticationPublicKey = keyPair.public
                // A clone has a copy of DG15, but not the private key that goes with it
                activeAuthenticationKey = if (fault == ChipFault.CLONED_CHIP) generateKeyPair(spec).private else keyPair.private
            }

            val eacKeyPair = configuration.chipAuthentication?.let { generateEcKeyPair(it.curve) }

            val dataGroups = sortedMapOf<Int, ByteArray>()
            for (number in configuration.dataGroups) {
                dataGroups[number] = when (number) {
                    1 -> DG1File(createMrzInfo(passportData)).encoded
                    2 -> DG2File(listOf(FaceInfo(listOf(createFaceImageInfo(passportData))))).encoded
                    3 -> createFingerprints()
                    11 -> createAdditionalPersonalDetails(passportData)
                    12 -> createAdditionalDocumentDetails(passportData)
                    14 -> DG14File(
                        buildList {
                            paceInfo?.let(::add)
                            configuration.chipAuthentication?.let { spec ->
                                add(ChipAuthenticationInfo(chipAuthenticationOid(spec.cipher), ChipAuthenticationInfo.VERSION_1))
                                add(ChipAuthenticationPublicKeyInfo(SecurityInfo.ID_PK_ECDH, eacKeyPair!!.public))
                            }
                            if (configuration.terminalAuthentication) add(TerminalAuthenticationInfo())
                            (configuration.activeAuthentication as? KeySpec.Ec)?.let {
                                add(ActiveAuthenticationInfo(activeAuthenticationOid(it.curve)))
                            }
                        }
                    ).encoded
                    15 -> DG15File(activeAuthenticationPublicKey).encoded
                    else -> throw IllegalArgumentException("No content for DG$number")
                }
            }
            val com = COMFile("1.7", "4.0.0", dataGroups.keys.map(::dataGroupTag).toIntArray()).encoded

            val digestAlgorithm = configuration.sod.digestAlgorithm
            val digest = MessageDigest.getInstance(digestAlgorithm)
            val dataGroupHashes = dataGroups.mapValues { (number, content) ->
                // Hashed as they were before being tampered with, so the chip's copy no longer matches
                val signed = when {
                    number == 1 && fault == ChipFault.ALTERED_DETAILS -> tampered(content)
                    number == 2 && fault == ChipFault.SWAPPED_PHOTO -> tampered(content)
                    else -> content
                }
                digest.digest(signed)
            }
            val signer = documentSignerFor(passportData)
            val certificate = signer.certificate
            val signatureAlgorithm = signer.signatureAlgorithm(digestAlgorithm)
            // A key other than the certificate's, of the same kind, so the signature doesn't verify
            val privateKey = if (fault == ChipFault.BROKEN_SIGNATURE) otherKeyLike(signer.privateKey) else signer.privateKey
            val sod = SODFile(digestAlgorithm, signatureAlgorithm, dataGroupHashes, privateKey, certificate).encoded

            val applicationFiles = buildMap {
                put(PassportService.EF_COM, com)
                dataGroups.forEach { (number, content) -> put(dataGroupFid(number), content) }
                put(PassportService.EF_SOD, sod)
                if (configuration.terminalAuthentication) {
                    put(PassportService.EF_CVCA, CVCAFile(PassportService.EF_CVCA, cvcaReference(configuration)).encoded)
                }
            }
            val masterFiles = mutableMapOf<Short, ByteArray>()
            var chipAuthenticationKeyPair: KeyPair? = null
            if (paceInfo != null) {
                masterFiles[PassportService.EF_CARD_ACCESS] = CardAccessFile(listOf(paceInfo)).encoded

                if (configuration.paceMapping == PaceMapping.CHIP_AUTHENTICATION) {
                    val keyPair = PaceProtocol.generateChipAuthenticationKeyPair(configuration.pace.curve)
                    val securityInfos = listOf<SecurityInfo>(
                        paceInfo,
                        ChipAuthenticationPublicKeyInfo(SecurityInfo.ID_PK_ECDH, keyPair.public)
                    )
                    masterFiles[PassportService.EF_CARD_SECURITY] = CardSecurityFile(
                        digestAlgorithm, signatureAlgorithm, securityInfos, privateKey, certificate
                    ).encoded
                    chipAuthenticationKeyPair = keyPair
                }
            }
            return PassportLdsFiles(
                configuration, masterFiles, applicationFiles, chipAuthenticationKeyPair, eacKeyPair, activeAuthenticationKey
            )
        }

        /**
         * The DG1 MRZ, built from the same MRZ fields [PassportData.toMrzData] uses: TD3 for a passport, TD1 for a
         * card, with the issuer's document code, country codes and personal number.
         */
        fun createMrzInfo(passportData: PassportData): MRZInfo {
            val documentType = passportData.documentType
            val issuingState = PassportData.mrzCountryCode(passportData.issuingCountry).trimEnd('<')
            val nationality = PassportData.mrzCountryCode(passportData.nationality).trimEnd('<')
            return if (documentType.isCard) {
                MRZInfo.createTD1MRZInfo(
                    passportData.mrzDocumentCodeValue(),
                    issuingState,
                    passportData.passportNumber.uppercase(),
                    passportData.mrzPersonalNumber(),
                    passportData.mrzDateOfBirth(),
                    toGender(passportData),
                    passportData.mrzExpiryDate(),
                    nationality,
                    "",
                    passportData.mrzPrimaryIdentifier(),
                    passportData.mrzSecondaryIdentifier()
                )
            } else {
                MRZInfo.createTD3MRZInfo(
                    passportData.mrzDocumentCodeValue(),
                    issuingState,
                    passportData.mrzPrimaryIdentifier(),
                    passportData.mrzSecondaryIdentifier(),
                    passportData.passportNumber,
                    nationality,
                    passportData.mrzDateOfBirth(),
                    toGender(passportData),
                    passportData.mrzExpiryDate(),
                    passportData.mrzPersonalNumber()
                )
            }
        }

        /** A key pair of the given kind, for Active Authentication. */
        private fun generateKeyPair(spec: KeySpec): KeyPair = when (spec) {
            is KeySpec.Ec -> generateEcKeyPair(spec.curve)
            is KeySpec.Rsa -> Util.getKeyPairGenerator("RSA").apply { initialize(spec.bits, SecureRandom()) }.generateKeyPair()
        }

        /** A key pair on a named curve, so that EF.DG14 and EF.DG15 name the curve rather than spell it out. */
        internal fun generateEcKeyPair(curve: EcCurve): KeyPair {
            val keyPairGenerator = Util.getKeyPairGenerator("EC")
            keyPairGenerator.initialize(ECGenParameterSpec(curve.jcaName), SecureRandom())
            return keyPairGenerator.generateKeyPair()
        }

        /** A private key of the same kind and size as [key], to sign with where a signature mustn't verify. */
        private fun otherKeyLike(key: PrivateKey): PrivateKey = when (key) {
            is RSAPrivateKey -> generateKeyPair(KeySpec.Rsa(key.modulus.bitLength())).private
            is ECPrivateKey -> Util.getKeyPairGenerator("EC").apply { initialize(key.params, SecureRandom()) }
                .generateKeyPair().private
            else -> throw IllegalArgumentException("Unsupported key ${key.algorithm}")
        }

        /** The EAC Chip Authentication protocol for a cipher, ECDH as all the profiles have it. */
        fun chipAuthenticationOid(cipher: SessionCipher): String = when (cipher) {
            SessionCipher.TDES -> SecurityInfo.ID_CA_ECDH_3DES_CBC_CBC
            SessionCipher.AES_128 -> SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128
            SessionCipher.AES_192 -> SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_192
            SessionCipher.AES_256 -> SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256
        }

        /** The plain ECDSA signature algorithm Active Authentication uses on a curve, BSI TR-03111. */
        private fun activeAuthenticationOid(curve: EcCurve): String = when (curve.digestAlgorithm) {
            "SHA-256" -> ActiveAuthenticationInfo.ECDSA_PLAIN_SHA256_OID
            "SHA-384" -> ActiveAuthenticationInfo.ECDSA_PLAIN_SHA384_OID
            else -> ActiveAuthenticationInfo.ECDSA_PLAIN_SHA512_OID
        }

        /**
         * The reference of the test CVCA the chip trusts for Terminal Authentication, BSI TR-03110 part 3: the
         * country, a holder mnemonic and a sequence number.
         */
        private fun cvcaReference(configuration: ChipConfiguration): String =
            "${ALPHA_2[configuration.profile.country] ?: "UT"}TESTCVCA00001"

        /**
         * DG3 with no fingerprint records but random data in their place, as JMRTD fills an empty one: no reader
         * gets past Terminal Authentication to see it.
         */
        @Suppress("DEPRECATION")
        private fun createFingerprints(): ByteArray = DG3File(emptyList<FingerInfo>(), true).encoded

        /** DG11: the full name, and the personal number, date and place of birth where there are ones. */
        private fun createAdditionalPersonalDetails(passportData: PassportData): ByteArray = DG11File(
            passportData.fullNameOfHolder(),
            null,
            passportData.personalNumber.ifBlank { null },
            passportData.dateOfBirth?.let(::toLdsDate),
            passportData.placeOfBirth.ifBlank { null }?.let(::listOf),
            null, null, null, null, null, null, null, null
        ).encoded

        /** DG12: the issuing authority and date of issue, where there are ones. */
        private fun createAdditionalDocumentDetails(passportData: PassportData): ByteArray = DG12File(
            passportData.issuingAuthority.ifBlank { null },
            passportData.dateOfIssue?.let(::toLdsDate),
            null, null, null, null, null, null as String?, null
        ).encoded

        /** A date as the LDS has it in DG11 and DG12, YYYYMMDD. */
        private fun toLdsDate(date: kotlinx.datetime.LocalDate): String = date.toString().replace("-", "")

        /** [content] with its last byte changed, standing in for the data group before it was tampered with. */
        private fun tampered(content: ByteArray): ByteArray =
            content.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }

        private fun dataGroupTag(number: Int): Int = when (number) {
            1 -> LDSFile.EF_DG1_TAG
            2 -> LDSFile.EF_DG2_TAG
            3 -> LDSFile.EF_DG3_TAG
            11 -> LDSFile.EF_DG11_TAG
            12 -> LDSFile.EF_DG12_TAG
            14 -> LDSFile.EF_DG14_TAG
            15 -> LDSFile.EF_DG15_TAG
            else -> throw IllegalArgumentException("No tag for DG$number")
        }

        private fun dataGroupFid(number: Int): Short = when (number) {
            1 -> PassportService.EF_DG1
            2 -> PassportService.EF_DG2
            3 -> PassportService.EF_DG3
            11 -> PassportService.EF_DG11
            12 -> PassportService.EF_DG12
            14 -> PassportService.EF_DG14
            15 -> PassportService.EF_DG15
            else -> throw IllegalArgumentException("No file identifier for DG$number")
        }

        /**
         * An ECDSA signature in the DER form Java makes, as the plain concatenation of r and s, each [fieldSize]
         * bytes, that ICAO 9303-11 section 6.1 asks Active Authentication for (BSI TR-03111).
         */
        internal fun derToPlainSignature(der: ByteArray, fieldSize: Int = EcCurve.NIST_P256.fieldSize): ByteArray {
            // SEQUENCE { INTEGER r, INTEGER s }, each INTEGER possibly with a leading zero byte
            var offset = 2 + if ((der[1].toInt() and 0x80) != 0) der[1].toInt() and 0x7F else 0
            val plain = ByteArray(2 * fieldSize)
            repeat(2) { index ->
                val length = der[offset + 1].toInt() and 0xFF
                val value = der.copyOfRange(offset + 2, offset + 2 + length).dropWhile { it == 0.toByte() }.toByteArray()
                value.copyInto(plain, destinationOffset = (index + 1) * fieldSize - value.size)
                offset += 2 + length
            }
            return plain
        }

        /**
         * The holder's portrait, in colour, or the grey placeholder if there is none.
         */
        private fun createFaceImageInfo(passportData: PassportData): FaceImageInfo {
            val portrait = passportData.portrait
            val jpeg = portrait?.jpeg ?: PLACEHOLDER_PORTRAIT_JPEG
            return FaceImageInfo(
                toGender(passportData),
                FaceImageInfo.EyeColor.UNSPECIFIED,
                0,
                FaceImageInfo.HAIR_COLOR_UNSPECIFIED,
                FaceImageInfo.EXPRESSION_UNSPECIFIED.toInt(),
                intArrayOf(0, 0, 0),
                intArrayOf(0, 0, 0),
                FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL,
                if (portrait != null) FaceImageInfo.IMAGE_COLOR_SPACE_RGB24 else FaceImageInfo.IMAGE_COLOR_SPACE_GRAY8,
                if (portrait != null) FaceImageInfo.SOURCE_TYPE_STATIC_PHOTO_UNKNOWN_SOURCE else FaceImageInfo.SOURCE_TYPE_UNSPECIFIED,
                0,
                0,
                emptyArray(),
                portrait?.width ?: PLACEHOLDER_PORTRAIT_SIZE,
                portrait?.height ?: PLACEHOLDER_PORTRAIT_SIZE,
                ByteArrayInputStream(jpeg),
                jpeg.size,
                FaceImageInfo.IMAGE_DATA_TYPE_JPEG
            )
        }

        private fun toGender(passportData: PassportData): Gender = when (passportData.mrzGender()) {
            'M' -> Gender.MALE
            'F' -> Gender.FEMALE
            else -> Gender.UNSPECIFIED
        }

        /**
         * Loads a Document Signer with BouncyCastle, which knows the Brainpool curves some of them are on.
         */
        private fun loadDocumentSigner(certificateName: String, keyName: String): Pair<PrivateKey, X509Certificate> {
            val provider = Util.getBouncyCastleProvider()
            val certificate = CertificateFactory.getInstance("X.509", provider)
                .generateCertificate(ByteArrayInputStream(readResource(certificateName))) as X509Certificate
            val keyDer = Base64.getMimeDecoder().decode(
                String(readResource(keyName), Charsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            )
            val keyAlgorithm = if (certificate.publicKey.algorithm == "RSA") "RSA" else "EC"
            val privateKey = KeyFactory.getInstance(keyAlgorithm, provider).generatePrivate(PKCS8EncodedKeySpec(keyDer))
            return privateKey to certificate
        }

        private fun readResource(name: String): ByteArray =
            PassportLdsFiles::class.java.getResourceAsStream(name)?.use { it.readBytes() }
                ?: throw IllegalStateException("Missing resource $name")

        private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }

    /**
     * Contents of the eMRTD application's file with the given file identifier, or null if there is none.
     */
    fun applicationFile(fid: Short): ByteArray? = applicationFiles[fid]

    /**
     * Contents of the master file's file with the given file identifier, or null if there is none.
     */
    fun masterFile(fid: Short): ByteArray? = masterFiles[fid]

    /** Whether the chip has an Active Authentication key pair. */
    val hasActiveAuthentication: Boolean get() = activeAuthenticationKey != null

    /**
     * Answers an Active Authentication challenge, ICAO 9303-11 section 6.1, or null if the chip has no Active
     * Authentication:
     * - ECDSA: the signature of [challenge] with the curve's SHA-2 digest, in the plain format JMRTD's AAProtocol
     *   expects;
     * - RSA: an ISO/IEC 9796-2 scheme 1 signature with SHA-1 of a random nonce M1, as long as the key leaves room to
     *   recover, followed by [challenge].
     */
    fun signActiveAuthenticationChallenge(challenge: ByteArray): ByteArray? {
        val key = activeAuthenticationKey ?: return null
        return when (val spec = configuration.activeAuthentication) {
            is KeySpec.Ec -> {
                val algorithm = "${spec.curve.digestAlgorithm.replace("-", "")}withECDSA"
                val signature = Util.getSignature(algorithm).apply {
                    initSign(key)
                    update(challenge)
                }
                derToPlainSignature(signature.sign(), spec.curve.fieldSize)
            }
            is KeySpec.Rsa -> {
                val keyBytes = ((key as RSAPrivateKey).modulus.bitLength() + 7) / 8
                // The header byte, the digest and the trailer byte leave the rest of the block for M1
                val nonce = ByteArray(keyBytes - RSA_AA_DIGEST_LENGTH - 2).also { SecureRandom().nextBytes(it) }
                Signature.getInstance(RSA_AA_SIGNATURE_ALGORITHM, Util.getBouncyCastleProvider()).run {
                    initSign(key)
                    update(nonce)
                    update(challenge)
                    sign()
                }
            }
            null -> null
        }
    }

    /**
     * The file a SELECT by file identifier addresses: a file of the eMRTD application once it is selected, and of
     * the MF before. EF.CardAccess stays selectable from the application, since its identifier is unambiguous.
     */
    fun fileById(fid: Short, isApplicationSelected: Boolean): ByteArray? =
        if (isApplicationSelected) applicationFiles[fid] ?: masterFiles[fid] else masterFiles[fid]

    /**
     * Name of the file [fileById] resolves to, for the event log.
     */
    fun fileName(fid: Short, isApplicationSelected: Boolean): String = when {
        fid == PassportService.EF_CARD_ACCESS -> "EF.CardAccess"
        !isApplicationSelected && fid == PassportService.EF_CARD_SECURITY -> "EF.CardSecurity"
        fid == PassportService.EF_COM -> "EF.COM"
        fid == PassportService.EF_SOD -> "EF.SOD"
        fid == PassportService.EF_CVCA -> "EF.CVCA"
        fid in PassportService.EF_DG1..PassportService.EF_DG16 -> "EF.DG${fid - PassportService.EF_DG1 + 1}"
        else -> "EF %04X".format(fid)
    }

    /**
     * Whether the file can be read without secure messaging. Only EF.CardAccess can; ICAO 9303-11 protects
     * EF.CardSecurity with PACE.
     */
    fun isPublic(fid: Short): Boolean = fid == PassportService.EF_CARD_ACCESS

    /** The reference of the test CVCA the chip trusts for Terminal Authentication, as in its EF.CVCA. */
    val cvcaReference: String get() = cvcaReference(configuration)

    /**
     * Whether reading the file needs Terminal Authentication as well as secure messaging: the fingerprints, DG3.
     */
    fun needsTerminalAuthentication(fid: Short): Boolean = fid == PassportService.EF_DG3

    /**
     * Resolves a short file identifier (READ BINARY with P1 bit 8 set) to the file identifier of a file
     * [fileById] finds.
     */
    fun fidForSfi(sfi: Int, isApplicationSelected: Boolean): Short? =
        SFI_TO_FID[sfi]?.takeIf { fileById(it, isApplicationSelected) != null }
}

/** The curve's name for Java's and BouncyCastle's key pair generators. */
val EcCurve.jcaName: String
    get() = when (this) {
        EcCurve.NIST_P256 -> "secp256r1"
        EcCurve.NIST_P384 -> "secp384r1"
        EcCurve.NIST_P521 -> "secp521r1"
        else -> displayName
    }
