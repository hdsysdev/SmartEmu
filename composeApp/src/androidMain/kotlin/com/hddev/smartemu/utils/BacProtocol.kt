package com.hddev.smartemu.utils

import android.util.Log
import com.hddev.smartemu.data.PassportData
import org.jmrtd.BACKey
import org.jmrtd.Util
import org.jmrtd.protocol.BACProtocol
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Chip side of BAC (Basic Access Control), ICAO 9303 part 11 section 4.3.
 * Mirrors the reader side in JMRTD's BACProtocol / BACAPDUSender: the chip answers GET CHALLENGE with RND.IC,
 * verifies and decrypts the reader's EXTERNAL AUTHENTICATE cryptogram, answers with its own cryptogram and
 * derives the 3DES session keys and send sequence counter for secure messaging.
 */
class BacProtocol {

    companion object {
        private const val TAG = "BacProtocol"

        private const val CHALLENGE_LENGTH = 8
        private const val KEY_MATERIAL_LENGTH = 16
        private const val CRYPTOGRAM_LENGTH = 32
        private const val MAC_LENGTH = 8

        /** EXTERNAL AUTHENTICATE data: E_IFD (32 bytes) followed by M_IFD (8 bytes). */
        const val MUTUAL_AUTHENTICATION_DATA_LENGTH = CRYPTOGRAM_LENGTH + MAC_LENGTH

        private val ZERO_IV = IvParameterSpec(ByteArray(8))
    }

    private val secureRandom = SecureRandom()
    private val cipher: Cipher = Util.getCipher("DESede/CBC/NoPadding")
    private val mac = Util.getMac("ISO9797Alg3Mac")

    private var currentState = BacState.INITIAL
    private var kEnc: SecretKey? = null
    private var kMac: SecretKey? = null
    private var rndIc: ByteArray? = null
    private var secureMessaging: ChipSecureMessaging? = null

    /**
     * BAC protocol states for state management.
     */
    enum class BacState {
        INITIAL,
        CHALLENGE_GENERATED,
        AUTHENTICATED,
        FAILED
    }

    /**
     * Result of BAC operations.
     */
    data class BacResult(
        val success: Boolean,
        val message: String,
        val data: ByteArray? = null,
        val newState: BacState = BacState.INITIAL
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BacResult) return false

            if (success != other.success) return false
            if (message != other.message) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            if (newState != other.newState) return false

