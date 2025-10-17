package com.hddev.smartemu.utils

/**
 * Utility class for parsing APDU commands and generating responses.
 * This class contains the core APDU parsing logic that can be tested independently.
 */
object ApduParser {
    
    // Standard APDU response codes
    val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
    val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00.toByte())
    val SW_INSTRUCTION_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
    val SW_CLASS_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00.toByte())
    val SW_SECURITY_STATUS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x82.toByte())
    
    // APDU command constants
    private const val CLA_ISO7816 = 0x00.toByte()
    private const val INS_SELECT = 0xA4.toByte()
    private const val INS_READ_BINARY = 0xB0.toByte()
    private const val INS_GET_CHALLENGE = 0x84.toByte()
    private const val INS_EXTERNAL_AUTHENTICATE = 0x82.toByte()
    private const val INS_INTERNAL_AUTHENTICATE = 0x88.toByte()
    private const val INS_MSE_SET_AT = 0x22.toByte()
    private const val INS_GENERAL_AUTHENTICATE = 0x86.toByte()
    
    // Passport AID
    val PASSPORT_AID = byteArrayOf(
        0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x02.toByte(), 0x47.toByte(), 0x10.toByte(), 0x01.toByte()
    )
    
    /**
     * Represents the result of APDU command parsing.
     */
    data class ApduParseResult(
        val commandType: ApduCommandType,
        val isValid: Boolean,
        val errorResponse: ByteArray? = null,
        val data: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ApduParseResult) return false
            
            if (commandType != other.commandType) return false
            if (isValid != other.isValid) return false
            if (errorResponse != null) {
                if (other.errorResponse == null) return false
                if (!errorResponse.contentEquals(other.errorResponse)) return false
            } else if (other.errorResponse != null) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = commandType.hashCode()
            result = 31 * result + isValid.hashCode()
            result = 31 * result + (errorResponse?.contentHashCode() ?: 0)
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }
    }
    
    /**
     * Types of APDU commands supported by the passport simulator.
     */
    enum class ApduCommandType {
        SELECT,
        READ_BINARY,
        GET_CHALLENGE,
        EXTERNAL_AUTHENTICATE,
        INTERNAL_AUTHENTICATE,
        MSE_SET_AT,
        GENERAL_AUTHENTICATE,
        UNSUPPORTED,
        INVALID
    }
    
    /**
     * Parses an APDU command and returns the parsing result.
     */
    fun parseApduCommand(apdu: ByteArray?): ApduParseResult {
        if (apdu == null || apdu.isEmpty()) {
            return ApduParseResult(
                commandType = ApduCommandType.INVALID,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        if (apdu.size < 4) {
            return ApduParseResult(
                commandType = ApduCommandType.INVALID,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val cla = apdu[0]
        val ins = apdu[1]
        
        return when {
            cla != CLA_ISO7816 -> ApduParseResult(
                commandType = ApduCommandType.INVALID,
                isValid = false,
                errorResponse = SW_CLASS_NOT_SUPPORTED
            )
            ins == INS_SELECT -> parseSelectCommand(apdu)
            ins == INS_READ_BINARY -> parseReadBinaryCommand(apdu)
            ins == INS_GET_CHALLENGE -> parseGetChallengeCommand(apdu)
            ins == INS_EXTERNAL_AUTHENTICATE -> parseExternalAuthenticateCommand(apdu)
            ins == INS_INTERNAL_AUTHENTICATE -> parseInternalAuthenticateCommand(apdu)
            ins == INS_MSE_SET_AT -> parseMseSetAtCommand(apdu)
            ins == INS_GENERAL_AUTHENTICATE -> parseGeneralAuthenticateCommand(apdu)
            else -> ApduParseResult(
                commandType = ApduCommandType.UNSUPPORTED,
                isValid = false,
                errorResponse = SW_INSTRUCTION_NOT_SUPPORTED
            )
        }
    }
    
    /**
     * Parses a SELECT command.
     */
    private fun parseSelectCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 5) {
            return ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) {
            return ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val aidData = apdu.sliceArray(5 until 5 + lc)
        
        return if (aidData.contentEquals(PASSPORT_AID)) {
            ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = true,
                data = aidData
            )
        } else {
            ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = false,
                errorResponse = SW_FILE_NOT_FOUND,
                data = aidData
            )
        }
    }
    
    /**
     * Parses a READ BINARY command.
     */
    private fun parseReadBinaryCommand(apdu: ByteArray): ApduParseResult {
        return ApduParseResult(
            commandType = ApduCommandType.READ_BINARY,
            isValid = true
        )
    }
    
    /**
     * Parses a GET CHALLENGE command.
     */
    private fun parseGetChallengeCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 5) {
            return ApduParseResult(
                commandType = ApduCommandType.GET_CHALLENGE,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val le = apdu[4].toInt() and 0xFF
        if (le != 8) {
            return ApduParseResult(
                commandType = ApduCommandType.GET_CHALLENGE,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        return ApduParseResult(
            commandType = ApduCommandType.GET_CHALLENGE,
            isValid = true
        )
    }
    
    /**
     * Parses an EXTERNAL AUTHENTICATE command.
     */
    private fun parseExternalAuthenticateCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 5) {
            return ApduParseResult(
                commandType = ApduCommandType.EXTERNAL_AUTHENTICATE,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val lc = apdu[4].toInt() and 0xFF
        if (lc > 0) {
            if (apdu.size < 5 + lc) {
                return ApduParseResult(
                    commandType = ApduCommandType.EXTERNAL_AUTHENTICATE,
                    isValid = false,
                    errorResponse = SW_WRONG_LENGTH
                )
            }
            
            val authData = apdu.sliceArray(5 until 5 + lc)
            return ApduParseResult(
                commandType = ApduCommandType.EXTERNAL_AUTHENTICATE,
                isValid = true,
                data = authData
            )
        }
        
        return ApduParseResult(
            commandType = ApduCommandType.EXTERNAL_AUTHENTICATE,
            isValid = true
        )
    }
    
    /**
     * Parses an INTERNAL AUTHENTICATE command.
     */
    private fun parseInternalAuthenticateCommand(apdu: ByteArray): ApduParseResult {
        return ApduParseResult(
            commandType = ApduCommandType.INTERNAL_AUTHENTICATE,
            isValid = true
        )
    }
    
    /**
     * Parses an MSE SET AT command for PACE protocol.
     */
    private fun parseMseSetAtCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 4) {
            return ApduParseResult(
                commandType = ApduCommandType.MSE_SET_AT,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val p1 = apdu[2]
        val p2 = apdu[3]
        
        // Check for PACE-specific MSE SET AT parameters
        if (p1 == 0x81.toByte() && p2 == 0xB6.toByte()) {
            val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
            val data = if (lc > 0 && apdu.size >= 5 + lc) {
                apdu.sliceArray(5 until 5 + lc)
            } else {
                byteArrayOf()
            }
            
            return ApduParseResult(
                commandType = ApduCommandType.MSE_SET_AT,
                isValid = true,
                data = data
            )
        }
        
        return ApduParseResult(
            commandType = ApduCommandType.MSE_SET_AT,
            isValid = false,
            errorResponse = SW_INSTRUCTION_NOT_SUPPORTED
        )
    }
    
    /**
     * Parses a GENERAL AUTHENTICATE command for PACE protocol.
     */
    private fun parseGeneralAuthenticateCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 4) {
            return ApduParseResult(
                commandType = ApduCommandType.GENERAL_AUTHENTICATE,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        val data = if (lc > 0 && apdu.size >= 5 + lc) {
            apdu.sliceArray(5 until 5 + lc)
        } else {
            byteArrayOf()
        }
        
        return ApduParseResult(
            commandType = ApduCommandType.GENERAL_AUTHENTICATE,
            isValid = true,
            data = data
        )
    }
    
    /**
     * Generates a challenge response for GET CHALLENGE command.
     */
    fun generateChallengeResponse(): ByteArray {
        val challenge = ByteArray(8) { (kotlin.random.Random.nextInt(256)).toByte() }
        return challenge + SW_SUCCESS
    }
    
    /**
     * Extension function to convert ByteArray to hex string.
     */
    fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}