package com.hddev.smartemu.utils

import android.util.Log
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PaceSuite
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SessionCipher
import com.hddev.smartemu.data.chipConfiguration
import net.sf.scuba.tlv.TLVUtil
import net.sf.scuba.util.Hex
import org.jmrtd.AccessKeySpec
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.protocol.PACEGMWithECDHAgreement
import org.jmrtd.protocol.PACEProtocol
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.interfaces.DHPublicKey
import javax.crypto.spec.DHParameterSpec
import javax.crypto.spec.DHPrivateKeySpec
import javax.crypto.spec.DHPublicKeySpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chip side of PACE (Password Authenticated Connection Establishment), ICAO 9303 part 11 section 4.4, with the
 * MRZ or the CAN as password. Mirrors the reader side in JMRTD's PACEProtocol and reuses its key derivation,
 * nonce mapping and authentication token helpers. The chip offers the suites of its configuration's
 * [com.hddev.smartemu.data.ChipConfiguration.paceSuites], as advertised in EF.CardAccess by [paceInfo]: usually one,
 * the document's mapping ([PassportData.paceMapping]) with ECDH on a curve or Diffie-Hellman in a group, and a cipher.
 * - Generic Mapping (GM): G~ = s * G + H (ECDH) or g~ = g^s * h (DH), with H or h from a key exchange of mapping keys
 * - Integrated Mapping (IM): the generator is a pseudo-random function of the chip's nonce s and the nonce t the
 *   reader sends in clear, mapped onto the curve with Icart's point encoding, or into the subgroup as x^((p-1)/q)
 * - Chip Authentication Mapping (CAM, ECDH only): GM, and the chip proves it holds the private key for the public key
 *   in EF.CardSecurity by sending CA = SK_map * SK_chip^-1 mod n, encrypted, with its authentication token
 *
 * The reader selects a suite with MSE:Set AT, naming its domain parameters (DO'84) when the chip offers more than
 * one set, then runs four GENERAL AUTHENTICATE steps: encrypted nonce, nonce mapping, ephemeral key exchange and
 * mutual authentication tokens. The chip checks what the reader sends as strictly as ICAO 9303 words it.
 *
 * [secureRandom] supplies the nonce and the chip's key pairs; tests pass a fixed one to replay ICAO's worked example.
 */
class PaceProtocol(private val secureRandom: SecureRandom = SecureRandom()) {

    companion object {
        private const val TAG = "PaceProtocol"

        /** The domain parameters of the generic profile, NIST P-256. */
        const val PARAMETER_ID = PACEInfo.PARAM_ID_ECP_NIST_P256_R1
        private const val PACE_VERSION = 2

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

        /** Integrated Mapping's pseudo-random function constants c0 and c1, ICAO 9303-11 4.4.3.3.2, by nonce length. */
        private val IM_C0_128 = Hex.hexStringToBytes("A668892A7C41E3CA739F40B057D85904")
        private val IM_C1_128 = Hex.hexStringToBytes("A4E136AC725F738B01C1F60217C188AD")
        private val IM_C0_256 = Hex.hexStringToBytes("D463D65234124EF7897054986DCA0A174E28DF758CBAA03F240616414D5A1676")
        private val IM_C1_256 = Hex.hexStringToBytes("54BD7255F0AAF831BEC3423FCF39D69B6CBF066677D0FAAE5AADD99DF8E53517")

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
         * The PACE protocol identifier for a mapping and cipher, with ECDH or, if [dh], Diffie-Hellman: id-PACE
         * followed by the key agreement and mapping, then the cipher. ICAO 9303-11 defines CAM for ECDH with AES only.
         */
        fun oid(mapping: PaceMapping, cipher: SessionCipher = SessionCipher.AES_128, dh: Boolean = false): String {
            val family = when (mapping) {
                PaceMapping.GENERIC -> if (dh) SecurityInfo.ID_PACE_DH_GM else SecurityInfo.ID_PACE_ECDH_GM
                PaceMapping.INTEGRATED -> if (dh) SecurityInfo.ID_PACE_DH_IM else SecurityInfo.ID_PACE_ECDH_IM
                PaceMapping.CHIP_AUTHENTICATION -> {
                    require(!dh) { "PACE-CAM is defined for ECDH only" }
                    require(cipher.isAes) { "PACE-CAM is defined with AES only" }
                    SecurityInfo.ID_PACE_ECDH_CAM
                }
            }
            val cipherNumber = when (cipher) {
                SessionCipher.TDES -> 1
                SessionCipher.AES_128 -> 2
                SessionCipher.AES_192 -> 3
                SessionCipher.AES_256 -> 4
            }
            return "$family.$cipherNumber"
        }

        fun oid(suite: PaceSuite): String = oid(suite.mapping, suite.spec.cipher, suite.spec.usesDh)

        /**
         * A PACEInfo published in EF.CardAccess, and copied to EF.DG14 and EF.CardSecurity.
         */
        fun paceInfo(suite: PaceSuite): PACEInfo = PACEInfo(oid(suite), PACE_VERSION, suite.spec.parameterId)

        fun paceInfo(mapping: PaceMapping, spec: PaceSpec = PaceSpec()): PACEInfo = paceInfo(PaceSuite(mapping, spec))

        /**
         * A static chip key pair for PACE-CAM. It must be on the PACE domain parameters; the public key goes in
         * EF.CardSecurity.
         */
        fun generateChipAuthenticationKeyPair(curve: EcCurve = EcCurve.NIST_P256): KeyPair {
            val keyPairGenerator = Util.getKeyPairGenerator("EC")
            keyPairGenerator.initialize(PACEInfo.toParameterSpec(curve.paceParameterId), SecureRandom())
            return keyPairGenerator.generateKeyPair()
        }

        /**
         * Decodes an uncompressed EC point a reader sent, 04 || X || Y, or returns null if it is not a point on the
         * curve of [parameters]. Each coordinate must take exactly the field's size, as on a real chip: JMRTD would
         * split a point of any other length down the middle and could read a badly encoded point as a valid one.
         */
        fun decodeReaderPublicKey(encoded: ByteArray, parameters: ECParameterSpec): ECPublicKey? {
            val coordinateSize = (parameters.curve.field.fieldSize + 7) / 8
            if (encoded.size != 1 + 2 * coordinateSize) {
                Log.w(TAG, "Public key from reader has ${encoded.size} bytes, not ${1 + 2 * coordinateSize}")
                return null
            }
            return try {
                val publicKey = PACEProtocol.decodePublicKeyFromSmartCard(encoded, parameters) as ECPublicKey
                val point = Util.toBouncyCastleECPoint(publicKey.w, parameters)
                if (point.isValid && !point.isInfinity) publicKey else null
            } catch (e: Exception) {
                Log.w(TAG, "Invalid public key from reader", e)
                null
            }
        }

        /**
         * Decodes a Diffie-Hellman public value y a reader sent, or returns null unless it is an element of the
         * subgroup of order [q]: 1 < y < p - 1 and y^q = 1 mod p, the RFC 2631 check that keeps small subgroups out.
         * ICAO 9303-11 9.4.1 encodes it as an unsigned integer in as few bytes as possible, so a leading zero byte is
         * an encoding error, as on a strict chip.
         */
        fun decodeReaderDhPublicKey(encoded: ByteArray, parameters: DHParameterSpec, q: BigInteger): DHPublicKey? {
            if (encoded.isEmpty() || encoded[0] == 0.toByte()) {
                Log.w(TAG, "Public value from reader is empty or has a leading zero byte")
                return null
            }
            val y = BigInteger(1, encoded)
            val p = parameters.p
            if (y <= BigInteger.ONE || y >= p - BigInteger.ONE || y.modPow(q, p) != BigInteger.ONE) {
                Log.w(TAG, "Public value from reader is not in the subgroup")
                return null
            }
            return KeyFactory.getInstance("DH").generatePublic(DHPublicKeySpec(y, p, parameters.g)) as DHPublicKey
        }

        /**
         * Integrated Mapping's pseudo-random function R_p(s, t), ICAO 9303-11 4.4.3.3.2: the cipher in CBC mode with a
         * zero IV, keyed first with t, then with each output truncated to the key size, until n * l >= log2 p + 64.
         * JMRTD's PACEProtocol has it too, but keys two-key 3DES with 16 bytes, which only some JCE providers take;
         * here a 16-byte key becomes the equivalent K1 || K2 || K1.
         */
        internal fun integratedMappingPrf(s: ByteArray, t: ByteArray, p: BigInteger, cipher: SessionCipher): BigInteger {
            val (c0, c1) = when (s.size) {
                16 -> IM_C0_128 to IM_C1_128
                24, 32 -> IM_C0_256 to IM_C1_256
                else -> throw IllegalArgumentException("Nonce s of ${s.size} bytes")
            }
            val algorithm = if (cipher.isAes) "AES" else "DESede"
            val blockCipher = Util.getCipher("$algorithm/CBC/NoPadding")
            val zeroIv = IvParameterSpec(ByteArray(blockCipher.blockSize))
            fun key(bytes: ByteArray) = SecretKeySpec(
                if (cipher.isAes) bytes else bytes + bytes.copyOfRange(0, 8),
                algorithm
            )

            blockCipher.init(Cipher.ENCRYPT_MODE, key(t), zeroIv)
            var k = blockCipher.doFinal(s)
            var x = byteArrayOf()
            var n = 0
            while (n * s.size * 8 < p.bitLength() + 64) {
                blockCipher.init(Cipher.ENCRYPT_MODE, key(k.copyOfRange(0, t.size)), zeroIv)
                k = blockCipher.doFinal(c0)
                x += blockCipher.doFinal(c1)
                n++
            }
            return BigInteger(1, x).mod(p)
        }

        /**
         * The length of the nonce s: one block of the cipher, except that Integrated Mapping takes the smallest
         * multiple of the block size at least the key size (ICAO 9303-11 4.4.3.3), 32 bytes for AES-192 and AES-256.
         */
        internal fun nonceLength(mapping: PaceMapping, cipher: SessionCipher): Int {
            val blockSize = if (cipher.isAes) 16 else 8
            if (mapping != PaceMapping.INTEGRATED) return blockSize
            val keySize = cipher.keyLength / 8
            return (keySize + blockSize - 1) / blockSize * blockSize
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

    // The suites the chip offers, set by initialize()
    private var suites = listOf(PaceSuite(PaceMapping.GENERIC, PaceSpec()))
    private var mrzKey: AccessKeySpec? = null
    private var canKey: AccessKeySpec? = null
    /** PACE-CAM's static key pairs, by the parameter ID of the curve they're on. */
    private var chipAuthenticationKeyPairs: Map<Int, KeyPair> = emptyMap()

    private var currentState = PaceState.INITIAL

    /** The suite the reader selected with MSE:Set AT, or the first one the chip offers before it has. */
    var suite: PaceSuite = suites.first()
        private set
    private val spec: PaceSpec get() = suite.spec
    private val cipherAlgorithm: String get() = if (spec.cipher.isAes) "AES" else "DESede"

    /** Protocol identifier of [suite]. */
    val oid: String get() = oid(suite)

    /** The curve of [suite], as in "brainpoolP256r1", unless it runs Diffie-Hellman. */
    val curve: EcCurve get() = spec.curve

    // Per-run values, discarded by reset()
    private var passwordKey: SecretKey? = null
    private var staticParameters: AlgorithmParameterSpec? = null
    /** The order of the DH subgroup, which the mapped generator keeps but JCA's DHParameterSpec can't carry. */
    private var subgroupOrder: BigInteger? = null
    private var nonce: ByteArray? = null
    private var chipMappingKeyPair: KeyPair? = null
    private var ephemeralParameters: AlgorithmParameterSpec? = null
    private var chipEphemeralKeyPair: KeyPair? = null
    private var readerEphemeralPublicKey: PublicKey? = null
    private var ksEnc: SecretKey? = null
    private var ksMac: SecretKey? = null
    private var secureMessaging: ChipSecureMessaging? = null

    /**
     * Initializes PACE with passport data: the suites its chip offers and the passwords, the MRZ and the CAN.
     * PACE-CAM also needs the static [chipAuthenticationKeyPairs], by the parameter ID of each CAM suite's curve, whose
     * public keys are in EF.CardSecurity.
     */
    fun initialize(passportData: PassportData, chipAuthenticationKeyPairs: Map<Int, KeyPair> = emptyMap()): PaceResult {
        return try {
            val configuration = passportData.chipConfiguration()
            val offered = configuration.paceSuites.ifEmpty { listOf(PaceSuite(configuration.paceMapping, configuration.pace)) }
            val camWithoutKey = offered.firstOrNull {
                it.mapping == PaceMapping.CHIP_AUTHENTICATION && it.spec.parameterId !in chipAuthenticationKeyPairs
            }
            if (!passportData.isValid()) {
                Log.e(TAG, "Invalid passport data provided for PACE initialization")
                currentState = PaceState.FAILED
                PaceResult(false, "Invalid passport data", newState = PaceState.FAILED)
            } else if (camWithoutKey != null) {
                Log.e(TAG, "${camWithoutKey.displayName} needs a chip authentication key pair")
                currentState = PaceState.FAILED
                PaceResult(false, "Missing chip authentication key for PACE-CAM", newState = PaceState.FAILED)
            } else {
                suites = offered
                suite = offered.first()
                this.chipAuthenticationKeyPairs = chipAuthenticationKeyPairs
                mrzKey = BACKey(passportData.passportNumber, passportData.mrzDateOfBirth(), passportData.mrzExpiryDate())
                canKey = if (passportData.hasCan()) PACEKeySpec.createCANKey(passportData.can) else null
                reset()
                Log.d(TAG, "PACE initialized with ${offered.joinToString { it.displayName }}")
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
     * domain parameter identifier (DO'84), which ICAO 9303-11 4.4.4.1 requires when the chip offers more than one set
     * of domain parameters. Selects the suite and derives K_pi for it, starting a new PACE run.
     */
    fun processMseSetAt(data: ByteArray): PaceResult {
        if (mrzKey == null) {
            return PaceResult(false, "PACE not initialized", newState = currentState)
        }
        reset()

        val dataObjects = try {
            parseDataObjects(data)
        } catch (e: IllegalArgumentException) {
            return fail("Malformed MSE:Set AT data: ${e.message}", SW_WRONG_DATA)
        }

        val mechanism = dataObjects[TAG_CRYPTOGRAPHIC_MECHANISM]
            ?.let { TLVUtil.wrapDO(TAG_CRYPTOGRAPHIC_MECHANISM, it) }
        val requested = suites.filter { mechanism != null && mechanism.contentEquals(Util.toOIDBytes(oid(it))) }
        if (requested.isEmpty()) {
            return fail("Unsupported PACE protocol", SW_WRONG_DATA)
        }

        // The MRZ is always a password; the CAN only if one is printed on the document. There is no PIN or PUK.
        val passwordReference = dataObjects[TAG_PASSWORD_REFERENCE]?.singleOrNull()
        val (accessKey, passwordName) = when (passwordReference) {
            PassportService.MRZ_PACE_KEY_REFERENCE -> mrzKey to "MRZ"
            PassportService.CAN_PACE_KEY_REFERENCE -> canKey to "CAN"
            else -> null to null
        }
        if (accessKey == null) {
            return fail("Unsupported PACE password", SW_REFERENCED_DATA_NOT_FOUND)
        }

        val domainParameterId = dataObjects[TAG_DOMAIN_PARAMETER_ID]
        val selected = if (domainParameterId != null) {
            val parameterId = Util.os2i(domainParameterId).toInt()
            requested.find { it.spec.parameterId == parameterId }
                ?: return fail("Unsupported PACE domain parameters", SW_REFERENCED_DATA_NOT_FOUND)
        } else if (suites.map { it.spec.parameterId }.distinct().size > 1) {
            return fail("MSE:Set AT names no domain parameters, but the chip offers several", SW_WRONG_DATA)
        } else {
            // One set of domain parameters, so one suite per protocol identifier
            requested.single()
        }

        return try {
            passwordKey = PACEProtocol.deriveStaticPACEKey(accessKey, oid(selected))
            staticParameters = PACEInfo.toParameterSpec(selected.spec.parameterId)
            subgroupOrder = (staticParameters as? PACEInfo.DHCParameterSpec)?.q
            suite = selected
            currentState = PaceState.KEY_SELECTED
            val message = "${selected.displayName} selected with the $passwordName"
            Log.d(TAG, message)
            PaceResult(true, message, newState = currentState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive the PACE password key", e)
            fail("PACE password key derivation failed: ${e.message}", SW_WRONG_DATA)
        }
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
                PaceState.NONCE_SENT -> if (suite.mapping == PaceMapping.INTEGRATED) {
                    mapNonceIntegrated(dataObjects)
                } else {
                    mapNonceGeneric(dataObjects)
                }
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
     * The mapping of [suite].
     */
    fun getMapping(): PaceMapping = suite.mapping

    /**
     * Resets PACE to its initial state, keeping the password keys.
     */
    fun reset() {
        currentState = PaceState.INITIAL
        passwordKey = null
        staticParameters = null
        subgroupOrder = null
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

        val cipher = Util.getCipher("$cipherAlgorithm/CBC/NoPadding")
        val s = ByteArray(nonceLength(suite.mapping, spec.cipher)).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey, IvParameterSpec(ByteArray(cipher.blockSize)))
        val encryptedNonce = cipher.doFinal(s)

        nonce = s
        currentState = PaceState.NONCE_SENT
        return TLVUtil.wrapDO(TAG_ENCRYPTED_NONCE, encryptedNonce)
    }

    /**
     * Step 2, generic mapping (also used by CAM): exchange mapping keys, then G~ = s * G + H with
     * H = SK_map_PICC * PK_map_PCD for ECDH, or g~ = g^s * h mod p with h = PK_map_PCD^SK_map_PICC for DH.
     */
    private fun mapNonceGeneric(dataObjects: Map<Int, ByteArray>): ByteArray {
        val readerMappingData = dataObjects[TAG_MAPPING_DATA_PCD]
        val chipMappingKeyPair: KeyPair
        when (val parameters = staticParameters) {
            is ECParameterSpec -> {
                val readerMappingKey = readerMappingData?.let { decodeReaderPublicKey(it, parameters) }
                    ?: throw PaceStepException("Invalid mapping data in PACE step 2", SW_WRONG_DATA)
                chipMappingKeyPair = generateKeyPair(parameters)
                val agreement = PACEGMWithECDHAgreement()
                agreement.init(chipMappingKeyPair.private)
                val sharedSecretPoint = agreement.doPhase(readerMappingKey)
                ephemeralParameters = PACEProtocol.mapNonceGMWithECDH(nonce, sharedSecretPoint, parameters)
            }
            is DHParameterSpec -> {
                val q = requireSubgroupOrder()
                val readerMappingKey = readerMappingData?.let { decodeReaderDhPublicKey(it, parameters, q) }
                    ?: throw PaceStepException("Invalid mapping data in PACE step 2", SW_WRONG_DATA)
                chipMappingKeyPair = generateDhKeyPair(parameters, q)
                val sharedSecret = dhAgreement(chipMappingKeyPair.private, readerMappingKey)
                ephemeralParameters = checkedGenerator(
                    PACEProtocol.mapNonceGMWithDH(nonce, Util.os2i(sharedSecret), parameters)
                )
            }
            else -> throw PaceStepException("PACE domain parameters not selected", SW_CONDITIONS_NOT_SATISFIED)
        }

        this.chipMappingKeyPair = chipMappingKeyPair
        currentState = PaceState.NONCE_MAPPED
        return TLVUtil.wrapDO(TAG_MAPPING_DATA_PICC, PACEProtocol.encodePublicKeyForSmartCard(chipMappingKeyPair.public))
    }

    /**
     * Step 2, integrated mapping: the reader sends the nonce t, exactly the cipher's key size (ICAO 9303-11
     * 4.4.3.3), and the generator is f(R_p(s, t)): Icart's point encoding for ECDH, x^((p-1)/q) mod p for DH. The
     * chip's mapping data is empty (4.4.5.2.2).
     */
    private fun mapNonceIntegrated(dataObjects: Map<Int, ByteArray>): ByteArray {
        val t = dataObjects[TAG_MAPPING_DATA_PCD]
            ?: throw PaceStepException("Missing nonce t in PACE step 2", SW_WRONG_DATA)
        val keySize = spec.cipher.keyLength / 8
        if (t.size != keySize) {
            throw PaceStepException("Nonce t has ${t.size} bytes rather than the key size, $keySize", SW_WRONG_DATA)
        }

        val s = nonce ?: throw PaceStepException("PACE nonce not sent", SW_CONDITIONS_NOT_SATISFIED)
        ephemeralParameters = when (val parameters = staticParameters) {
            is ECParameterSpec -> {
                val x = integratedMappingPrf(s, t, Util.getPrime(parameters), spec.cipher)
                ECParameterSpec(parameters.curve, PACEProtocol.icartPointEncode(x, parameters), parameters.order, parameters.cofactor)
            }
            is DHParameterSpec -> {
                val q = requireSubgroupOrder()
                val x = integratedMappingPrf(s, t, parameters.p, spec.cipher)
                val g = x.modPow((parameters.p - BigInteger.ONE) / q, parameters.p)
                checkedGenerator(DHParameterSpec(parameters.p, g, parameters.l))
            }
            else -> throw PaceStepException("PACE domain parameters not selected", SW_CONDITIONS_NOT_SATISFIED)
        }

        currentState = PaceState.NONCE_MAPPED
        return TLVUtil.wrapDO(TAG_MAPPING_DATA_PICC, ByteArray(0))
    }

    /**
     * Step 3: exchange ephemeral keys on the mapped domain parameters, K = KA(SK_PICC~, PK_PCD~),
     * and derive the session keys KS_enc and KS_mac from K.
     */
    private fun exchangeEphemeralKeys(dataObjects: Map<Int, ByteArray>): ByteArray {
        val readerKeyData = dataObjects[TAG_EPHEMERAL_PUBLIC_KEY_PCD]
        val readerKey: PublicKey
        val chipKeyPair: KeyPair
        val sharedSecret: ByteArray
        when (val parameters = ephemeralParameters) {
            is ECParameterSpec -> {
                val readerEcKey = readerKeyData?.let { decodeReaderPublicKey(it, parameters) }
                    ?: throw PaceStepException("Invalid ephemeral public key in PACE step 3", SW_WRONG_DATA)
                chipKeyPair = generateKeyPair(parameters)
                // ICAO 9303-11: the chip must check that the reader did not echo its ephemeral public key
                if (readerEcKey.w == (chipKeyPair.public as ECPublicKey).w) throw echoedKey()

                val keyAgreement = Util.getKeyAgreement("ECDH")
                keyAgreement.init(chipKeyPair.private)
                keyAgreement.doPhase(PACEProtocol.updateParameterSpec(readerEcKey, chipKeyPair.private), true)
                sharedSecret = keyAgreement.generateSecret()
                readerKey = readerEcKey
            }
            is DHParameterSpec -> {
                val q = requireSubgroupOrder()
                val readerDhKey = readerKeyData?.let { decodeReaderDhPublicKey(it, parameters, q) }
                    ?: throw PaceStepException("Invalid ephemeral public key in PACE step 3", SW_WRONG_DATA)
                chipKeyPair = generateDhKeyPair(parameters, q)
                if (readerDhKey.y == (chipKeyPair.public as DHPublicKey).y) throw echoedKey()

                sharedSecret = dhAgreement(chipKeyPair.private, readerDhKey)
                readerKey = readerDhKey
            }
            else -> throw PaceStepException("PACE nonce not mapped", SW_CONDITIONS_NOT_SATISFIED)
        }
        ksEnc = Util.deriveKey(sharedSecret, cipherAlgorithm, spec.cipher.keyLength, Util.ENC_MODE)
        ksMac = Util.deriveKey(sharedSecret, cipherAlgorithm, spec.cipher.keyLength, Util.MAC_MODE)

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
        val response = if (suite.mapping == PaceMapping.CHIP_AUTHENTICATION) {
            chipToken + TLVUtil.wrapDO(TAG_ENCRYPTED_CHIP_AUTHENTICATION_DATA, encryptChipAuthenticationData(encKey))
        } else {
            chipToken
        }
        secureMessaging = ChipSecureMessaging(encKey, macKey, 0L, ChipSecureMessaging.algorithmFor(spec.cipher))
        currentState = PaceState.AUTHENTICATED
        Log.d(TAG, "${suite.displayName} authentication successful")
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
        val staticKey = chipAuthenticationKeyPairs[spec.parameterId]?.private as? ECPrivateKey
            ?: throw PaceStepException("PACE-CAM chip key missing", SW_CONDITIONS_NOT_SATISFIED)

        val order = mappingKey.params.order
        val chipAuthenticationData = mappingKey.s.multiply(staticKey.s.modInverse(order)).mod(order)

        val iv = Util.getCipher("AES/ECB/NoPadding", Cipher.ENCRYPT_MODE, encKey).doFinal(MINUS_ONE_BLOCK)
        val cipher = Util.getCipher("AES/CBC/NoPadding")
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
     * A Diffie-Hellman key pair on [parameters]' generator, with the private key x drawn uniformly from 1 to q - 1,
     * since the generator has order q. JCA's generators can't be told q, and would draw from a wider range.
     */
    private fun generateDhKeyPair(parameters: DHParameterSpec, q: BigInteger): KeyPair {
        var x: BigInteger
        do {
            x = BigInteger(q.bitLength(), secureRandom)
        } while (x.signum() == 0 || x >= q)
        val y = parameters.g.modPow(x, parameters.p)
        val keyFactory = KeyFactory.getInstance("DH")
        return KeyPair(
            keyFactory.generatePublic(DHPublicKeySpec(y, parameters.p, parameters.g)),
            keyFactory.generatePrivate(DHPrivateKeySpec(x, parameters.p, parameters.g))
        )
    }

    /** The Diffie-Hellman shared secret, as an octet string as long as p, as JCA's key agreement makes it. */
    private fun dhAgreement(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = Util.getKeyAgreement("DH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret()
    }

    private fun echoedKey() = PaceStepException("Reader ephemeral public key equals the chip's", SW_WRONG_DATA)

    private fun requireSubgroupOrder(): BigInteger =
        subgroupOrder ?: throw PaceStepException("PACE DH group without a subgroup order", SW_CONDITIONS_NOT_SATISFIED)

    /** ICAO 9303-11 4.4.3.3: a mapped DH generator of 1 would make every key 1, so the run stops there. */
    private fun checkedGenerator(parameters: DHParameterSpec): DHParameterSpec {
        if (parameters.g <= BigInteger.ONE) throw PaceStepException("Mapped generator is 1", SW_WRONG_DATA)
        return parameters
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