            return true
        }

        override fun hashCode(): Int {
            var result = success.hashCode()
            result = 31 * result + message.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + newState.hashCode()
            return result
        }
    }

    /**
     * Initializes BAC protocol with passport data, deriving the document basic access keys K_enc and K_mac.
     */
    fun initialize(passportData: PassportData): BacResult {
        return try {
            if (!passportData.isValid()) {
                Log.e(TAG, "Invalid passport data provided for BAC initialization")
                currentState = BacState.FAILED
                BacResult(false, "Invalid passport data", newState = BacState.FAILED)
            } else {
                val bacKey = BACKey(passportData.passportNumber, passportData.mrzDateOfBirth(), passportData.mrzExpiryDate())
                val keySeed = BACProtocol.computeKeySeedForBAC(bacKey)
                kEnc = Util.deriveKey(keySeed, Util.ENC_MODE)
                kMac = Util.deriveKey(keySeed, Util.MAC_MODE)
                reset()
                Log.d(TAG, "BAC protocol initialized successfully")
                BacResult(true, "BAC initialized", newState = BacState.INITIAL)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BAC protocol", e)
            currentState = BacState.FAILED
            BacResult(false, "BAC initialization failed: ${e.message}", newState = BacState.FAILED)
        }
    }

    /**
     * Generates RND.IC for GET CHALLENGE. A new challenge restarts BAC and discards any previous session.
     */
    fun generateChallenge(): BacResult {
        return try {
            if (kEnc == null || kMac == null) {
                Log.w(TAG, "Challenge requested before BAC keys were derived")
                return BacResult(false, "BAC not initialized", newState = currentState)
            }

            val challenge = ByteArray(CHALLENGE_LENGTH)
            secureRandom.nextBytes(challenge)
            rndIc = challenge
            secureMessaging = null

            currentState = BacState.CHALLENGE_GENERATED
            Log.d(TAG, "BAC challenge generated")

            BacResult(
                success = true,
                message = "Challenge generated",
                data = challenge.copyOf(),
                newState = BacState.CHALLENGE_GENERATED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate BAC challenge", e)
            currentState = BacState.FAILED
            BacResult(false, "Challenge generation failed: ${e.message}", newState = BacState.FAILED)
        }
    }

    /**
     * Processes EXTERNAL AUTHENTICATE (mutual authentication) data E_IFD || M_IFD.
     * On success returns E_IC || M_IC and establishes secure messaging.
     */
    fun processExternalAuthenticate(authData: ByteArray): BacResult {
        return try {
            val challenge = rndIc
            val encKey = kEnc
            val macKey = kMac
            if (currentState != BacState.CHALLENGE_GENERATED || challenge == null || encKey == null || macKey == null) {
                Log.w(TAG, "External authenticate attempted in invalid state: $currentState")
                return BacResult(false, "Invalid state for authentication", newState = currentState)
            }

            // The challenge is single use, whatever the outcome
            rndIc = null

            if (authData.size != MUTUAL_AUTHENTICATION_DATA_LENGTH) {
                Log.e(TAG, "Invalid authentication data length: ${authData.size}")
                return fail("Invalid authentication data length")
            }

            val eIfd = authData.copyOfRange(0, CRYPTOGRAM_LENGTH)
            val mIfd = authData.copyOfRange(CRYPTOGRAM_LENGTH, MUTUAL_AUTHENTICATION_DATA_LENGTH)
            if (!computeMac(macKey, eIfd).contentEquals(mIfd)) {
                Log.e(TAG, "BAC cryptogram MAC mismatch; reader used a different MRZ")
                return fail("Authentication verification failed")
            }

            // S = RND.IFD || RND.IC || K.IFD
            cipher.init(Cipher.DECRYPT_MODE, encKey, ZERO_IV)
            val s = cipher.doFinal(eIfd)
            val rndIfd = s.copyOfRange(0, 8)
            val echoedRndIc = s.copyOfRange(8, 16)
            val kIfd = s.copyOfRange(16, 32)
            if (!echoedRndIc.contentEquals(challenge)) {
                Log.e(TAG, "BAC cryptogram does not contain the issued challenge")
                return fail("Authentication verification failed")
            }

            // R = RND.IC || RND.IFD || K.IC
            val kIc = ByteArray(KEY_MATERIAL_LENGTH)
            secureRandom.nextBytes(kIc)
            cipher.init(Cipher.ENCRYPT_MODE, encKey, ZERO_IV)
            val eIc = cipher.doFinal(challenge + rndIfd + kIc)
            val mIc = computeMac(macKey, eIc)

            val sessionKeySeed = ByteArray(KEY_MATERIAL_LENGTH) { i -> (kIfd[i].toInt() xor kIc[i].toInt()).toByte() }
            secureMessaging = ChipSecureMessaging(
                ksEnc = Util.deriveKey(sessionKeySeed, Util.ENC_MODE),
                ksMac = Util.deriveKey(sessionKeySeed, Util.MAC_MODE),
                ssc = BACProtocol.computeSendSequenceCounter(challenge, rndIfd)
            )

            currentState = BacState.AUTHENTICATED
            Log.d(TAG, "BAC authentication successful")

            BacResult(
                success = true,
                message = "BAC authentication successful",
                data = eIc + mIc,
                newState = BacState.AUTHENTICATED
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process BAC external authentication", e)
            fail("Authentication failed: ${e.message}")
        }
    }

    /**
     * Gets the current BAC protocol state.
     */
    fun getCurrentState(): BacState = currentState

    /**
     * Secure messaging session established by the last successful mutual authentication, if any.
     */
    fun getSecureMessaging(): ChipSecureMessaging? = secureMessaging

    /**
     * Resets the BAC protocol to initial state, keeping the document basic access keys.
     */
    fun reset() {
        currentState = BacState.INITIAL
        rndIc = null
        secureMessaging = null
        Log.d(TAG, "BAC protocol reset")
    }

    private fun fail(message: String): BacResult {
        currentState = BacState.FAILED
        secureMessaging = null
        return BacResult(false, message, newState = BacState.FAILED)
    }

    private fun computeMac(key: SecretKey, data: ByteArray): ByteArray {
        mac.init(key)
        return mac.doFinal(Util.pad(data, 8))
    }
}
