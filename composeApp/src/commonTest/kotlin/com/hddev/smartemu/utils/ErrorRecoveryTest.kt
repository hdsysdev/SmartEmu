package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ErrorRecoveryTest {
    
    private lateinit var errorRecovery: ErrorRecovery
    
    @BeforeTest
    fun setUp() {
        errorRecovery = ErrorRecovery()
        ErrorLogger.clearLogs()
    }
    
    @Test
    fun testWithRecovery_successfulOperation() = runTest {
        // Given
        val operation = "TEST_OPERATION"
        
        // When
        val result = errorRecovery.withRecovery(operation) { attemptNumber ->
            "success on attempt $attemptNumber"
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("success on attempt 1", (result as ErrorRecovery.RecoveryResult.Success).value)
        assertEquals(1, result.attemptNumber)
    }
    
    @Test
    fun testWithRecovery_successAfterRetries() = runTest {
        // Given
        val operation = "RETRY_TEST"
        var attemptCount = 0
        
        // When
        val result = errorRecovery.withRecovery(operation) { attemptNumber ->
            attemptCount++
            if (attemptNumber < 3) {
                throw SimulatorError.NfcError.ConnectionFailed("Temporary failure")
            }
            "success on attempt $attemptNumber"
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("success on attempt 3", (result as ErrorRecovery.RecoveryResult.Success).value)
        assertEquals(3, result.attemptNumber)
        assertEquals(3, attemptCount)
        
        // Verify recovery attempts were logged
        val recoveryLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        assertTrue(recoveryLogs.any { it.message.contains("Recovery attempt") })
        assertTrue(recoveryLogs.any { it.message.contains("Recovery successful") })
    }
    
    @Test
    fun testWithRecovery_allRetriesExhausted() = runTest {
        // Given
        val operation = "FAILURE_TEST"
        val config = ErrorRecovery.RecoveryConfig(maxRetries = 2)
        
        // When
        val result = errorRecovery.withRecovery(operation, config) { attemptNumber ->
            throw SimulatorError.NfcError.ServiceError("Persistent failure", null)
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Failed)
        val failedResult = result as ErrorRecovery.RecoveryResult.Failed
        assertTrue(failedResult.lastError is SimulatorError.NfcError.ServiceError)
        assertEquals(3, failedResult.totalAttempts) // maxRetries + 1
        
        // Verify failure was logged
        val recoveryLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        assertTrue(recoveryLogs.any { it.message.contains("All retry attempts exhausted") })
    }
    
    @Test
    fun testWithRecovery_nonRetryableError() = runTest {
        // Given
        val operation = "NON_RETRYABLE_TEST"
        val config = ErrorRecovery.RecoveryConfig(
            retryableErrors = setOf(SimulatorError.NfcError.ConnectionFailed::class.java)
        )
        
        // When
        val result = errorRecovery.withRecovery(operation, config) { attemptNumber ->
            throw SimulatorError.ValidationError.InvalidPassportNumber // Not retryable
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.NonRetryable)
        val nonRetryableResult = result as ErrorRecovery.RecoveryResult.NonRetryable
        assertTrue(nonRetryableResult.error is SimulatorError.ValidationError.InvalidPassportNumber)
        
        // Verify non-retryable error was logged
        val recoveryLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        assertTrue(recoveryLogs.any { it.message.contains("Non-retryable error") })
    }
    
    @Test
    fun testWithRecovery_exponentialBackoff() = runTest {
        // Given
        val operation = "BACKOFF_TEST"
        val config = ErrorRecovery.RecoveryConfig(
            maxRetries = 2,
            baseDelayMs = 100L,
            backoffMultiplier = 2.0
        )
        val startTime = kotlin.time.Clock.System.now()
        
        // When
        val result = errorRecovery.withRecovery(operation, config) { attemptNumber ->
            if (attemptNumber <= 2) {
                throw SimulatorError.NfcError.ConnectionFailed("Retry test")
            }
            "success"
        }
        
        // Then
        val endTime = kotlin.time.Clock.System.now()
        val duration = (endTime - startTime).inWholeMilliseconds
        
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        // Should have waited: 100ms + 200ms = 300ms minimum
        assertTrue(duration >= 300, "Duration was ${duration}ms, expected at least 300ms")
    }
    
    @Test
    fun testWithBacRecovery_usesCorrectConfiguration() = runTest {
        // Given
        val correlationId = "bac-recovery-test"
        
        // When
        val result = errorRecovery.withBacRecovery(
            block = { attemptNumber -> "bac success on attempt $attemptNumber" },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("bac success on attempt 1", (result as ErrorRecovery.RecoveryResult.Success).value)
        
        // Verify BAC-specific logging
        val recoveryLogs = ErrorLogger.getLogEntries(correlationId = correlationId)
        assertTrue(recoveryLogs.any { it.message.contains("BAC_AUTHENTICATION") })
    }
    
    @Test
    fun testWithBacRecovery_limitedRetries() = runTest {
        // Given
        val correlationId = "bac-limited-retries"
        
        // When
        val result = errorRecovery.withBacRecovery(
            block = { attemptNumber ->
                throw SimulatorError.ProtocolError.CryptographicError("BAC crypto error", RuntimeException())
            },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Failed)
        val failedResult = result as ErrorRecovery.RecoveryResult.Failed
        assertEquals(3, failedResult.totalAttempts) // BAC uses maxRetries = 2, so 3 total attempts
    }
    
    @Test
    fun testWithPaceRecovery_includesStepInformation() = runTest {
        // Given
        val step = "KEY_AGREEMENT"
        val correlationId = "pace-recovery-test"
        
        // When
        val result = errorRecovery.withPaceRecovery(
            step = step,
            block = { attemptNumber -> "pace $step success on attempt $attemptNumber" },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("pace $step success on attempt 1", (result as ErrorRecovery.RecoveryResult.Success).value)
        
        // Verify PACE-specific logging with step
        val recoveryLogs = ErrorLogger.getLogEntries(correlationId = correlationId)
        assertTrue(recoveryLogs.any { it.message.contains("PACE_$step") })
    }
    
    @Test
    fun testWithNfcRecovery_allowsMoreRetries() = runTest {
        // Given
        val correlationId = "nfc-recovery-test"
        var attemptCount = 0
        
        // When
        val result = errorRecovery.withNfcRecovery(
            block = { attemptNumber ->
                attemptCount++
                if (attemptNumber <= 4) {
                    throw SimulatorError.NfcError.ConnectionFailed("NFC flaky connection")
                }
                "nfc success on attempt $attemptNumber"
            },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("nfc success on attempt 5", (result as ErrorRecovery.RecoveryResult.Success).value)
        assertEquals(5, attemptCount)
    }
    
    @Test
    fun testCircuitBreaker_opensAfterFailures() = runTest {
        // Given
        val operation = "CIRCUIT_BREAKER_TEST"
        
        // Cause multiple failures to open circuit breaker
        repeat(ErrorRecovery.DEFAULT_FAILURE_THRESHOLD) {
            errorRecovery.withRecovery(operation) { attemptNumber ->
                throw SimulatorError.SystemError.UnexpectedError(RuntimeException("Persistent failure"))
            }
        }
        
        // When - attempt another operation
        val result = errorRecovery.withRecovery(operation) { attemptNumber ->
            "should be blocked by circuit breaker"
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.NonRetryable)
        val nonRetryableResult = result as ErrorRecovery.RecoveryResult.NonRetryable
        assertTrue(nonRetryableResult.error.message.contains("Circuit breaker open"))
        
        // Verify circuit breaker logging
        val recoveryLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        assertTrue(recoveryLogs.any { it.message.contains("Circuit breaker opened") })
    }
    
    @Test
    fun testCircuitBreaker_halfOpenRecovery() = runTest {
        // Given
        val operation = "HALF_OPEN_TEST"
        
        // Open the circuit breaker
        repeat(ErrorRecovery.DEFAULT_FAILURE_THRESHOLD) {
            errorRecovery.withRecovery(operation) { attemptNumber ->
                throw SimulatorError.SystemError.UnexpectedError(RuntimeException("Failure"))
            }
        }
        
        // Reset circuit breaker to simulate recovery timeout
        errorRecovery.resetCircuitBreaker(operation)
        
        // When - successful operation should close circuit breaker
        val result = errorRecovery.withRecovery(operation) { attemptNumber ->
            "recovery success"
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("recovery success", (result as ErrorRecovery.RecoveryResult.Success).value)
    }
    
    @Test
    fun testResetCircuitBreaker_clearsFailureState() = runTest {
        // Given
        val operation = "RESET_TEST"
        
        // Cause some failures
        repeat(3) {
            errorRecovery.withRecovery(operation) { attemptNumber ->
                throw SimulatorError.NfcError.ServiceError("Test failure", null)
            }
        }
        
        // When
        errorRecovery.resetCircuitBreaker(operation)
        
        // Then - should be able to operate normally
        val result = errorRecovery.withRecovery(operation) { attemptNumber ->
            "success after reset"
        }
        
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        assertEquals("success after reset", (result as ErrorRecovery.RecoveryResult.Success).value)
        
        // Verify reset was logged
        val recoveryLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        assertTrue(recoveryLogs.any { it.message.contains("Circuit breaker reset") })
    }
    
    @Test
    fun testGetCircuitBreakerStatus_providesStateInformation() = runTest {
        // Given
        val operation = "STATUS_TEST"
        
        // Cause a failure
        errorRecovery.withRecovery(operation) { attemptNumber ->
            throw SimulatorError.NfcError.ConnectionFailed("Test failure")
        }
        
        // When
        val status = errorRecovery.getCircuitBreakerStatus()
        
        // Then
        assertTrue(status.containsKey(operation))
        val operationStatus = status[operation] as Map<*, *>
        assertEquals("CLOSED", operationStatus["state"])
        assertEquals(1, operationStatus["failureCount"])
        assertEquals(0, operationStatus["successCount"])
        assertTrue(operationStatus.containsKey("lastFailureTime"))
    }
    
    @Test
    fun testGetRecoveryStatistics_providesMetrics() = runTest {
        // Given
        // Create some recovery attempts
        errorRecovery.withRecovery("STATS_TEST_1") { attemptNumber ->
            if (attemptNumber == 1) throw SimulatorError.NfcError.ConnectionFailed("Retry")
            "success"
        }
        
        errorRecovery.withRecovery("STATS_TEST_2") { attemptNumber ->
            "immediate success"
        }
        
        // When
        val statistics = errorRecovery.getRecoveryStatistics()
        
        // Then
        assertTrue(statistics.containsKey("totalRecoveryAttempts"))
        assertTrue(statistics.containsKey("circuitBreakers"))
        assertTrue(statistics.containsKey("recoveryByOperation"))
        assertTrue(statistics.containsKey("successfulRecoveries"))
        
        val totalAttempts = statistics["totalRecoveryAttempts"] as Int
        assertTrue(totalAttempts >= 3) // At least 3 recovery log entries
        
        val successfulRecoveries = statistics["successfulRecoveries"] as Int
        assertTrue(successfulRecoveries >= 2) // At least 2 successful recoveries
    }
    
    @Test
    fun testCorrelationIdTracking_maintainsContext() = runTest {
        // Given
        val correlationId = "correlation-tracking-test"
        val operation = "CORRELATION_TEST"
        
        // When
        val result = errorRecovery.withRecovery(
            operation = operation,
            config = ErrorRecovery.RecoveryConfig(correlationId = correlationId)
        ) { attemptNumber ->
            if (attemptNumber == 1) {
                throw SimulatorError.NfcError.ServiceError("Retry with correlation", null)
            }
            "success with correlation"
        }
        
        // Then
        assertTrue(result is ErrorRecovery.RecoveryResult.Success)
        
        // Verify all logs have the correlation ID
        val correlatedLogs = ErrorLogger.getLogEntries(correlationId = correlationId)
        assertTrue(correlatedLogs.isNotEmpty())
        assertTrue(correlatedLogs.all { it.correlationId == correlationId })
    }
}