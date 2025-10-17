package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError
import kotlin.test.*

class ErrorCodeMapperTest {
    
    @Test
    fun testMapError_validationErrors() {
        // Test InvalidPassportNumber
        val passportError = SimulatorError.ValidationError.InvalidPassportNumber
        val passportResponse = ErrorCodeMapper.mapError(passportError)
        
        assertEquals(ErrorCodeMapper.SW_WRONG_DATA, passportResponse.statusWord)
        assertEquals("INVALID_PASSPORT_NUMBER", passportResponse.errorCode)
        assertTrue(passportResponse.recoverable)
        
        // Test InvalidDateOfBirth
        val dobError = SimulatorError.ValidationError.InvalidDateOfBirth
        val dobResponse = ErrorCodeMapper.mapError(dobError)
        
        assertEquals(ErrorCodeMapper.SW_WRONG_DATA, dobResponse.statusWord)
        assertEquals("INVALID_DATE_OF_BIRTH", dobResponse.errorCode)
        assertTrue(dobResponse.recoverable)
        
        // Test MissingRequiredFields
        val missingError = SimulatorError.ValidationError.MissingRequiredFields
        val missingResponse = ErrorCodeMapper.mapError(missingError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, missingResponse.statusWord)
        assertEquals("MISSING_REQUIRED_FIELDS", missingResponse.errorCode)
        assertTrue(missingResponse.recoverable)
    }
    
    @Test
    fun testMapError_nfcErrors() {
        // Test NfcNotAvailable
        val notAvailableError = SimulatorError.NfcError.NfcNotAvailable
        val notAvailableResponse = ErrorCodeMapper.mapError(notAvailableError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, notAvailableResponse.statusWord)
        assertEquals("NFC_NOT_AVAILABLE", notAvailableResponse.errorCode)
        assertFalse(notAvailableResponse.recoverable)
        
        // Test PermissionDenied
        val permissionError = SimulatorError.NfcError.PermissionDenied
        val permissionResponse = ErrorCodeMapper.mapError(permissionError)
        
        assertEquals(ErrorCodeMapper.SW_SECURITY_STATUS_NOT_SATISFIED, permissionResponse.statusWord)
        assertEquals("NFC_PERMISSION_DENIED", permissionResponse.errorCode)
        assertTrue(permissionResponse.recoverable)
        
        // Test ConnectionFailed with retry delay
        val connectionError = SimulatorError.NfcError.ConnectionFailed("Timeout")
        val connectionResponse = ErrorCodeMapper.mapError(connectionError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, connectionResponse.statusWord)
        assertEquals("NFC_CONNECTION_FAILED", connectionResponse.errorCode)
        assertTrue(connectionResponse.recoverable)
        assertEquals(2000L, connectionResponse.retryAfterMs)
        assertEquals("Timeout", connectionResponse.details["reason"])
    }
    
    @Test
    fun testMapError_protocolErrors() {
        // Test BacAuthenticationFailed
        val bacError = SimulatorError.ProtocolError.BacAuthenticationFailed
        val bacResponse = ErrorCodeMapper.mapError(bacError)
        
        assertEquals(ErrorCodeMapper.SW_BAC_AUTHENTICATION_FAILED, bacResponse.statusWord)
        assertEquals("BAC_AUTHENTICATION_FAILED", bacResponse.errorCode)
        assertTrue(bacResponse.recoverable)
        assertEquals(3000L, bacResponse.retryAfterMs)
        assertEquals("BAC", bacResponse.details["protocol"])
        
        // Test PaceAuthenticationFailed
        val paceError = SimulatorError.ProtocolError.PaceAuthenticationFailed
        val paceResponse = ErrorCodeMapper.mapError(paceError)
        
        assertEquals(ErrorCodeMapper.SW_PACE_AUTHENTICATION_FAILED, paceResponse.statusWord)
        assertEquals("PACE_AUTHENTICATION_FAILED", paceResponse.errorCode)
        assertTrue(paceResponse.recoverable)
        assertEquals("PACE", paceResponse.details["protocol"])
        
        // Test InvalidApduCommand
        val apduError = SimulatorError.ProtocolError.InvalidApduCommand("00A40400")
        val apduResponse = ErrorCodeMapper.mapError(apduError)
        
        assertEquals(ErrorCodeMapper.SW_INS_NOT_SUPPORTED, apduResponse.statusWord)
        assertEquals("INVALID_APDU_COMMAND", apduResponse.errorCode)
        assertFalse(apduResponse.recoverable)
        assertEquals("00A40400", apduResponse.details["command"])
        
        // Test ProtocolViolation
        val violationError = SimulatorError.ProtocolError.ProtocolViolation("BAC", "Invalid state")
        val violationResponse = ErrorCodeMapper.mapError(violationError)
        
        assertEquals(ErrorCodeMapper.SW_PROTOCOL_VIOLATION, violationResponse.statusWord)
        assertEquals("PROTOCOL_VIOLATION", violationResponse.errorCode)
        assertFalse(violationResponse.recoverable)
        assertEquals("BAC", violationResponse.details["protocol"])
        assertEquals("Invalid state", violationResponse.details["violation"])
        
        // Test CryptographicError
        val cryptoError = SimulatorError.ProtocolError.CryptographicError("Key derivation", RuntimeException())
        val cryptoResponse = ErrorCodeMapper.mapError(cryptoError)
        
        assertEquals(ErrorCodeMapper.SW_CRYPTOGRAPHIC_ERROR, cryptoResponse.statusWord)
        assertEquals("CRYPTOGRAPHIC_ERROR", cryptoResponse.errorCode)
        assertTrue(cryptoResponse.recoverable)
        assertEquals(1000L, cryptoResponse.retryAfterMs)
        assertEquals("Key derivation", cryptoResponse.details["operation"])
    }
    
