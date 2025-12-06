@file:OptIn(ExperimentalTime::class)

package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Error recovery mechanism for handling protocol failures and system errors.
 * Provides automatic retry logic with exponential backoff and circuit breaker patterns.
 */
class ErrorRecovery {
    
    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_DELAY_MS = 1000L
        const val DEFAULT_MAX_DELAY_MS = 10000L
        const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        
        // Circuit breaker thresholds
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_RECOVERY_TIMEOUT_MS = 30000L
    }
    
    /**
     * Recovery strategy configuration.
     */
    data class RecoveryConfig(
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
        val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
        val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
        val retryableErrors: Set<Class<out SimulatorError>> = setOf(
            SimulatorError.NfcError.ConnectionFailed::class.java,
            SimulatorError.NfcError.ServiceError::class.java,
            SimulatorError.ProtocolError.CryptographicError::class.java,
            SimulatorError.SystemError.UnexpectedError::class.java
        ),
        val correlationId: String? = null
    )
    
    /**
     * Result of a recovery operation.
     */
    sealed class RecoveryResult<T> {
        data class Success<T>(val value: T, val attemptNumber: Int) : RecoveryResult<T>()
        data class Failed<T>(val lastError: SimulatorError, val totalAttempts: Int) : RecoveryResult<T>()
        data class NonRetryable<T>(val error: SimulatorError) : RecoveryResult<T>()
    }
    
    /**
     * Circuit breaker state for preventing cascading failures.
     */
    enum class CircuitState {
        CLOSED,    // Normal operation
        OPEN,      // Failing fast
        HALF_OPEN  // Testing recovery
    }
    
    /**
     * Circuit breaker for specific operations.
     */
    private data class CircuitBreaker(
        var state: CircuitState = CircuitState.CLOSED,
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        var successCount: Int = 0
    )
    
    private val circuitBreakers = mutableMapOf<String, CircuitBreaker>()
    
    /**
     * Executes an operation with automatic retry and error recovery.
     */
    suspend fun <T> withRecovery(
        operation: String,
        config: RecoveryConfig = RecoveryConfig(),
        block: suspend (attemptNumber: Int) -> T
    ): RecoveryResult<T> {
        
        // Check circuit breaker
        val circuitBreaker = getCircuitBreaker(operation)
        if (circuitBreaker.state == CircuitState.OPEN) {
            if (shouldAttemptRecovery(circuitBreaker)) {
                circuitBreaker.state = CircuitState.HALF_OPEN
                ErrorLogger.logRecoveryAttempt(
                    operation = operation,
                    attemptNumber = 1,
                    maxAttempts = 1,
                    context = mapOf("circuitState" to "HALF_OPEN"),
                    correlationId = config.correlationId
                )
            } else {
                val error = SimulatorError.SystemError.ConfigurationError("Circuit breaker open for $operation")
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.WARNING,
                    category = ErrorLogger.ErrorCategory.RECOVERY,
                    message = "Circuit breaker open, failing fast: $operation",
                    error = error,
                    correlationId = config.correlationId
                )
                return RecoveryResult.NonRetryable(error)
            }
        }
        
        var lastError: SimulatorError? = null
        var currentDelay = config.baseDelayMs
        
        repeat(config.maxRetries + 1) { attempt ->
            try {
                ErrorLogger.logRecoveryAttempt(
                    operation = operation,
                    attemptNumber = attempt + 1,
                    maxAttempts = config.maxRetries + 1,
                    correlationId = config.correlationId
                )
                
                val result = block(attempt + 1)
                
                // Success - update circuit breaker
                onOperationSuccess(circuitBreaker, operation, attempt + 1, config.correlationId)
                
                ErrorLogger.logRecoverySuccess(
                    operation = operation,
                    attemptNumber = attempt + 1,
                    correlationId = config.correlationId
                )
                
                return RecoveryResult.Success(result, attempt + 1)
                
            } catch (e: Exception) {
                val simulatorError = when (e) {
                    is SimulatorError -> e
                    else -> SimulatorError.SystemError.UnexpectedError(e)
                }
                
                lastError = simulatorError
                
                // Check if error is retryable
                if (!isRetryableError(simulatorError, config)) {
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.ERROR,
                        category = ErrorLogger.ErrorCategory.RECOVERY,
                        message = "Non-retryable error in $operation: ${simulatorError.message}",
                        error = simulatorError,
                        correlationId = config.correlationId
                    )
                    
                    onOperationFailure(circuitBreaker, operation, simulatorError, config.correlationId)
                    return RecoveryResult.NonRetryable(simulatorError)
                }
                
                // Log retry attempt
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.WARNING,
                    category = ErrorLogger.ErrorCategory.RECOVERY,
                    message = "Retryable error in $operation (attempt ${attempt + 1}/${config.maxRetries + 1}): ${simulatorError.message}",
                    error = simulatorError,
                    context = mapOf(
                        "attemptNumber" to (attempt + 1),
                        "maxAttempts" to (config.maxRetries + 1),
                        "nextDelayMs" to currentDelay
                    ),
                    correlationId = config.correlationId
                )
                
                // If this is not the last attempt, wait before retrying
                if (attempt < config.maxRetries) {
                    delay(currentDelay)
                    currentDelay = minOf(
                        (currentDelay * config.backoffMultiplier).toLong(),
                        config.maxDelayMs
                    )
                }
            }
        }
        
        // All retries exhausted
        val finalError = lastError ?: SimulatorError.SystemError.UnexpectedError(
            RuntimeException("Unknown error in $operation")
        )
        
        onOperationFailure(circuitBreaker, operation, finalError, config.correlationId)
        
        ErrorLogger.logError(
            level = ErrorLogger.LogLevel.ERROR,
            category = ErrorLogger.ErrorCategory.RECOVERY,
            message = "All retry attempts exhausted for $operation",
            error = finalError,
            context = mapOf("totalAttempts" to (config.maxRetries + 1)),
            correlationId = config.correlationId
        )
        
        return RecoveryResult.Failed(finalError, config.maxRetries + 1)
    }
    
    /**
     * Executes BAC authentication with recovery.
     */
    suspend fun <T> withBacRecovery(
        block: suspend (attemptNumber: Int) -> T,
        correlationId: String? = null
    ): RecoveryResult<T> {
        return withRecovery(
            operation = "BAC_AUTHENTICATION",
            config = RecoveryConfig(
                maxRetries = 2, // BAC is sensitive, fewer retries
                baseDelayMs = 500L,
                retryableErrors = setOf(
                    SimulatorError.ProtocolError.CryptographicError::class.java,
                    SimulatorError.NfcError.ServiceError::class.java
                ),
                correlationId = correlationId
            ),
            block = block
        )
    }
    
    /**
     * Executes PACE authentication with recovery.
     */
    suspend fun <T> withPaceRecovery(
        step: String,
        block: suspend (attemptNumber: Int) -> T,
        correlationId: String? = null
    ): RecoveryResult<T> {
        return withRecovery(
            operation = "PACE_$step",
            config = RecoveryConfig(
                maxRetries = 3,
                baseDelayMs = 1000L,
                retryableErrors = setOf(
                    SimulatorError.ProtocolError.CryptographicError::class.java,
                    SimulatorError.NfcError.ServiceError::class.java,
                    SimulatorError.SystemError.UnexpectedError::class.java
                ),
                correlationId = correlationId
            ),
            block = block
        )
    }
    
    /**
     * Executes NFC connection with recovery.
     */
    suspend fun <T> withNfcRecovery(
        block: suspend (attemptNumber: Int) -> T,
        correlationId: String? = null
    ): RecoveryResult<T> {
        return withRecovery(
            operation = "NFC_CONNECTION",
            config = RecoveryConfig(
                maxRetries = 5, // NFC connections can be flaky
                baseDelayMs = 2000L,
                retryableErrors = setOf(
                    SimulatorError.NfcError.ConnectionFailed::class.java,
                    SimulatorError.NfcError.ServiceError::class.java
                ),
                correlationId = correlationId
            ),
            block = block
        )
    }
    
    /**
     * Resets circuit breaker for a specific operation.
     */
    fun resetCircuitBreaker(operation: String) {
        circuitBreakers[operation] = CircuitBreaker()
        ErrorLogger.logError(
            level = ErrorLogger.LogLevel.INFO,
            category = ErrorLogger.ErrorCategory.RECOVERY,
            message = "Circuit breaker reset for operation: $operation"
        )
    }
    
    /**
     * Gets circuit breaker status for monitoring.
     */
    fun getCircuitBreakerStatus(): Map<String, Map<String, Any>> {
        return circuitBreakers.mapValues { (_, breaker) ->
            mapOf(
                "state" to breaker.state.name,
                "failureCount" to breaker.failureCount,
                "successCount" to breaker.successCount,
                "lastFailureTime" to breaker.lastFailureTime
            )
        }
    }
    
    /**
     * Gets recovery statistics for monitoring.
     */
    fun getRecoveryStatistics(): Map<String, Any> {
        val recoveryEntries = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.RECOVERY)
        
        return mapOf(
            "totalRecoveryAttempts" to recoveryEntries.size,
            "circuitBreakers" to circuitBreakers.size,
            "recoveryByOperation" to recoveryEntries.groupBy { 
                it.context["operation"] as? String ?: "unknown" 
            }.mapValues { it.value.size },
            "successfulRecoveries" to recoveryEntries.count { 
                it.message.contains("Recovery successful") 
            }
        )
    }
    
    private fun getCircuitBreaker(operation: String): CircuitBreaker {
        return circuitBreakers.getOrPut(operation) { CircuitBreaker() }
    }
    
    private fun shouldAttemptRecovery(circuitBreaker: CircuitBreaker): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return (now - circuitBreaker.lastFailureTime) > DEFAULT_RECOVERY_TIMEOUT_MS
    }
    
    private fun isRetryableError(error: SimulatorError, config: RecoveryConfig): Boolean {
        return config.retryableErrors.any { it.isInstance(error) }
    }
    
    private fun onOperationSuccess(
        circuitBreaker: CircuitBreaker,
        operation: String,
        attemptNumber: Int,
        correlationId: String?
    ) {
        when (circuitBreaker.state) {
            CircuitState.HALF_OPEN -> {
                circuitBreaker.state = CircuitState.CLOSED
                circuitBreaker.failureCount = 0
                circuitBreaker.successCount++
                
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.INFO,
                    category = ErrorLogger.ErrorCategory.RECOVERY,
                    message = "Circuit breaker closed after successful recovery: $operation",
                    context = mapOf("attemptNumber" to attemptNumber),
                    correlationId = correlationId
                )
            }
            CircuitState.CLOSED -> {
                circuitBreaker.successCount++
            }
            CircuitState.OPEN -> {
                // Should not happen, but handle gracefully
                circuitBreaker.state = CircuitState.CLOSED
                circuitBreaker.failureCount = 0
            }
        }
    }
    
    private fun onOperationFailure(
        circuitBreaker: CircuitBreaker,
        operation: String,
        error: SimulatorError,
        correlationId: String?
    ) {
        circuitBreaker.failureCount++
        circuitBreaker.lastFailureTime = Clock.System.now().toEpochMilliseconds()
        
        when (circuitBreaker.state) {
            CircuitState.CLOSED -> {
                if (circuitBreaker.failureCount >= DEFAULT_FAILURE_THRESHOLD) {
                    circuitBreaker.state = CircuitState.OPEN
                    
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.WARNING,
                        category = ErrorLogger.ErrorCategory.RECOVERY,
                        message = "Circuit breaker opened due to repeated failures: $operation",
                        error = error,
                        context = mapOf("failureCount" to circuitBreaker.failureCount),
                        correlationId = correlationId
                    )
                }
            }
            CircuitState.HALF_OPEN -> {
                circuitBreaker.state = CircuitState.OPEN
                
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.WARNING,
                    category = ErrorLogger.ErrorCategory.RECOVERY,
                    message = "Circuit breaker reopened after failed recovery attempt: $operation",
                    error = error,
                    correlationId = correlationId
                )
            }
            CircuitState.OPEN -> {
                // Already open, just update failure time
            }
        }
    }
}