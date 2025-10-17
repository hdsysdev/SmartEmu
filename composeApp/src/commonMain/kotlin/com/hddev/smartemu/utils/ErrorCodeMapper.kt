package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError

/**
 * Maps simulator errors to standardized ISO 7816 status words and error codes.
 * Provides consistent error responses for authentication failures and protocol violations.
 */
object ErrorCodeMapper {
    
    // ISO 7816-4 Status Words
    const val SW_NO_ERROR = 0x9000
    const val SW_BYTES_REMAINING_00 = 0x6100
    const val SW_STATE_NON_VOLATILE_MEMORY_UNCHANGED = 0x6200
    const val SW_STATE_NON_VOLATILE_MEMORY_CHANGED = 0x6300
    const val SW_STATE_NON_VOLATILE_MEMORY_UNCHANGED_2 = 0x6400
    const val SW_STATE_NON_VOLATILE_MEMORY_CHANGED_2 = 0x6500
    const val SW_WRONG_LENGTH = 0x6700
    const val SW_LOGICAL_CHANNEL_NOT_SUPPORTED = 0x6881
    const val SW_SECURE_MESSAGING_NOT_SUPPORTED = 0x6882
    const val SW_LAST_COMMAND_EXPECTED = 0x6883
    const val SW_COMMAND_CHAINING_NOT_SUPPORTED = 0x6884
    const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982
    const val SW_FILE_INVALID = 0x6983
    const val SW_DATA_INVALID = 0x6984
    const val SW_CONDITIONS_NOT_SATISFIED = 0x6985
    const val SW_COMMAND_NOT_ALLOWED = 0x6986
    const val SW_EXPECTED_SM_DATA_OBJECTS_MISSING = 0x6987
    const val SW_SM_DATA_OBJECTS_INCORRECT = 0x6988
    const val SW_APPLET_SELECT_FAILED = 0x6999
    const val SW_WRONG_DATA = 0x6A80
    const val SW_FUNC_NOT_SUPPORTED = 0x6A81
    const val SW_FILE_NOT_FOUND = 0x6A82
    const val SW_RECORD_NOT_FOUND = 0x6A83
    const val SW_INSUFFICIENT_MEMORY_SPACE = 0x6A84
    const val SW_NC_INCONSISTENT_WITH_TLV = 0x6A85
    const val SW_INCORRECT_P1P2 = 0x6A86
    const val SW_NC_INCONSISTENT_WITH_P1P2 = 0x6A87
    const val SW_REFERENCED_DATA_NOT_FOUND = 0x6A88
    const val SW_FILE_ALREADY_EXISTS = 0x6A89
    const val SW_DF_NAME_ALREADY_EXISTS = 0x6A8A
    const val SW_WRONG_P1P2 = 0x6B00
    const val SW_CORRECT_LENGTH_00 = 0x6C00
    const val SW_INS_NOT_SUPPORTED = 0x6D00
    const val SW_CLA_NOT_SUPPORTED = 0x6E00
    const val SW_UNKNOWN = 0x6F00
    
    // Custom error codes for passport simulation
    const val SW_BAC_AUTHENTICATION_FAILED = 0x6300
    const val SW_PACE_AUTHENTICATION_FAILED = 0x6301
    const val SW_INVALID_MRZ_DATA = 0x6302
    const val SW_CRYPTOGRAPHIC_ERROR = 0x6303
    const val SW_PROTOCOL_VIOLATION = 0x6304
    const val SW_TIMEOUT_ERROR = 0x6305
    const val SW_RECOVERY_FAILED = 0x6306
    
    /**
     * Detailed error response with status word and additional information.
     */
    data class ErrorResponse(
        val statusWord: Int,
        val errorCode: String,
        val message: String,
        val details: Map<String, Any> = emptyMap(),
        val recoverable: Boolean = false,
        val retryAfterMs: Long? = null
    ) {
        /**
         * Converts the status word to a byte array for APDU response.
         */
        fun toByteArray(): ByteArray {
            val sw1 = (statusWord shr 8).toByte()
            val sw2 = (statusWord and 0xFF).toByte()
            return byteArrayOf(sw1, sw2)
        }
        
        /**
         * Returns a hex string representation of the status word.
         */
        fun toHexString(): String {
            return String.format("0x%04X", statusWord)
        }
    }
    
