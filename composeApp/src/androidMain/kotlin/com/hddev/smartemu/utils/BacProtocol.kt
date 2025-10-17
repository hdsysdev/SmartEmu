package com.hddev.smartemu.utils

import android.util.Log
import com.hddev.smartemu.data.PassportData
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implementation of BAC (Basic Access Control) protocol for passport authentication.
 * Handles key derivation, challenge-response authentication, and secure messaging setup.
 */
class BacProtocol {
    
    companion object {
        private const val TAG = "BacProtocol"
        
        // BAC protocol constants
        private const val CHALLENGE_LENGTH = 8
        private const val KEY_LENGTH = 16
        private const val DES_KEY_LENGTH = 8
        
        // Key derivation constants
        private const val KDF_COUNTER_ENC = 1
        private const val KDF_COUNTER_MAC = 2
        
        // Cipher algorithms
        private const val DES_ALGORITHM = "DES"
        private const val TRIPLE_DES_ALGORITHM = "DESede"
        private const val DES_ECB_MODE = "DES/ECB/NoPadding"
        private const val TRIPLE_DES_ECB_MODE = "DESede/ECB/NoPadding"
    }
    
    private val secureRandom = SecureRandom()
    private var currentState = BacState.INITIAL
    private var passportData: PassportData? = null
    private var kEnc: ByteArray? = null
    private var kMac: ByteArray? = null
    private var rndIc: ByteArray? = null
    private var rndIfd: ByteArray? = null
    private var kIfd: ByteArray? = null
    
