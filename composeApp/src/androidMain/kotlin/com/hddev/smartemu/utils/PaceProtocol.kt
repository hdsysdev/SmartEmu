package com.hddev.smartemu.utils

import android.util.Log
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import net.sf.scuba.tlv.TLVUtil
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.PACEInfo
import org.jmrtd.protocol.PACEGMWithECDHAgreement
import org.jmrtd.protocol.PACEProtocol
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Chip side of PACE (Password Authenticated Connection Establishment), ICAO 9303 part 11 section 4.4, with the
 * MRZ or the CAN as password. Mirrors the reader side in JMRTD's PACEProtocol and reuses its key derivation,
 * nonce mapping and authentication token helpers. The chip offers one protocol, ECDH on NIST P-256 with
 * AES-128 and the mapping chosen by [PassportData.paceMapping], as advertised in EF.CardAccess by [paceInfo]:
 * - Generic Mapping (GM): G~ = s * G + H, with H from an ECDH exchange of mapping keys
 * - Chip Authentication Mapping (CAM): GM, and the chip proves it holds the private key for the public key
 *   in EF.CardSecurity by sending CA = SK_map * SK_chip^-1 mod n, encrypted, with its authentication token
 *
 * The reader selects the protocol with MSE:Set AT, then runs four GENERAL AUTHENTICATE steps:
 * encrypted nonce, nonce mapping, ephemeral key exchange and mutual authentication tokens.
 */
class PaceProtocol {

    companion object {
        private const val TAG = "PaceProtocol"

        /** The r2w nfc-library reader only supports the NIST curves, not Brainpool. */
        const val PARAMETER_ID = PACEInfo.PARAM_ID_ECP_NIST_P256_R1
        private const val PACE_VERSION = 2
        private const val CIPHER_ALGORITHM = "AES"
        private const val KEY_LENGTH = 128
        private const val NONCE_LENGTH = 16

        private const val TAG_CRYPTOGRAPHIC_MECHANISM = 0x80
        private const val TAG_PASSWORD_REFERENCE = 0x83
        private const val TAG_DOMAIN_PARAMETER_ID = 0x84
        private const val TAG_DYNAMIC_AUTHENTICATION_DATA = 0x7C
        private const val TAG_ENCRYPTED_NONCE = 0x80
        private const val TAG_MAPPING_DATA_PCD = 0x81
        private const val TAG_MAPPING_DATA_PICC = 0x82
        private const val TAG_EPHEMERAL_PUBLIC_KEY_PCD = 0x83
        private const val TAG_EPHEMERAL_PUBLIC_KEY_PICC = 0x84
        private const val TAG_AUTHENTICATION_TOKEN_PCD = 0x85
        private const val TAG_AUTHENTICATION_TOKEN_PICC = 0x86
        private const val TAG_ENCRYPTED_CHIP_AUTHENTICATION_DATA = 0x8A

        /** Plain block encrypted with KS_enc to get the IV for the CAM chip authentication data, all bits 1. */
        private val MINUS_ONE_BLOCK = ByteArray(16) { 0xFF.toByte() }

        /** MSE:Set AT errors, ISO 7816-4. */
        const val SW_WRONG_DATA = 0x6A80
        const val SW_REFERENCED_DATA_NOT_FOUND = 0x6A88
        /** GENERAL AUTHENTICATE out of sequence. */
        const val SW_CONDITIONS_NOT_SATISFIED = 0x6985
        /** Authentication token of the reader does not verify: wrong password. */
        const val SW_AUTHENTICATION_FAILED = 0x6300

        /**
         * The PACE protocol identifier for a mapping.
         */
        fun oid(mapping: PaceMapping): String = when (mapping) {
            PaceMapping.GENERIC -> PACEInfo.ID_PACE_ECDH_GM_AES_CBC_CMAC_128
            PaceMapping.CHIP_AUTHENTICATION -> PACEInfo.ID_PACE_ECDH_CAM_AES_CBC_CMAC_128
        }

        /**
         * The PACEInfo published in EF.CardAccess.
         */
        fun paceInfo(mapping: PaceMapping): PACEInfo = PACEInfo(oid(mapping), PACE_VERSION, PARAMETER_ID)

        /**
         * A static chip key pair for PACE-CAM. It must be on the PACE domain parameters; the public key goes in
         * EF.CardSecurity.
         */
        fun generateChipAuthenticationKeyPair(): KeyPair {
            val keyPairGenerator = Util.getKeyPairGenerator("EC")
            keyPairGenerator.initialize(PACEInfo.toParameterSpec(PARAMETER_ID), SecureRandom())
            return keyPairGenerator.generateKeyPair()
        }
    }

