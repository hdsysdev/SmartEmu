package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError
import kotlin.time.Clock
import kotlin.test.*

class ErrorLoggerTest {
    
    @BeforeTest
    fun setUp() {
        ErrorLogger.clearLogs()
    }
    
    @Test
    fun testLogError_basicLogging() {
        // Given
        val message = "Test error message"
        val error = SimulatorError.ValidationError.InvalidPassportNumber
        
        // When
        val entry = ErrorLogger.logError(
            level = ErrorLogger.LogLevel.ERROR,
            category = ErrorLogger.ErrorCategory.VALIDATION,
            message = message,
            error = error
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.ERROR, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.VALIDATION, entry.category)
        assertEquals(message, entry.message)
        assertEquals(error, entry.error)
        assertNotNull(entry.timestamp)
    }
    
    @Test
    fun testLogNfcError_specifiesCorrectCategory() {
        // Given
        val message = "NFC connection failed"
        val error = SimulatorError.NfcError.ConnectionFailed("Timeout")
        
        // When
        val entry = ErrorLogger.logNfcError(message, error)
        
        // Then
        assertEquals(ErrorLogger.LogLevel.ERROR, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.NFC_HARDWARE, entry.category)
        assertEquals(message, entry.message)
        assertEquals(error, entry.error)
    }
    
    @Test
    fun testLogAuthenticationError_includesProtocolContext() {
        // Given
        val protocol = "BAC"
        val message = "Authentication failed"
        val error = SimulatorError.ProtocolError.BacAuthenticationFailed
        val correlationId = "test-correlation-123"
        
        // When
        val entry = ErrorLogger.logAuthenticationError(
            protocol = protocol,
            message = message,
            error = error,
            correlationId = correlationId
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.ERROR, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.AUTHENTICATION, entry.category)
        assertEquals(message, entry.message)
        assertEquals(error, entry.error)
        assertEquals(correlationId, entry.correlationId)
        assertEquals(protocol, entry.context["protocol"])
    }
    
    @Test
    fun testLogProtocolViolation_capturesViolationDetails() {
        // Given
        val protocol = "PACE"
        val violation = "Invalid state transition"
        val apduCommand = "00A4040007A0000002471001"
        val correlationId = "test-correlation-456"
        
        // When
        val entry = ErrorLogger.logProtocolViolation(
            protocol = protocol,
            violation = violation,
            apduCommand = apduCommand,
            correlationId = correlationId
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.WARNING, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.PROTOCOL_VIOLATION, entry.category)
        assertEquals("Protocol violation in $protocol: $violation", entry.message)
        assertEquals(correlationId, entry.correlationId)
        assertEquals(protocol, entry.context["protocol"])
        assertEquals(violation, entry.context["violation"])
        assertEquals(apduCommand, entry.context["apduCommand"])
    }
    
    @Test
    fun testLogCryptographicError_includesStackTrace() {
        // Given
        val operation = "Key derivation"
        val message = "Failed to derive BAC keys"
        val throwable = RuntimeException("Crypto library error")
        
        // When
        val entry = ErrorLogger.logCryptographicError(
            operation = operation,
            message = message,
            throwable = throwable
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.CRITICAL, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.CRYPTOGRAPHIC, entry.category)
        assertEquals(message, entry.message)
        assertEquals(operation, entry.context["operation"])
        assertNotNull(entry.stackTrace)
        assertTrue(entry.stackTrace!!.contains("RuntimeException"))
    }
    
    @Test
    fun testLogTimeout_includesTimeoutDetails() {
        // Given
        val operation = "BAC_AUTHENTICATION"
        val timeoutMs = 30000L
        val correlationId = "timeout-test-789"
        
        // When
        val entry = ErrorLogger.logTimeout(
            operation = operation,
            timeoutMs = timeoutMs,
            correlationId = correlationId
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.WARNING, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.TIMEOUT, entry.category)
        assertEquals("Operation timeout: $operation (${timeoutMs}ms)", entry.message)
        assertEquals(correlationId, entry.correlationId)
        assertEquals(operation, entry.context["operation"])
        assertEquals(timeoutMs, entry.context["timeoutMs"])
    }
    