    /**
     * Maps a SimulatorError to an appropriate error response.
     */
    fun mapError(error: SimulatorError, context: Map<String, Any> = emptyMap()): ErrorResponse {
        return when (error) {
            // Validation Errors
            is SimulatorError.ValidationError.InvalidPassportNumber -> ErrorResponse(
                statusWord = SW_WRONG_DATA,
                errorCode = "INVALID_PASSPORT_NUMBER",
                message = "Invalid passport number format",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.ValidationError.InvalidDateOfBirth -> ErrorResponse(
                statusWord = SW_WRONG_DATA,
                errorCode = "INVALID_DATE_OF_BIRTH",
                message = "Invalid date of birth",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.ValidationError.InvalidExpiryDate -> ErrorResponse(
                statusWord = SW_WRONG_DATA,
                errorCode = "INVALID_EXPIRY_DATE",
                message = "Invalid expiry date",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.ValidationError.MissingRequiredFields -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "MISSING_REQUIRED_FIELDS",
                message = "Required passport fields are missing",
                details = context,
                recoverable = true
            )
            
            // NFC Errors
            is SimulatorError.NfcError.NfcNotAvailable -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "NFC_NOT_AVAILABLE",
                message = "NFC hardware is not available",
                details = context,
                recoverable = false
            )
            
            is SimulatorError.NfcError.NfcDisabled -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "NFC_DISABLED",
                message = "NFC is disabled in device settings",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.NfcError.PermissionDenied -> ErrorResponse(
                statusWord = SW_SECURITY_STATUS_NOT_SATISFIED,
                errorCode = "NFC_PERMISSION_DENIED",
                message = "NFC permissions have been denied",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.NfcError.ConnectionFailed -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "NFC_CONNECTION_FAILED",
                message = "NFC connection failed: ${error.reason}",
                details = context + mapOf("reason" to error.reason),
                recoverable = true,
                retryAfterMs = 2000L
            )
            
            is SimulatorError.NfcError.ServiceError -> ErrorResponse(
                statusWord = SW_UNKNOWN,
                errorCode = "NFC_SERVICE_ERROR",
                message = "NFC service error: ${error.reason}",
                details = context + mapOf("reason" to error.reason),
                recoverable = true,
                retryAfterMs = 1000L
            )
            
            is SimulatorError.NfcError.HceNotSupported -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "HCE_NOT_SUPPORTED",
                message = "Host Card Emulation is not supported on this device",
                details = context,
                recoverable = false
            )
            
            // Protocol Errors
            is SimulatorError.ProtocolError.BacAuthenticationFailed -> ErrorResponse(
                statusWord = SW_BAC_AUTHENTICATION_FAILED,
                errorCode = "BAC_AUTHENTICATION_FAILED",
                message = "BAC authentication failed",
                details = context + mapOf("protocol" to "BAC"),
                recoverable = true,
                retryAfterMs = 3000L
            )
            
            is SimulatorError.ProtocolError.PaceAuthenticationFailed -> ErrorResponse(
                statusWord = SW_PACE_AUTHENTICATION_FAILED,
                errorCode = "PACE_AUTHENTICATION_FAILED",
                message = "PACE authentication failed",
                details = context + mapOf("protocol" to "PACE"),
                recoverable = true,
                retryAfterMs = 3000L
            )
            
            is SimulatorError.ProtocolError.InvalidApduCommand -> ErrorResponse(
                statusWord = SW_INS_NOT_SUPPORTED,
                errorCode = "INVALID_APDU_COMMAND",
                message = "Invalid APDU command: ${error.command}",
                details = context + mapOf("command" to error.command),
                recoverable = false
            )
            
            is SimulatorError.ProtocolError.ProtocolViolation -> ErrorResponse(
                statusWord = SW_PROTOCOL_VIOLATION,
                errorCode = "PROTOCOL_VIOLATION",
                message = "Protocol violation in ${error.protocol}: ${error.reason}",
                details = context + mapOf(
                    "protocol" to error.protocol,
                    "violation" to error.reason
                ),
                recoverable = false
            )
            
            is SimulatorError.ProtocolError.CryptographicError -> ErrorResponse(
                statusWord = SW_CRYPTOGRAPHIC_ERROR,
                errorCode = "CRYPTOGRAPHIC_ERROR",
                message = "Cryptographic error during ${error.operation}",
                details = context + mapOf("operation" to error.operation),
                recoverable = true,
                retryAfterMs = 1000L
            )
            
            // System Errors
            is SimulatorError.SystemError.LibraryInitializationFailed -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "LIBRARY_INITIALIZATION_FAILED",
                message = "Failed to initialize ${error.library} library",
                details = context + mapOf("library" to error.library),
                recoverable = true,
                retryAfterMs = 5000L
            )
            
            is SimulatorError.SystemError.UnexpectedError -> ErrorResponse(
                statusWord = SW_UNKNOWN,
                errorCode = "UNEXPECTED_ERROR",
                message = "Unexpected error occurred",
                details = context,
                recoverable = true,
                retryAfterMs = 2000L
            )
            
            is SimulatorError.SystemError.SimulationAlreadyRunning -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "SIMULATION_ALREADY_RUNNING",
                message = "Simulation is already running",
                details = context,
                recoverable = false
            )
            