    /**
     * PACE protocol states, one per GENERAL AUTHENTICATE step.
     */
    enum class PaceState {
        INITIAL,
        KEY_SELECTED,
        NONCE_SENT,
        NONCE_MAPPED,
        KEYS_AGREED,
        AUTHENTICATED,
        FAILED
    }

    /**
     * Result of PACE operations; [statusWord] is the SW the chip answers with.
     */
    data class PaceResult(
        val success: Boolean,
        val message: String,
        val data: ByteArray? = null,
        val statusWord: Int = if (success) 0x9000 else SW_CONDITIONS_NOT_SATISFIED,
        val newState: PaceState = PaceState.INITIAL
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PaceResult) return false

            if (success != other.success) return false
            if (message != other.message) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            if (statusWord != other.statusWord) return false
            if (newState != other.newState) return false

            return true
        }

        override fun hashCode(): Int {
            var result = success.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + statusWord
            result = 31 * result + newState.hashCode()
            return result
        }
    }

    /**
     * A GENERAL AUTHENTICATE step was rejected; the chip answers with [statusWord] and the PACE run is over.
     */
    private class PaceStepException(message: String, val statusWord: Int) : Exception(message)

    private val secureRandom = SecureRandom()
    private val staticParameters = PACEInfo.toParameterSpec(PARAMETER_ID) as ECParameterSpec

    private var currentState = PaceState.INITIAL
    private var mapping = PaceMapping.GENERIC
    private var mrzPasswordKey: SecretKey? = null
    private var canPasswordKey: SecretKey? = null
    private var chipAuthenticationKeyPair: KeyPair? = null

    /** Protocol identifier of the mapping the chip offers. */
    val oid: String get() = oid(mapping)

    // Per-run values, discarded by reset()
    private var passwordKey: SecretKey? = null
    private var nonce: ByteArray? = null
    private var chipMappingKeyPair: KeyPair? = null
    private var ephemeralParameters: ECParameterSpec? = null
    private var chipEphemeralKeyPair: KeyPair? = null
    private var readerEphemeralPublicKey: PublicKey? = null
    private var ksEnc: SecretKey? = null
    private var ksMac: SecretKey? = null
    private var secureMessaging: ChipSecureMessaging? = null

    /**
     * Initializes PACE with passport data, deriving the password keys K_pi from the MRZ and the CAN.
     * PACE-CAM also needs the static [chipAuthenticationKeyPair] whose public key is in EF.CardSecurity.
     */
    fun initialize(passportData: PassportData, chipAuthenticationKeyPair: KeyPair? = null): PaceResult {
        return try {
            if (!passportData.isValid()) {
                Log.e(TAG, "Invalid passport data provided for PACE initialization")
                currentState = PaceState.FAILED
                PaceResult(false, "Invalid passport data", newState = PaceState.FAILED)
            } else if (passportData.paceMapping == PaceMapping.CHIP_AUTHENTICATION && chipAuthenticationKeyPair == null) {
                Log.e(TAG, "PACE-CAM needs a chip authentication key pair")
                currentState = PaceState.FAILED
                PaceResult(false, "Missing chip authentication key for PACE-CAM", newState = PaceState.FAILED)
            } else {
                mapping = passportData.paceMapping
                this.chipAuthenticationKeyPair = chipAuthenticationKeyPair
                val mrzKey = BACKey(passportData.passportNumber, passportData.mrzDateOfBirth(), passportData.mrzExpiryDate())
                mrzPasswordKey = PACEProtocol.deriveStaticPACEKey(mrzKey, oid)
                canPasswordKey = if (passportData.hasCan()) {
                    PACEProtocol.deriveStaticPACEKey(PACEKeySpec.createCANKey(passportData.can), oid)
                } else {
                    null
                }
                reset()
                Log.d(TAG, "PACE-${mapping.abbreviation} initialized")
                PaceResult(true, "PACE initialized", newState = PaceState.INITIAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PACE protocol", e)
            currentState = PaceState.FAILED
            PaceResult(false, "PACE initialization failed: ${e.message}", newState = PaceState.FAILED)
        }
    }

    /**
     * Processes MSE:Set AT (P1-P2 C1 A4): the cryptographic mechanism (DO'80), password reference (DO'83) and
     * optional domain parameter identifier (DO'84). Starts a new PACE run.
     */
    fun processMseSetAt(data: ByteArray): PaceResult {
        if (mrzPasswordKey == null) {
            return PaceResult(false, "PACE not initialized", newState = currentState)
        }
        reset()

        val dataObjects = try {
            parseDataObjects(data)
        } catch (e: IllegalArgumentException) {
            return fail("Malformed MSE:Set AT data: ${e.message}", SW_WRONG_DATA)
        }

        val mechanism = dataObjects[TAG_CRYPTOGRAPHIC_MECHANISM]
        if (mechanism == null || !TLVUtil.wrapDO(TAG_CRYPTOGRAPHIC_MECHANISM, mechanism).contentEquals(Util.toOIDBytes(oid))) {
            return fail("Unsupported PACE protocol", SW_WRONG_DATA)
        }

        // The MRZ is always a password; the CAN only if one is printed on the document. There is no PIN or PUK.
        val passwordReference = dataObjects[TAG_PASSWORD_REFERENCE]?.singleOrNull()
        val (selectedKey, passwordName) = when (passwordReference) {
            PassportService.MRZ_PACE_KEY_REFERENCE -> mrzPasswordKey to "MRZ"
            PassportService.CAN_PACE_KEY_REFERENCE -> canPasswordKey to "CAN"
            else -> null to null
        }
        if (selectedKey == null) {
            return fail("Unsupported PACE password", SW_REFERENCED_DATA_NOT_FOUND)
        }

        val domainParameterId = dataObjects[TAG_DOMAIN_PARAMETER_ID]
        if (domainParameterId != null && Util.os2i(domainParameterId).toInt() != PARAMETER_ID) {
            return fail("Unsupported PACE domain parameters", SW_REFERENCED_DATA_NOT_FOUND)
        }

        passwordKey = selectedKey
        currentState = PaceState.KEY_SELECTED
        val message = "PACE-${mapping.abbreviation} selected with the $passwordName"
        Log.d(TAG, message)
        return PaceResult(true, message, newState = currentState)
    }

    /**
     * Processes one GENERAL AUTHENTICATE step. [data] is the command data, a dynamic authentication data object
     * (tag 7C); on success the result data is the chip's dynamic authentication data object.
     */
    fun processGeneralAuthenticate(data: ByteArray): PaceResult {
        val step = currentState
        return try {
            val content = parseDataObjects(data)[TAG_DYNAMIC_AUTHENTICATION_DATA]
                ?: throw PaceStepException("Missing dynamic authentication data", SW_WRONG_DATA)
            val dataObjects = parseDataObjects(content)

            val response = when (step) {
                PaceState.KEY_SELECTED -> sendEncryptedNonce(dataObjects)
                PaceState.NONCE_SENT -> mapNonceGeneric(dataObjects)
                PaceState.NONCE_MAPPED -> exchangeEphemeralKeys(dataObjects)
                PaceState.KEYS_AGREED -> exchangeAuthenticationTokens(dataObjects)
                else -> throw PaceStepException("GENERAL AUTHENTICATE out of sequence in state $step", SW_CONDITIONS_NOT_SATISFIED)
            }

            PaceResult(
                success = true,
                message = "PACE step ${step.ordinal} completed",
                data = TLVUtil.wrapDO(TAG_DYNAMIC_AUTHENTICATION_DATA, response),
                newState = currentState
            )
        } catch (e: PaceStepException) {
            fail(e.message ?: "PACE failed", e.statusWord)
        } catch (e: IllegalArgumentException) {
            fail("Malformed GENERAL AUTHENTICATE data: ${e.message}", SW_WRONG_DATA)
        } catch (e: Exception) {
            Log.e(TAG, "PACE GENERAL AUTHENTICATE failed in state $step", e)
            fail("PACE failed: ${e.message}", SW_WRONG_DATA)
        }
    }

    /**
     * Gets the current PACE protocol state.
     */
    fun getCurrentState(): PaceState = currentState

    /**
     * Secure messaging session established by the last successful PACE run, if any.
     */
    fun getSecureMessaging(): ChipSecureMessaging? = secureMessaging

    /**
     * The mapping the chip offers.
     */
    fun getMapping(): PaceMapping = mapping

    /**
     * Resets PACE to its initial state, keeping the password keys.
     */
    fun reset() {
        currentState = PaceState.INITIAL
        passwordKey = null
        nonce = null
        chipMappingKeyPair = null
        ephemeralParameters = null
        chipEphemeralKeyPair = null
        readerEphemeralPublicKey = null
        ksEnc = null
        ksMac = null
        secureMessaging = null
    }

    /**
     * Step 1: choose the nonce s and send z = E(K_pi, s). The command carries no data objects.
     */
    private fun sendEncryptedNonce(dataObjects: Map<Int, ByteArray>): ByteArray {
        if (dataObjects.isNotEmpty()) throw PaceStepException("Unexpected data in PACE step 1", SW_WRONG_DATA)

        val s = ByteArray(NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Util.getCipher("$CIPHER_ALGORITHM/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey, IvParameterSpec(ByteArray(cipher.blockSize)))
        val encryptedNonce = cipher.doFinal(s)

        nonce = s
        currentState = PaceState.NONCE_SENT
        return TLVUtil.wrapDO(TAG_ENCRYPTED_NONCE, encryptedNonce)
    }

    /**
     * Step 2, generic mapping (also used by CAM): exchange mapping keys, H = SK_map_PICC * PK_map_PCD and
     * G~ = s * G + H.
     */
    private fun mapNonceGeneric(dataObjects: Map<Int, ByteArray>): ByteArray {
        val readerMappingKey = dataObjects[TAG_MAPPING_DATA_PCD]?.let { decodePublicKey(it, staticParameters) }
            ?: throw PaceStepException("Invalid mapping data in PACE step 2", SW_WRONG_DATA)

        val chipMappingKeyPair = generateKeyPair(staticParameters)
        val agreement = PACEGMWithECDHAgreement()
        agreement.init(chipMappingKeyPair.private)
        val sharedSecretPoint = agreement.doPhase(readerMappingKey)
        ephemeralParameters = PACEProtocol.mapNonceGMWithECDH(nonce, sharedSecretPoint, staticParameters)

        this.chipMappingKeyPair = chipMappingKeyPair
        currentState = PaceState.NONCE_MAPPED
        return TLVUtil.wrapDO(TAG_MAPPING_DATA_PICC, PACEProtocol.encodePublicKeyForSmartCard(chipMappingKeyPair.public))
    }

    /**
     * Step 3: exchange ephemeral keys on the mapped domain parameters, K = KA(SK_PICC~, PK_PCD~),
     * and derive the session keys KS_enc and KS_mac from K.
     */
    private fun exchangeEphemeralKeys(dataObjects: Map<Int, ByteArray>): ByteArray {
        val parameters = ephemeralParameters ?: throw PaceStepException("PACE nonce not mapped", SW_CONDITIONS_NOT_SATISFIED)
        val readerKey = dataObjects[TAG_EPHEMERAL_PUBLIC_KEY_PCD]?.let { decodePublicKey(it, parameters) }
            ?: throw PaceStepException("Invalid ephemeral public key in PACE step 3", SW_WRONG_DATA)

        val chipKeyPair = generateKeyPair(parameters)
        // ICAO 9303-11: the chip must check that the reader did not echo its ephemeral public key
        if ((readerKey as ECPublicKey).w == (chipKeyPair.public as ECPublicKey).w) {
            throw PaceStepException("Reader ephemeral public key equals the chip's", SW_WRONG_DATA)
        }

        val keyAgreement = Util.getKeyAgreement("ECDH")
        keyAgreement.init(chipKeyPair.private)
        keyAgreement.doPhase(PACEProtocol.updateParameterSpec(readerKey, chipKeyPair.private), true)
        val sharedSecret = keyAgreement.generateSecret()
        ksEnc = Util.deriveKey(sharedSecret, CIPHER_ALGORITHM, KEY_LENGTH, Util.ENC_MODE)
        ksMac = Util.deriveKey(sharedSecret, CIPHER_ALGORITHM, KEY_LENGTH, Util.MAC_MODE)

        chipEphemeralKeyPair = chipKeyPair
        readerEphemeralPublicKey = readerKey
        currentState = PaceState.KEYS_AGREED
        return TLVUtil.wrapDO(TAG_EPHEMERAL_PUBLIC_KEY_PICC, PACEProtocol.encodePublicKeyForSmartCard(chipKeyPair.public))
    }

    /**
     * Step 4: verify T_PCD = MAC(KS_mac, PK_PICC~), answer with T_PICC = MAC(KS_mac, PK_PCD~) (and, for CAM, the
     * encrypted chip authentication data) and start secure messaging with SSC 0.
     */
    private fun exchangeAuthenticationTokens(dataObjects: Map<Int, ByteArray>): ByteArray {
        val encKey = ksEnc
        val macKey = ksMac
        val chipKeyPair = chipEphemeralKeyPair
        val readerKey = readerEphemeralPublicKey
        if (encKey == null || macKey == null || chipKeyPair == null || readerKey == null) {
            throw PaceStepException("PACE keys not agreed", SW_CONDITIONS_NOT_SATISFIED)
        }
        val readerToken = dataObjects[TAG_AUTHENTICATION_TOKEN_PCD]
            ?: throw PaceStepException("Missing authentication token in PACE step 4", SW_WRONG_DATA)

        val expectedReaderToken = PACEProtocol.generateAuthenticationToken(oid, macKey, chipKeyPair.public)
        if (!MessageDigest.isEqual(expectedReaderToken, readerToken)) {
            Log.e(TAG, "PACE authentication token mismatch; reader used a different MRZ")
            throw PaceStepException("Authentication verification failed", SW_AUTHENTICATION_FAILED)
        }

        val chipToken = TLVUtil.wrapDO(
            TAG_AUTHENTICATION_TOKEN_PICC, PACEProtocol.generateAuthenticationToken(oid, macKey, readerKey)
        )
        val response = if (mapping == PaceMapping.CHIP_AUTHENTICATION) {
            chipToken + TLVUtil.wrapDO(TAG_ENCRYPTED_CHIP_AUTHENTICATION_DATA, encryptChipAuthenticationData(encKey))
        } else {
            chipToken
        }
        secureMessaging = ChipSecureMessaging(encKey, macKey, 0L, ChipSecureMessaging.Algorithm.AES)
        currentState = PaceState.AUTHENTICATED
        Log.d(TAG, "PACE-${mapping.abbreviation} authentication successful")
        return response
    }

    /**
     * CAM chip authentication data A_PICC = E(KS_enc, CA_PICC) with CA_PICC = SK_map_PICC * SK_PICC^-1 mod n,
     * AES-CBC with IV = E(KS_enc, -1) over the padded CA_PICC. The reader checks PK_map_PICC = CA_PICC * PK_PICC
     * against the chip's public key in EF.CardSecurity.
     */
    private fun encryptChipAuthenticationData(encKey: SecretKey): ByteArray {
        val mappingKey = chipMappingKeyPair?.private as? ECPrivateKey
            ?: throw PaceStepException("PACE-CAM mapping key missing", SW_CONDITIONS_NOT_SATISFIED)
        val staticKey = chipAuthenticationKeyPair?.private as? ECPrivateKey
            ?: throw PaceStepException("PACE-CAM chip key missing", SW_CONDITIONS_NOT_SATISFIED)

        val order = staticParameters.order
        val chipAuthenticationData = mappingKey.s.multiply(staticKey.s.modInverse(order)).mod(order)

        val iv = Util.getCipher("$CIPHER_ALGORITHM/ECB/NoPadding", Cipher.ENCRYPT_MODE, encKey).doFinal(MINUS_ONE_BLOCK)
        val cipher = Util.getCipher("$CIPHER_ALGORITHM/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv))
        val encoded = Util.i2os(chipAuthenticationData, (order.bitLength() + 7) / 8)
        return cipher.doFinal(Util.pad(encoded, cipher.blockSize))
    }

    private fun generateKeyPair(parameters: ECParameterSpec): KeyPair {
        val keyPairGenerator = Util.getKeyPairGenerator("EC")
        keyPairGenerator.initialize(parameters, secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Decodes an uncompressed EC point sent by the reader, rejecting points that are not on the curve.
     */
    private fun decodePublicKey(encoded: ByteArray, parameters: ECParameterSpec): PublicKey? {
        return try {
            val publicKey = PACEProtocol.decodePublicKeyFromSmartCard(encoded, parameters) as ECPublicKey
            val point = Util.toBouncyCastleECPoint(publicKey.w, parameters)
            if (point.isValid && !point.isInfinity) publicKey else null
        } catch (e: Exception) {
            Log.w(TAG, "Invalid public key from reader", e)
            null
        }
    }

    private fun fail(message: String, statusWord: Int): PaceResult {
        Log.w(TAG, message)
        currentState = PaceState.FAILED
        secureMessaging = null
        return PaceResult(false, message, statusWord = statusWord, newState = PaceState.FAILED)
    }

    /**
     * Parses a sequence of BER-TLV data objects with one-byte or 7C-style tags into a tag-to-value map.
     */
    private fun parseDataObjects(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val tag = data[offset++].toInt() and 0xFF
            require(offset < data.size) { "Truncated data object ${Integer.toHexString(tag)}" }
            val first = data[offset++].toInt() and 0xFF
            val length = if (first < 0x80) {
                first
            } else {
                val lengthBytes = first and 0x7F
                require(lengthBytes in 1..2 && offset + lengthBytes <= data.size) { "Unsupported length encoding" }
                var value = 0
                repeat(lengthBytes) { value = (value shl 8) or (data[offset++].toInt() and 0xFF) }
                value
            }
            require(offset + length <= data.size) { "Data object ${Integer.toHexString(tag)} exceeds data" }
            result[tag] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        return result
    }
}