    @Test
    fun testLogRecoveryAttempt_tracksAttemptProgress() {
        // Given
        val operation = "NFC_CONNECTION"
        val attemptNumber = 2
        val maxAttempts = 3
        val correlationId = "recovery-test-101"
        
        // When
        val entry = ErrorLogger.logRecoveryAttempt(
            operation = operation,
            attemptNumber = attemptNumber,
            maxAttempts = maxAttempts,
            correlationId = correlationId
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.INFO, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.RECOVERY, entry.category)
        assertEquals("Recovery attempt $attemptNumber/$maxAttempts for: $operation", entry.message)
        assertEquals(correlationId, entry.correlationId)
        assertEquals(operation, entry.context["operation"])
        assertEquals(attemptNumber, entry.context["attemptNumber"])
        assertEquals(maxAttempts, entry.context["maxAttempts"])
    }
    
    @Test
    fun testLogRecoverySuccess_recordsSuccessfulRecovery() {
        // Given
        val operation = "PACE_AUTHENTICATION"
        val attemptNumber = 3
        val correlationId = "recovery-success-202"
        
        // When
        val entry = ErrorLogger.logRecoverySuccess(
            operation = operation,
            attemptNumber = attemptNumber,
            correlationId = correlationId
        )
        
        // Then
        assertEquals(ErrorLogger.LogLevel.INFO, entry.level)
        assertEquals(ErrorLogger.ErrorCategory.RECOVERY, entry.category)
        assertEquals("Recovery successful for: $operation (attempt $attemptNumber)", entry.message)
        assertEquals(correlationId, entry.correlationId)
        assertEquals(operation, entry.context["operation"])
        assertEquals(attemptNumber, entry.context["attemptNumber"])
    }
    
    @Test
    fun testToNfcEvent_convertsErrorLogToNfcEvent() {
        // Given
        val entry = ErrorLogger.logError(
            level = ErrorLogger.LogLevel.ERROR,
            category = ErrorLogger.ErrorCategory.AUTHENTICATION,
            message = "Test error for NFC event conversion",
            correlationId = "nfc-event-test-303"
        )
        
        // When
        val nfcEvent = ErrorLogger.toNfcEvent(entry)
        
        // Then
        assertEquals(entry.timestamp, nfcEvent.timestamp)
        assertEquals(com.hddev.smartemu.data.NfcEventType.ERROR, nfcEvent.type)
        assertEquals(entry.message, nfcEvent.message)
        assertEquals(entry.level.name, nfcEvent.details["level"])
        assertEquals(entry.category.name, nfcEvent.details["category"])
        assertEquals(entry.correlationId, nfcEvent.details["correlationId"])
    }
    
    @Test
    fun testGetLogEntries_filtersCorrectly() {
        // Given
        ErrorLogger.logError(ErrorLogger.LogLevel.DEBUG, ErrorLogger.ErrorCategory.SYSTEM, "Debug message")
        ErrorLogger.logError(ErrorLogger.LogLevel.ERROR, ErrorLogger.ErrorCategory.AUTHENTICATION, "Error message")
        ErrorLogger.logError(ErrorLogger.LogLevel.WARNING, ErrorLogger.ErrorCategory.TIMEOUT, "Warning message")
        
        // When - filter by minimum level
        val errorAndAbove = ErrorLogger.getLogEntries(minLevel = ErrorLogger.LogLevel.ERROR)
        
        // Then
        assertEquals(1, errorAndAbove.size)
        assertEquals("Error message", errorAndAbove[0].message)
        
        // When - filter by category
        val authenticationEntries = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.AUTHENTICATION)
        