    @Test
    fun testMapError_systemErrors() {
        // Test LibraryInitializationFailed
        val libError = SimulatorError.SystemError.LibraryInitializationFailed("SCUBA", RuntimeException())
        val libResponse = ErrorCodeMapper.mapError(libError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, libResponse.statusWord)
        assertEquals("LIBRARY_INITIALIZATION_FAILED", libResponse.errorCode)
        assertTrue(libResponse.recoverable)
        assertEquals(5000L, libResponse.retryAfterMs)
        assertEquals("SCUBA", libResponse.details["library"])
        
        // Test UnexpectedError
        val unexpectedError = SimulatorError.SystemError.UnexpectedError(RuntimeException("Test"))
        val unexpectedResponse = ErrorCodeMapper.mapError(unexpectedError)
        
        assertEquals(ErrorCodeMapper.SW_UNKNOWN, unexpectedResponse.statusWord)
        assertEquals("UNEXPECTED_ERROR", unexpectedResponse.errorCode)
        assertTrue(unexpectedResponse.recoverable)
        assertEquals(2000L, unexpectedResponse.retryAfterMs)
        
        // Test SimulationAlreadyRunning
        val runningError = SimulatorError.SystemError.SimulationAlreadyRunning
        val runningResponse = ErrorCodeMapper.mapError(runningError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, runningResponse.statusWord)
        assertEquals("SIMULATION_ALREADY_RUNNING", runningResponse.errorCode)
        assertFalse(runningResponse.recoverable)
        
        // Test ConfigurationError
        val configError = SimulatorError.SystemError.ConfigurationError("Invalid setting")
        val configResponse = ErrorCodeMapper.mapError(configError)
        
        assertEquals(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED, configResponse.statusWord)
        assertEquals("CONFIGURATION_ERROR", configResponse.errorCode)
        assertTrue(configResponse.recoverable)
        assertEquals("Invalid setting", configResponse.details["setting"])
    }
    
    @Test
    fun testMapError_customValidationError() {
        // Test CustomValidation
        val customError = SimulatorError.ValidationError.CustomValidation("birthDate", "Future date not allowed")
        val customResponse = ErrorCodeMapper.mapError(customError)
        
        assertEquals(ErrorCodeMapper.SW_WRONG_DATA, customResponse.statusWord)
        assertEquals("CUSTOM_VALIDATION_ERROR", customResponse.errorCode)
        assertTrue(customResponse.recoverable)
        assertEquals("birthDate", customResponse.details["field"])
        assertEquals("Future date not allowed", customResponse.details["reason"])
    }
    
    @Test
    fun testMapError_withContext() {
        // Test error mapping with additional context
        val error = SimulatorError.NfcError.ServiceError("Connection lost", null)
        val context = mapOf(
            "sessionId" to "test-session-123",
            "timestamp" to "2024-01-01T12:00:00Z"
        )
        
        val response = ErrorCodeMapper.mapError(error, context)
        
        assertEquals(ErrorCodeMapper.SW_UNKNOWN, response.statusWord)
        assertEquals("NFC_SERVICE_ERROR", response.errorCode)
        assertTrue(response.recoverable)
        assertEquals("Connection lost", response.details["reason"])
        assertEquals("test-session-123", response.details["sessionId"])
        assertEquals("2024-01-01T12:00:00Z", response.details["timestamp"])
    }
    
    @Test
    fun testCreateTimeoutError() {
        // Given
        val operation = "BAC_AUTHENTICATION"
        val timeoutMs = 30000L
        
        // When
        val response = ErrorCodeMapper.createTimeoutError(operation, timeoutMs)
        
        // Then
        assertEquals(ErrorCodeMapper.SW_TIMEOUT_ERROR, response.statusWord)
        assertEquals("OPERATION_TIMEOUT", response.errorCode)
        assertEquals("Operation timed out: $operation", response.message)
        assertTrue(response.recoverable)
        assertEquals(15000L, response.retryAfterMs) // timeoutMs / 2
        assertEquals(operation, response.details["operation"])
        assertEquals(timeoutMs, response.details["timeoutMs"])
    }
    
