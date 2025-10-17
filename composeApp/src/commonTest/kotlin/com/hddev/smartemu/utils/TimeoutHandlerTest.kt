package com.hddev.smartemu.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TimeoutHandlerTest {
    
    private lateinit var timeoutHandler: TimeoutHandler
    
    @BeforeTest
    fun setUp() {
        timeoutHandler = TimeoutHandler()
        ErrorLogger.clearLogs()
    }
    
    @Test
    fun testWithTimeout_successfulOperation() = runTest {
        // Given
        val config = TimeoutHandler.TimeoutConfig(
            timeoutMs = 1000L,
            operation = "TEST_OPERATION"
        )
        
        // When
        val result = timeoutHandler.withTimeout(config) {
            delay(100) // Short delay, well within timeout
            "success"
        }
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Success)
        assertEquals("success", (result as TimeoutHandler.TimeoutResult.Success).value)
        assertTrue(result.duration >= 100)
    }
    
    @Test
    fun testWithTimeout_operationTimeout() = runTest {
        // Given
        val config = TimeoutHandler.TimeoutConfig(
            timeoutMs = 100L,
            operation = "TIMEOUT_TEST"
        )
        
        // When
        val result = timeoutHandler.withTimeout(config) {
            delay(200) // Longer than timeout
            "should not complete"
        }
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Timeout)
        val timeoutResult = result as TimeoutHandler.TimeoutResult.Timeout
        assertEquals("TIMEOUT_TEST", timeoutResult.operation)
        assertEquals(100L, timeoutResult.timeoutMs)
        
        // Verify timeout was logged
        val timeoutLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.TIMEOUT)
        assertTrue(timeoutLogs.isNotEmpty())
        assertTrue(timeoutLogs.any { it.message.contains("TIMEOUT_TEST") })
    }
    
    @Test
    fun testWithTimeout_operationException() = runTest {
        // Given
        val config = TimeoutHandler.TimeoutConfig(
            timeoutMs = 1000L,
            operation = "ERROR_TEST"
        )
        val testException = RuntimeException("Test exception")
        
        // When
        val result = timeoutHandler.withTimeout(config) {
            throw testException
        }
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Error)
        val errorResult = result as TimeoutHandler.TimeoutResult.Error
        assertEquals("ERROR_TEST", errorResult.operation)
        assertEquals(testException, errorResult.error)
    }
    
    @Test
    fun testWithTimeout_onTimeoutCallback() = runTest {
        // Given
        var timeoutCallbackCalled = false
        val config = TimeoutHandler.TimeoutConfig(
            timeoutMs = 100L,
            operation = "CALLBACK_TEST",
            onTimeout = { timeoutCallbackCalled = true }
        )
        
        // When
        val result = timeoutHandler.withTimeout(config) {
            delay(200) // Will timeout
            "should not complete"
        }
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Timeout)
        assertTrue(timeoutCallbackCalled)
    }
    
    @Test
    fun testWithBacTimeout_usesCorrectDefaults() = runTest {
        // Given
        val correlationId = "bac-test-123"
        
        // When
        val result = timeoutHandler.withBacTimeout(
            operation = { "bac success" },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Success)
        assertEquals("bac success", (result as TimeoutHandler.TimeoutResult.Success).value)
    }
    
    @Test
    fun testWithBacTimeout_logsAuthenticationError() = runTest {
        // Given
        val correlationId = "bac-timeout-test"
        
        // When
        val result = timeoutHandler.withBacTimeout(
            operation = {
                delay(TimeoutHandler.DEFAULT_AUTHENTICATION_TIMEOUT + 100)
                "should timeout"
            },
            correlationId = correlationId,
            timeoutMs = 100L
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Timeout)
        
        // Verify BAC-specific error logging
        val authLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.AUTHENTICATION)
        assertTrue(authLogs.any { 
            it.message.contains("BAC authentication timed out") && 
            it.correlationId == correlationId 
        })
    }
    
    @Test
    fun testWithPaceTimeout_includesStepInformation() = runTest {
        // Given
        val step = "KEY_AGREEMENT"
        val correlationId = "pace-test-456"
        
        // When
        val result = timeoutHandler.withPaceTimeout(
            step = step,
            operation = { "pace success" },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Success)
        assertEquals("pace success", (result as TimeoutHandler.TimeoutResult.Success).value)
    }
    
    @Test
    fun testWithPaceTimeout_logsStepSpecificError() = runTest {
        // Given
        val step = "MUTUAL_AUTHENTICATION"
        val correlationId = "pace-timeout-test"
        
        // When
        val result = timeoutHandler.withPaceTimeout(
            step = step,
            operation = {
                delay(200)
                "should timeout"
            },
            correlationId = correlationId,
            timeoutMs = 100L
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Timeout)
        
        // Verify PACE-specific error logging
        val authLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.AUTHENTICATION)
        assertTrue(authLogs.any { 
            it.message.contains("PACE $step timed out") && 
            it.correlationId == correlationId 
        })
    }
    
    @Test
    fun testWithApduTimeout_usesShortTimeout() = runTest {
        // Given
        val apduType = "SELECT"
        val correlationId = "apdu-test-789"
        
        // When
        val result = timeoutHandler.withApduTimeout(
            apduType = apduType,
            operation = { "apdu processed" },
            correlationId = correlationId
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Success)
        assertEquals("apdu processed", (result as TimeoutHandler.TimeoutResult.Success).value)
    }
    
    @Test
    fun testWithConnectionTimeout_logsNfcError() = runTest {
        // Given
        val correlationId = "connection-timeout-test"
        
        // When
        val result = timeoutHandler.withConnectionTimeout(
            operation = {
                delay(200)
                "should timeout"
            },
            correlationId = correlationId,
            timeoutMs = 100L
        )
        
        // Then
        assertTrue(result is TimeoutHandler.TimeoutResult.Timeout)
        
        // Verify NFC-specific error logging
        val nfcLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.NFC_HARDWARE)
        assertTrue(nfcLogs.any { 
            it.message.contains("NFC connection timed out") && 
            it.correlationId == correlationId 
        })
    }
    
    @Test
    fun testCancelOperation_cancelsMatchingOperations() = runTest {
        // Given
        val correlationId = "cancel-test-101"
        
        // Start a long-running operation
        val operationJob = kotlinx.coroutines.launch {
            timeoutHandler.withTimeout(
                TimeoutHandler.TimeoutConfig(
                    timeoutMs = 5000L,
                    operation = "LONG_OPERATION",
                    correlationId = correlationId
                )
            ) {
                delay(10000) // Very long delay
                "should be cancelled"
            }
        }
        
        // Give the operation time to start
        delay(50)
        
        // When
        timeoutHandler.cancelOperation(correlationId)
        
        // Then
        // Wait for the operation to be cancelled
        delay(100)
        
        val cancelLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.TIMEOUT)
        assertTrue(cancelLogs.any { 
            it.message.contains("Operation cancelled") && 
            it.correlationId == correlationId 
        })
        
        operationJob.cancel()
    }
    
    @Test
    fun testCancelAllOperations_cancelsAllActive() = runTest {
        // Given
        val correlationId1 = "cancel-all-test-1"
        val correlationId2 = "cancel-all-test-2"
        
        // Start multiple operations
        val job1 = kotlinx.coroutines.launch {
            timeoutHandler.withTimeout(
                TimeoutHandler.TimeoutConfig(
                    timeoutMs = 5000L,
                    operation = "OPERATION_1",
                    correlationId = correlationId1
                )
            ) {
                delay(10000)
                "should be cancelled"
            }
        }
        
        val job2 = kotlinx.coroutines.launch {
            timeoutHandler.withTimeout(
                TimeoutHandler.TimeoutConfig(
                    timeoutMs = 5000L,
                    operation = "OPERATION_2",
                    correlationId = correlationId2
                )
            ) {
                delay(10000)
                "should be cancelled"
            }
        }
        
        // Give operations time to start
        delay(50)
        
        // When
        timeoutHandler.cancelAllOperations()
        
        // Then
        delay(100)
        
        val cancelLogs = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.TIMEOUT)
        assertTrue(cancelLogs.any { it.message.contains("Operation cancelled (bulk)") })
        
        job1.cancel()
        job2.cancel()
    }
    
    @Test
    fun testGetActiveOperations_tracksOperationStatus() = runTest {
        // Given
        val correlationId = "status-test-202"
        
        // Start an operation
        val operationJob = kotlinx.coroutines.launch {
            timeoutHandler.withTimeout(
                TimeoutHandler.TimeoutConfig(
                    timeoutMs = 1000L,
                    operation = "STATUS_TEST",
                    correlationId = correlationId
                )
            ) {
                delay(500)
                "completed"
            }
        }
        
        // Give operation time to start
        delay(50)
        
        // When
        val activeOperations = timeoutHandler.getActiveOperations()
        
        // Then
        assertTrue(activeOperations.isNotEmpty())
        assertTrue(activeOperations.values.any { it == "ACTIVE" })
        
        // Wait for completion
        operationJob.join()
        
        // Check final status
        val finalOperations = timeoutHandler.getActiveOperations()
        // Operations should be cleaned up after completion
    }
    
    @Test
    fun testGetTimeoutStatistics_providesMetrics() = runTest {
        // Given
        // Create some timeout events
        timeoutHandler.withTimeout(
            TimeoutHandler.TimeoutConfig(timeoutMs = 50L, operation = "STATS_TEST_1")
        ) {
            delay(100) // Will timeout
            "timeout"
        }
        
        timeoutHandler.withTimeout(
            TimeoutHandler.TimeoutConfig(timeoutMs = 50L, operation = "STATS_TEST_2")
        ) {
            delay(100) // Will timeout
            "timeout"
        }
        
        // When
        val statistics = timeoutHandler.getTimeoutStatistics()
        
        // Then
        assertTrue(statistics.containsKey("totalTimeouts"))
        assertTrue(statistics.containsKey("activeOperations"))
        assertTrue(statistics.containsKey("timeoutsByOperation"))
        assertTrue(statistics.containsKey("recentTimeouts"))
        
        val totalTimeouts = statistics["totalTimeouts"] as Int
        assertTrue(totalTimeouts >= 2)
    }
}