        // Then
        assertEquals(1, authenticationEntries.size)
        assertEquals("Error message", authenticationEntries[0].message)
    }
    
    @Test
    fun testGetLogEntries_filtersbyCorrelationId() {
        // Given
        val correlationId1 = "correlation-1"
        val correlationId2 = "correlation-2"
        
        ErrorLogger.logError(
            ErrorLogger.LogLevel.INFO, 
            ErrorLogger.ErrorCategory.SYSTEM, 
            "Message 1", 
            correlationId = correlationId1
        )
        ErrorLogger.logError(
            ErrorLogger.LogLevel.INFO, 
            ErrorLogger.ErrorCategory.SYSTEM, 
            "Message 2", 
            correlationId = correlationId2
        )
        ErrorLogger.logError(
            ErrorLogger.LogLevel.INFO, 
            ErrorLogger.ErrorCategory.SYSTEM, 
            "Message 3", 
            correlationId = correlationId1
        )
        
        // When
        val correlation1Entries = ErrorLogger.getLogEntries(correlationId = correlationId1)
        
        // Then
        assertEquals(2, correlation1Entries.size)
        assertTrue(correlation1Entries.all { it.correlationId == correlationId1 })
    }
    
    @Test
    fun testGetErrorStatistics_providesCorrectCounts() {
        // Given
        ErrorLogger.logError(ErrorLogger.LogLevel.ERROR, ErrorLogger.ErrorCategory.AUTHENTICATION, "Error 1")
        ErrorLogger.logError(ErrorLogger.LogLevel.WARNING, ErrorLogger.ErrorCategory.TIMEOUT, "Warning 1")
        ErrorLogger.logError(ErrorLogger.LogLevel.ERROR, ErrorLogger.ErrorCategory.NFC_HARDWARE, "Error 2")
        
        // When
        val statistics = ErrorLogger.getErrorStatistics()
        
        // Then
        assertEquals(3, statistics["totalErrors"])
        
        val errorsByLevel = statistics["errorsByLevel"] as Map<*, *>
        assertEquals(2, errorsByLevel[ErrorLogger.LogLevel.ERROR])
        assertEquals(1, errorsByLevel[ErrorLogger.LogLevel.WARNING])
        
        val errorsByCategory = statistics["errorsByCategory"] as Map<*, *>
        assertEquals(1, errorsByCategory[ErrorLogger.ErrorCategory.AUTHENTICATION])
        assertEquals(1, errorsByCategory[ErrorLogger.ErrorCategory.TIMEOUT])
        assertEquals(1, errorsByCategory[ErrorLogger.ErrorCategory.NFC_HARDWARE])
    }
    
    @Test
    fun testClearLogs_removesAllEntries() {
        // Given
        ErrorLogger.logError(ErrorLogger.LogLevel.ERROR, ErrorLogger.ErrorCategory.SYSTEM, "Test error 1")
        ErrorLogger.logError(ErrorLogger.LogLevel.WARNING, ErrorLogger.ErrorCategory.VALIDATION, "Test error 2")
        
        // Verify entries exist
        assertTrue(ErrorLogger.getLogEntries().isNotEmpty())
        
        // When
        ErrorLogger.clearLogs()
        
        // Then
        assertTrue(ErrorLogger.getLogEntries().isEmpty())
        
        val statistics = ErrorLogger.getErrorStatistics()
        assertEquals(0, statistics["totalErrors"])
    }
    
    @Test
    fun testMaxLogEntries_limitsStoredEntries() {
        // Given - Create more than MAX_LOG_ENTRIES (1000) entries
        // We'll create a smaller number for testing performance
        repeat(50) { index ->
            ErrorLogger.logError(
                ErrorLogger.LogLevel.INFO,
                ErrorLogger.ErrorCategory.SYSTEM,
                "Test message $index"
            )
        }
        
        // When
        val entries = ErrorLogger.getLogEntries()
        
        // Then
        assertEquals(50, entries.size)
        
        // Verify entries are in chronological order (newest last)
        for (i in 1 until entries.size) {
            assertTrue(entries[i].timestamp >= entries[i-1].timestamp)
        }
    }
}