    @Test
    fun testCreateRecoveryFailedError() {
        // Given
        val operation = "PACE_KEY_AGREEMENT"
        val attempts = 3
        
        // When
        val response = ErrorCodeMapper.createRecoveryFailedError(operation, attempts)
        
        // Then
        assertEquals(ErrorCodeMapper.SW_RECOVERY_FAILED, response.statusWord)
        assertEquals("RECOVERY_FAILED", response.errorCode)
        assertEquals("Recovery failed for operation: $operation", response.message)
        assertFalse(response.recoverable)
        assertEquals(operation, response.details["operation"])
        assertEquals(attempts, response.details["attempts"])
    }
    
    @Test
    fun testCreateSuccessResponse() {
        // Test success response without data
        val emptyResponse = ErrorCodeMapper.createSuccessResponse()
        assertEquals(2, emptyResponse.size)
        assertEquals(0x90.toByte(), emptyResponse[0]) // SW1
        assertEquals(0x00.toByte(), emptyResponse[1]) // SW2
        
        // Test success response with data
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val dataResponse = ErrorCodeMapper.createSuccessResponse(data)
        assertEquals(5, dataResponse.size)
        assertEquals(0x01.toByte(), dataResponse[0])
        assertEquals(0x02.toByte(), dataResponse[1])
        assertEquals(0x03.toByte(), dataResponse[2])
        assertEquals(0x90.toByte(), dataResponse[3]) // SW1
        assertEquals(0x00.toByte(), dataResponse[4]) // SW2
    }
    
    @Test
    fun testErrorResponseToByteArray() {
        // Given
        val error = SimulatorError.ProtocolError.BacAuthenticationFailed
        val response = ErrorCodeMapper.mapError(error)
        
        // When
        val bytes = response.toByteArray()
        
        // Then
        assertEquals(2, bytes.size)
        val statusWord = (bytes[0].toInt() and 0xFF shl 8) or (bytes[1].toInt() and 0xFF)
        assertEquals(ErrorCodeMapper.SW_BAC_AUTHENTICATION_FAILED, statusWord)
    }
    
    @Test
    fun testErrorResponseToHexString() {
        // Given
        val error = SimulatorError.ProtocolError.PaceAuthenticationFailed
        val response = ErrorCodeMapper.mapError(error)
        
        // When
        val hexString = response.toHexString()
        
        // Then
        assertEquals("0x6301", hexString)
    }
    
    @Test
    fun testIsSuccess() {
        assertTrue(ErrorCodeMapper.isSuccess(ErrorCodeMapper.SW_NO_ERROR))
        assertFalse(ErrorCodeMapper.isSuccess(ErrorCodeMapper.SW_WRONG_DATA))
        assertFalse(ErrorCodeMapper.isSuccess(ErrorCodeMapper.SW_BAC_AUTHENTICATION_FAILED))
    }
    
    @Test
    fun testIsRecoverable() {
        // Recoverable errors
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_BAC_AUTHENTICATION_FAILED))
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_PACE_AUTHENTICATION_FAILED))
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_CRYPTOGRAPHIC_ERROR))
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_TIMEOUT_ERROR))
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_CONDITIONS_NOT_SATISFIED))
        assertTrue(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_SECURITY_STATUS_NOT_SATISFIED))
        
        // Non-recoverable errors
        assertFalse(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_INS_NOT_SUPPORTED))
        assertFalse(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_CLA_NOT_SUPPORTED))
        assertFalse(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_FILE_NOT_FOUND))
        assertFalse(ErrorCodeMapper.isRecoverable(ErrorCodeMapper.SW_PROTOCOL_VIOLATION))
    }
    
    @Test
    fun testGetStatusDescription() {
        assertEquals("Success", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_NO_ERROR))
        assertEquals("Wrong length", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_WRONG_LENGTH))
        assertEquals("Security status not satisfied", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_SECURITY_STATUS_NOT_SATISFIED))
        assertEquals("BAC authentication failed", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_BAC_AUTHENTICATION_FAILED))
        assertEquals("PACE authentication failed", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_PACE_AUTHENTICATION_FAILED))
        assertEquals("Cryptographic error", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_CRYPTOGRAPHIC_ERROR))
        assertEquals("Operation timeout", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_TIMEOUT_ERROR))
        assertEquals("Recovery failed", ErrorCodeMapper.getStatusDescription(ErrorCodeMapper.SW_RECOVERY_FAILED))
        
        // Test unknown status word
        val unknownStatus = 0x1234
        val unknownDescription = ErrorCodeMapper.getStatusDescription(unknownStatus)
        assertTrue(unknownDescription.contains("Unknown status word"))
        assertTrue(unknownDescription.contains("0x1234"))
    }
}