    /**
     * BAC protocol states for state management.
     */
    enum class BacState {
        INITIAL,
        CHALLENGE_GENERATED,
        AUTHENTICATION_IN_PROGRESS,
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
     * Initializes BAC protocol with passport data.
     */
    fun initialize(passportData: PassportData): BacResult {
        return try {
            if (!passportData.isValid()) {
                Log.e(TAG, "Invalid passport data provided for BAC initialization")
                BacResult(false, "Invalid passport data", newState = BacState.FAILED)
            } else {
                this.passportData = passportData
                deriveKeys(passportData)
                currentState = BacState.INITIAL
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
     * Generates a challenge for BAC authentication.
     */
    fun generateChallenge(): BacResult {
        return try {
            if (currentState != BacState.INITIAL) {
                Log.w(TAG, "Challenge generation attempted in invalid state: $currentState")
                return BacResult(false, "Invalid state for challenge generation", newState = currentState)
            }
            
            // Generate 8-byte random challenge (RND.IC)
            rndIc = ByteArray(CHALLENGE_LENGTH)
            secureRandom.nextBytes(rndIc!!)
            
            currentState = BacState.CHALLENGE_GENERATED
            Log.d(TAG, "BAC challenge generated: ${rndIc!!.toHexString()}")
            
            BacResult(
                success = true,
                message = "Challenge generated",
                data = rndIc,
                newState = BacState.CHALLENGE_GENERATED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate BAC challenge", e)
            currentState = BacState.FAILED
            BacResult(false, "Challenge generation failed: ${e.message}", newState = BacState.FAILED)
        }
    }
    
    /**
     * Processes external authentication command for BAC.
     */
    fun processExternalAuthenticate(authData: ByteArray): BacResult {
        return try {
            if (currentState != BacState.CHALLENGE_GENERATED) {
                Log.w(TAG, "External authenticate attempted in invalid state: $currentState")
                return BacResult(false, "Invalid state for authentication", newState = currentState)
            }
            
            currentState = BacState.AUTHENTICATION_IN_PROGRESS
            
            // Parse authentication data (should contain RND.IFD + K.IFD + encrypted data)
            if (authData.size < 32) {
                Log.e(TAG, "Invalid authentication data length: ${authData.size}")
                currentState = BacState.FAILED
                return BacResult(false, "Invalid authentication data", newState = BacState.FAILED)
            }
            
            // Extract RND.IFD and K.IFD from authentication data
            rndIfd = authData.sliceArray(0..7)
            kIfd = authData.sliceArray(8..15)
            
            Log.d(TAG, "Processing BAC authentication - RND.IFD: ${rndIfd!!.toHexString()}, K.IFD: ${kIfd!!.toHexString()}")
            
            // Verify the authentication data
            val verificationResult = verifyAuthentication(authData)
            if (!verificationResult) {
                Log.e(TAG, "BAC authentication verification failed")
                currentState = BacState.FAILED
                return BacResult(false, "Authentication verification failed", newState = BacState.FAILED)
            }
            
            // Generate response data
            val responseData = generateAuthenticationResponse()
            
            currentState = BacState.AUTHENTICATED
            Log.d(TAG, "BAC authentication successful")
            
            BacResult(
                success = true,
                message = "BAC authentication successful",
                data = responseData,
                newState = BacState.AUTHENTICATED
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process BAC external authentication", e)
            currentState = BacState.FAILED
            BacResult(false, "Authentication failed: ${e.message}", newState = BacState.FAILED)
        }
    }
    
    /**
     * Gets the current BAC protocol state.
     */
    fun getCurrentState(): BacState = currentState
    
    /**
     * Resets the BAC protocol to initial state.
     */
    fun reset() {
        currentState = BacState.INITIAL
        rndIc = null
        rndIfd = null
        kIfd = null
        Log.d(TAG, "BAC protocol reset")
    }
    
    /**
     * Derives encryption and MAC keys from passport MRZ data.
     */
    private fun deriveKeys(passportData: PassportData) {
        try {
            // Generate MRZ data for key derivation
            val mrzData = passportData.toMrzData()
            Log.d(TAG, "Deriving BAC keys from MRZ data")
            
            // Extract key seed from MRZ (passport number + birth date + expiry date + check digits)
            val keySeed = extractKeySeed(mrzData)
            
            // Derive K_seed using SHA-1
            val sha1 = MessageDigest.getInstance("SHA-1")
            val kSeed = sha1.digest(keySeed)
            
            // Derive encryption and MAC keys
            kEnc = deriveKey(kSeed, KDF_COUNTER_ENC)
            kMac = deriveKey(kSeed, KDF_COUNTER_MAC)
            
            Log.d(TAG, "BAC keys derived successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive BAC keys", e)
            throw e
        }
    }
    
    /**
     * Extracts key seed from MRZ data according to ICAO 9303 specification.
     */
    private fun extractKeySeed(mrzData: String): ByteArray {
        // MRZ line 2 contains: passport number + check digit + nationality + birth date + check digit + 
        // gender + expiry date + check digit + personal number + check digit + final check digit
        val line2 = mrzData.substring(44) // Second line of MRZ
        
        // Extract passport number (positions 0-8), birth date (positions 13-18), expiry date (positions 21-26)
        val passportNumber = line2.substring(0, 9).replace('<', '0')
        val birthDate = line2.substring(13, 19)
        val expiryDate = line2.substring(21, 27)
        
        // Calculate check digits
        val passportCheckDigit = line2.substring(9, 10)
        val birthDateCheckDigit = line2.substring(19, 20)
        val expiryDateCheckDigit = line2.substring(27, 28)
        
        val keySeedString = passportNumber + passportCheckDigit + birthDate + birthDateCheckDigit + expiryDate + expiryDateCheckDigit
        
        Log.d(TAG, "Key seed extracted: $keySeedString")
        return keySeedString.toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Derives a key using the key derivation function specified in ICAO 9303.
     */
    private fun deriveKey(kSeed: ByteArray, counter: Int): ByteArray {
        val input = kSeed + byteArrayOf(0x00, 0x00, 0x00, counter.toByte())
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest(input)
        
        // Take first 16 bytes for the key
        return hash.sliceArray(0 until KEY_LENGTH)
    }
    
    /**
     * Verifies the authentication data received from the reader.
     */
    private fun verifyAuthentication(authData: ByteArray): Boolean {
        return try {
            // In a real implementation, this would decrypt and verify the authentication data
            // For simulation purposes, we'll perform basic validation
            
            // Check that we have the required components
            if (rndIc == null || kEnc == null || kMac == null) {
                Log.e(TAG, "Missing required BAC components for verification")
                return false
            }
            
            // Simulate successful verification for valid-looking data
            val hasValidStructure = authData.size >= 32 && rndIfd != null && kIfd != null
            
            Log.d(TAG, "BAC authentication verification: $hasValidStructure")
            return hasValidStructure
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during BAC verification", e)
            false
        }
    }
    
    /**
     * Generates the authentication response for successful BAC.
     */
    private fun generateAuthenticationResponse(): ByteArray {
        return try {
            // Generate K.IC (8 bytes)
            val kIc = ByteArray(8)
            secureRandom.nextBytes(kIc)
            
            // Create response data: RND.IFD + RND.IC + K.IC
            val responseData = rndIfd!! + rndIc!! + kIc
            
            Log.d(TAG, "Generated BAC authentication response")
            
            // In a real implementation, this would be encrypted with the derived keys
            // For simulation, return the response data directly
            responseData
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate BAC authentication response", e)
            throw e
        }
    }
    
    /**
     * Extension function to convert ByteArray to hex string.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}