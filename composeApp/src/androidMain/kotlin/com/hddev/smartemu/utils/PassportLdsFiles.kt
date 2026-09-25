package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import net.sf.scuba.data.Gender
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.LDSFile
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The elementary files of the emulated eMRTD application (LDS1), encoded with JMRTD's LDS classes so that
 * readers parse exactly what a real passport would return.
 *
 * EF.CardAccess, which advertises PACE, is present only when the access control allows PACE. It is the only file
 * readable without authentication. With PACE-CAM the chip also has a static key pair, whose public key is in
 * EF.CardSecurity. Both files live in the master file (MF) rather than the eMRTD application; EF.CardSecurity
 * and EF.SOD share file identifier 011D, so which one a SELECT addresses depends on the current DF.
 *
 * EF.SOD and EF.CardSecurity are signed by the test Document Signer in the repository's test-pki directory,
 * issued by the SmartEmu Test CSCA. Passive authentication succeeds for readers that trust that CSCA.
 */
class PassportLdsFiles private constructor(
    private val masterFiles: Map<Short, ByteArray>,
    private val applicationFiles: Map<Short, ByteArray>,
    /** The chip's static key pair for PACE-CAM, or null if the chip does not offer CAM. */
    val chipAuthenticationKeyPair: KeyPair?
) {

    companion object {
        private const val DIGEST_ALGORITHM = "SHA-256"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val DOCUMENT_SIGNER_CERTIFICATE = "/smartemu-test-ds.cert.pem"
        private const val DOCUMENT_SIGNER_KEY = "/smartemu-test-ds.key.pem"

        private val SFI_TO_FID = mapOf(
            PassportService.SFI_CARD_ACCESS.toInt() to PassportService.EF_CARD_ACCESS,
            PassportService.SFI_CARD_SECURITY.toInt() to PassportService.EF_CARD_SECURITY,
            PassportService.SFI_COM.toInt() to PassportService.EF_COM,
            PassportService.SFI_DG1.toInt() to PassportService.EF_DG1,
            PassportService.SFI_DG2.toInt() to PassportService.EF_DG2,
            PassportService.SFI_SOD.toInt() to PassportService.EF_SOD
        )

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

        private val documentSigner: Pair<PrivateKey, X509Certificate> by lazy { loadDocumentSigner() }

        /**
         * The certificate EF.SOD is signed with.
         */
        val documentSignerCertificate: X509Certificate get() = documentSigner.second

        /**
         * Builds EF.COM, EF.DG1, EF.DG2 and EF.SOD for the given passport data, EF.CardAccess if it allows PACE,
         * and EF.CardSecurity with a new chip key pair if the PACE mapping is CAM.
         */
        fun create(passportData: PassportData): PassportLdsFiles {
            val dg1 = DG1File(createMrzInfo(passportData)).encoded
            val dg2 = DG2File(listOf(FaceInfo(listOf(createFaceImageInfo(passportData))))).encoded
            val com = COMFile("1.7", "4.0.0", intArrayOf(LDSFile.EF_DG1_TAG, LDSFile.EF_DG2_TAG)).encoded

            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            val dataGroupHashes = mapOf(1 to digest.digest(dg1), 2 to digest.digest(dg2))
            val (privateKey, certificate) = documentSigner
            val sod = SODFile(DIGEST_ALGORITHM, SIGNATURE_ALGORITHM, dataGroupHashes, privateKey, certificate).encoded

            val applicationFiles = mapOf(
                PassportService.EF_COM to com,
                PassportService.EF_DG1 to dg1,
                PassportService.EF_DG2 to dg2,
                PassportService.EF_SOD to sod
            )
            val masterFiles = mutableMapOf<Short, ByteArray>()
            var chipAuthenticationKeyPair: KeyPair? = null
            if (passportData.accessControl.supportsPace) {
                val paceInfo = PaceProtocol.paceInfo(passportData.paceMapping)
                masterFiles[PassportService.EF_CARD_ACCESS] = CardAccessFile(listOf(paceInfo)).encoded

                if (passportData.paceMapping == PaceMapping.CHIP_AUTHENTICATION) {
                    val keyPair = PaceProtocol.generateChipAuthenticationKeyPair()
                    val securityInfos = listOf<SecurityInfo>(
                        paceInfo,
                        ChipAuthenticationPublicKeyInfo(SecurityInfo.ID_PK_ECDH, keyPair.public)
                    )
                    masterFiles[PassportService.EF_CARD_SECURITY] = CardSecurityFile(
                        DIGEST_ALGORITHM, SIGNATURE_ALGORITHM, securityInfos, privateKey, certificate
                    ).encoded
                    chipAuthenticationKeyPair = keyPair
                }
            }
            return PassportLdsFiles(masterFiles, applicationFiles, chipAuthenticationKeyPair)
        }

        /**
         * The DG1 MRZ, built from the same MRZ fields [PassportData.toMrzData] uses.
         */
        fun createMrzInfo(passportData: PassportData): MRZInfo {
            return MRZInfo.createTD3MRZInfo(
                "P",
                passportData.issuingCountry,
                passportData.mrzPrimaryIdentifier(),
                passportData.mrzSecondaryIdentifier(),
                passportData.passportNumber,
                passportData.nationality,
                passportData.mrzDateOfBirth(),
                toGender(passportData),
                passportData.mrzExpiryDate(),
                ""
            )
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
        
        private fun loadDocumentSigner(): Pair<PrivateKey, X509Certificate> {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(readResource(DOCUMENT_SIGNER_CERTIFICATE))) as X509Certificate
            val keyDer = Base64.getMimeDecoder().decode(
                String(readResource(DOCUMENT_SIGNER_KEY), Charsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            )
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyDer))
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
        fid == PassportService.EF_DG1 -> "EF.DG1"
        fid == PassportService.EF_DG2 -> "EF.DG2"
        else -> "EF %04X".format(fid)
    }

    /**
     * Whether the file can be read without secure messaging. Only EF.CardAccess can; ICAO 9303-11 protects
     * EF.CardSecurity with PACE.
     */
    fun isPublic(fid: Short): Boolean = fid == PassportService.EF_CARD_ACCESS

    /**
     * Resolves a short file identifier (READ BINARY with P1 bit 8 set) to the file identifier of a file
     * [fileById] finds.
     */
    fun fidForSfi(sfi: Int, isApplicationSelected: Boolean): Short? =
        SFI_TO_FID[sfi]?.takeIf { fileById(it, isApplicationSelected) != null }
}