            is SimulatorError.SystemError.SimulationNotRunning -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "SIMULATION_NOT_RUNNING",
                message = "Simulation is not currently running",
                details = context,
                recoverable = true
            )
            
            is SimulatorError.SystemError.ConfigurationError -> ErrorResponse(
                statusWord = SW_CONDITIONS_NOT_SATISFIED,
                errorCode = "CONFIGURATION_ERROR",
                message = "Configuration error: ${error.setting}",
                details = context + mapOf("setting" to error.setting),
                recoverable = true
            )
            
            // Custom validation errors
            is SimulatorError.ValidationError.CustomValidation -> ErrorResponse(
                statusWord = SW_WRONG_DATA,
                errorCode = "CUSTOM_VALIDATION_ERROR",
                message = "Validation failed for ${error.field}: ${error.reason}",
                details = context + mapOf(
                    "field" to error.field,
                    "reason" to error.reason
                ),
                recoverable = true
            )
            
            // Fallback for other validation errors
            is SimulatorError.ValidationError -> ErrorResponse(
                statusWord = SW_WRONG_DATA,
                errorCode = "VALIDATION_ERROR",
                message = error.message ?: "Unknown validation error",
                details = context,
                recoverable = true
            )
        }
    }
    
    /**
     * Creates a timeout error response.
     */
    fun createTimeoutError(operation: String, timeoutMs: Long): ErrorResponse {
        return ErrorResponse(
            statusWord = SW_TIMEOUT_ERROR,
            errorCode = "OPERATION_TIMEOUT",
            message = "Operation timed out: $operation",
            details = mapOf(
                "operation" to operation,
                "timeoutMs" to timeoutMs
            ),
            recoverable = true,
            retryAfterMs = timeoutMs / 2
        )
    }
    
    /**
     * Creates a recovery failure error response.
     */
    fun createRecoveryFailedError(operation: String, attempts: Int): ErrorResponse {
        return ErrorResponse(
            statusWord = SW_RECOVERY_FAILED,
            errorCode = "RECOVERY_FAILED",
            message = "Recovery failed for operation: $operation",
            details = mapOf(
                "operation" to operation,
                "attempts" to attempts
            ),
            recoverable = false
        )
    }
    
    /**
     * Creates a success response.
     */
    fun createSuccessResponse(data: ByteArray = byteArrayOf()): ByteArray {
        return data + byteArrayOf(
            (SW_NO_ERROR shr 8).toByte(),
            (SW_NO_ERROR and 0xFF).toByte()
        )
    }
    
    /**
     * Checks if a status word indicates success.
     */
    fun isSuccess(statusWord: Int): Boolean {
        return statusWord == SW_NO_ERROR
    }
    
    /**
     * Checks if a status word indicates a recoverable error.
     */
    fun isRecoverable(statusWord: Int): Boolean {
        return when (statusWord) {
            SW_BAC_AUTHENTICATION_FAILED,
            SW_PACE_AUTHENTICATION_FAILED,
            SW_CRYPTOGRAPHIC_ERROR,
            SW_TIMEOUT_ERROR,
            SW_CONDITIONS_NOT_SATISFIED,
            SW_SECURITY_STATUS_NOT_SATISFIED -> true
            else -> false
        }
    }
    
    /**
     * Gets a human-readable description of a status word.
     */
    fun getStatusDescription(statusWord: Int): String {
        return when (statusWord) {
            SW_NO_ERROR -> "Success"
            SW_WRONG_LENGTH -> "Wrong length"
            SW_SECURITY_STATUS_NOT_SATISFIED -> "Security status not satisfied"
            SW_CONDITIONS_NOT_SATISFIED -> "Conditions not satisfied"
            SW_WRONG_DATA -> "Wrong data"
            SW_FILE_NOT_FOUND -> "File not found"
            SW_INS_NOT_SUPPORTED -> "Instruction not supported"
            SW_CLA_NOT_SUPPORTED -> "Class not supported"
            SW_BAC_AUTHENTICATION_FAILED -> "BAC authentication failed"
            SW_PACE_AUTHENTICATION_FAILED -> "PACE authentication failed"
            SW_INVALID_MRZ_DATA -> "Invalid MRZ data"
            SW_CRYPTOGRAPHIC_ERROR -> "Cryptographic error"
            SW_PROTOCOL_VIOLATION -> "Protocol violation"
            SW_TIMEOUT_ERROR -> "Operation timeout"
            SW_RECOVERY_FAILED -> "Recovery failed"
            SW_UNKNOWN -> "Unknown error"
            else -> "Unknown status word: ${String.format("0x%04X", statusWord)}"
        }
    }
}