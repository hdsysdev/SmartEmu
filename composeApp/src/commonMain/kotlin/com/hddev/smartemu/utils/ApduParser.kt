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
    private const val CLA_COMMAND_CHAINING = 0x10.toByte()
    private const val INS_SELECT = 0xA4.toByte()
    private const val INS_READ_BINARY = 0xB0.toByte()
    private const val INS_GET_CHALLENGE = 0x84.toByte()
    private const val INS_EXTERNAL_AUTHENTICATE = 0x82.toByte()
    private const val INS_INTERNAL_AUTHENTICATE = 0x88.toByte()
    private const val INS_MSE_SET_AT = 0x22.toByte()
    private const val INS_GENERAL_AUTHENTICATE = 0x86.toByte()
    private const val INS_PSO = 0x2A.toByte()

    /** MSE P1: set for mutual authentication (PACE), internal authentication (CA), external authentication (TA). */
    const val MSE_P1_PACE = 0xC1
    const val MSE_P1_CHIP_AUTHENTICATION = 0x41
    const val MSE_P1_TERMINAL_AUTHENTICATION = 0x81
    
    // Passport (LDS1 eMRTD) application AID, ICAO 9303 part 10: A0 00 00 02 47 10 01
    val PASSPORT_AID = byteArrayOf(
        0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x02.toByte(), 0x47.toByte(), 0x10.toByte(), 0x01.toByte()
    )
    
    /**
     * Represents the result of APDU command parsing.
     */
    data class ApduParseResult(
        val commandType: ApduCommandType,
        val isValid: Boolean,
        val errorResponse: ByteArray? = null,
        val data: ByteArray? = null,
        val p1: Int = 0,
        val p2: Int = 0,
        val le: Int = 0
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
        /** MSE:Set AT, for PACE, Chip Authentication or Terminal Authentication as P1 says. */
        MSE_SET_AT,
        /** MSE:Set KAT, Chip Authentication with 3DES. */
        MSE_SET_KAT,
        /** MSE:Set DST, naming the key a terminal certificate is to be verified with. */
        MSE_SET_DST,
        /** PSO:Verify Certificate, a step of Terminal Authentication. */
        PSO_VERIFY_CERTIFICATE,
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
            // PACE sends its GENERAL AUTHENTICATE steps as a command chain
            cla == CLA_COMMAND_CHAINING && ins == INS_GENERAL_AUTHENTICATE -> parseGeneralAuthenticateCommand(apdu)
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
            ins == INS_PSO -> parsePsoCommand(apdu)
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
        if (apdu.size < 4) {
            return ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }

        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        
        // Case 1: Select by DF Name (P1=04, P2=0C) -> Existing logic
        if (p1 == 0x04) {
             if (apdu.size < 5) return ApduParseResult(ApduCommandType.SELECT, false, errorResponse = SW_WRONG_LENGTH)
             val lc = apdu[4].toInt() and 0xFF
             if (apdu.size < 5 + lc) return ApduParseResult(ApduCommandType.SELECT, false, errorResponse = SW_WRONG_LENGTH)
             val aidData = apdu.sliceArray(5 until 5 + lc)
             
             return if (aidData.contentEquals(PASSPORT_AID)) {
                ApduParseResult(ApduCommandType.SELECT, true, data = aidData, p1 = p1, p2 = p2)
             } else {
                ApduParseResult(ApduCommandType.SELECT, false, errorResponse = SW_FILE_NOT_FOUND, data = aidData, p1 = p1, p2 = p2)
             }
        }
        
        // Case 2: Select by File ID, either as EF under the current DF (P1=02) or MF/DF/EF (P1=00)
        if (p1 == 0x02 || p1 == 0x00) {
            if (apdu.size < 5) return ApduParseResult(ApduCommandType.SELECT, false, errorResponse = SW_WRONG_LENGTH)
            val lc = apdu[4].toInt() and 0xFF
            // File ID is typically 2 bytes
            if (lc != 2 || apdu.size < 5 + lc) return ApduParseResult(ApduCommandType.SELECT, false, errorResponse = SW_WRONG_LENGTH)
            
            val fileId = apdu.sliceArray(5 until 5 + lc)
            return ApduParseResult(
                commandType = ApduCommandType.SELECT,
                isValid = true,
                data = fileId,
                p1 = p1,
                p2 = p2
            )
        }
        
        return ApduParseResult(
             commandType = ApduCommandType.SELECT,
             isValid = false,
             errorResponse = SW_INSTRUCTION_NOT_SUPPORTED // Or strictly File Not Found/params error
        )
    }
    
    /**
     * Parses a READ BINARY command.
     */
    private fun parseReadBinaryCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 4) {
             return ApduParseResult(ApduCommandType.READ_BINARY, false, errorResponse = SW_WRONG_LENGTH)
        }
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        val le = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        
        return ApduParseResult(
            commandType = ApduCommandType.READ_BINARY,
            isValid = true,
            p1 = p1,
            p2 = p2,
            le = le
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
     * Parses an INTERNAL AUTHENTICATE command: its data is the reader's challenge, eight bytes for Active
     * Authentication.
     */
    private fun parseInternalAuthenticateCommand(apdu: ByteArray): ApduParseResult {
        val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        if (lc == 0 || apdu.size < 5 + lc) {
            return ApduParseResult(
                commandType = ApduCommandType.INTERNAL_AUTHENTICATE,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        return ApduParseResult(
            commandType = ApduCommandType.INTERNAL_AUTHENTICATE,
            isValid = true,
            data = apdu.sliceArray(5 until 5 + lc)
        )
    }
    
    /**
     * Parses an MSE (MANAGE SECURITY ENVIRONMENT) command: Set AT (P2=A4) for PACE (P1=C1), Chip Authentication
     * (P1=41) or Terminal Authentication (P1=81); Set KAT (41 A6) for Chip Authentication with 3DES; and Set DST
     * (81 B6) for Terminal Authentication.
     */
    private fun parseMseSetAtCommand(apdu: ByteArray): ApduParseResult {
        if (apdu.size < 4) {
            return ApduParseResult(
                commandType = ApduCommandType.MSE_SET_AT,
                isValid = false,
                errorResponse = SW_WRONG_LENGTH
            )
        }
        
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        val commandType = when {
            p2 == 0xA4 && p1 in setOf(MSE_P1_PACE, MSE_P1_CHIP_AUTHENTICATION, MSE_P1_TERMINAL_AUTHENTICATION) ->
                ApduCommandType.MSE_SET_AT
            p1 == MSE_P1_CHIP_AUTHENTICATION && p2 == 0xA6 -> ApduCommandType.MSE_SET_KAT
            p1 == MSE_P1_TERMINAL_AUTHENTICATION && p2 == 0xB6 -> ApduCommandType.MSE_SET_DST
            else -> return ApduParseResult(
                commandType = ApduCommandType.MSE_SET_AT,
                isValid = false,
                errorResponse = SW_INSTRUCTION_NOT_SUPPORTED
            )
        }
        return ApduParseResult(
            commandType = commandType,
            isValid = true,
            data = commandData(apdu),
            p1 = p1,
            p2 = p2
        )
    }

    /**
     * Parses a PERFORM SECURITY OPERATION command; only Verify Certificate (P1=00, P2=BE) is recognised.
     */
    private fun parsePsoCommand(apdu: ByteArray): ApduParseResult {
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        if (p1 != 0x00 || p2 != 0xBE) {
            return ApduParseResult(
                commandType = ApduCommandType.UNSUPPORTED,
                isValid = false,
                errorResponse = SW_INSTRUCTION_NOT_SUPPORTED
            )
        }
        return ApduParseResult(
            commandType = ApduCommandType.PSO_VERIFY_CERTIFICATE,
            isValid = true,
            data = commandData(apdu),
            p1 = p1,
            p2 = p2
        )
    }

    /** The command data of a short APDU, or none. */
    private fun commandData(apdu: ByteArray): ByteArray {
        val lc = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
        return if (lc > 0 && apdu.size >= 5 + lc) apdu.sliceArray(5 until 5 + lc) else byteArrayOf()
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