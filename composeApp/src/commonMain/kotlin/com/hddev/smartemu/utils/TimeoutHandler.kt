package com.hddev.smartemu.utils

import com.hddev.smartemu.domain.SimulatorError
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Utility for handling timeouts in authentication operations and protocol steps.
 * Provides configurable timeout handling with automatic cleanup and error reporting.
 */
class TimeoutHandler {
    
    companion object {
        // Default timeout values in milliseconds
        const val DEFAULT_AUTHENTICATION_TIMEOUT = 30_000L // 30 seconds
        const val DEFAULT_PROTOCOL_STEP_TIMEOUT = 10_000L  // 10 seconds
        const val DEFAULT_CONNECTION_TIMEOUT = 5_000L      // 5 seconds
        const val DEFAULT_APDU_TIMEOUT = 3_000L           // 3 seconds
    }
    
    /**
     * Configuration for timeout operations.
     */
    data class TimeoutConfig(
        val timeoutMs: Long,
        val operation: String,
        val correlationId: String? = null,
        val onTimeout: suspend () -> Unit = {},
        val onCancel: suspend () -> Unit = {}
    )
    
    /**
     * Result of a timeout operation.
     */
    sealed class TimeoutResult<T> {
        data class Success<T>(val value: T, val duration: Long) : TimeoutResult<T>()
        data class Timeout<T>(val operation: String, val timeoutMs: Long) : TimeoutResult<T>()
        data class Cancelled<T>(val operation: String) : TimeoutResult<T>()
        data class Error<T>(val operation: String, val error: Throwable) : TimeoutResult<T>()
    }
    
    private val activeOperations = mutableMapOf<String, Job>()
    
    /**
     * Executes an operation with a timeout.
     */
    suspend fun <T> withTimeout(
        config: TimeoutConfig,
        operation: suspend () -> T
    ): TimeoutResult<T> {
        val startTime = Clock.System.now()
        val operationId = "${config.operation}_${startTime.toEpochMilliseconds()}"
        
        return try {
            val result = withTimeoutOrNull(config.timeoutMs) {
                // Track the operation
                coroutineContext[Job]?.let { activeOperations[operationId] = it }
                
                try {
                    operation()
                } finally {
                    activeOperations.remove(operationId)
                }
            }
            
            val duration = (Clock.System.now() - startTime).inWholeMilliseconds
            
            if (result != null) {
                TimeoutResult.Success(result, duration)
            } else {
                // Operation timed out
                ErrorLogger.logTimeout(
                    operation = config.operation,
                    timeoutMs = config.timeoutMs,
                    context = mapOf("operationId" to operationId),
                    correlationId = config.correlationId
                )
                
                config.onTimeout()
                TimeoutResult.Timeout(config.operation, config.timeoutMs)
            }
            
        } catch (e: CancellationException) {
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.INFO,
                category = ErrorLogger.ErrorCategory.TIMEOUT,
                message = "Operation cancelled: ${config.operation}",
                context = mapOf("operationId" to operationId),
                correlationId = config.correlationId
            )
            
            config.onCancel()
            TimeoutResult.Cancelled(config.operation)
            
        } catch (e: Exception) {
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.ERROR,
                category = ErrorLogger.ErrorCategory.SYSTEM,
                message = "Operation failed: ${config.operation}",
                context = mapOf("operationId" to operationId),
                throwable = e,
                correlationId = config.correlationId
            )
            
            TimeoutResult.Error(config.operation, e)
            
        } finally {
            activeOperations.remove(operationId)
        }
    }
    
    /**
     * Executes a BAC authentication operation with timeout.
     */
    suspend fun <T> withBacTimeout(
        operation: suspend () -> T,
        correlationId: String? = null,
        timeoutMs: Long = DEFAULT_AUTHENTICATION_TIMEOUT
    ): TimeoutResult<T> {
        return withTimeout(
            config = TimeoutConfig(
                timeoutMs = timeoutMs,
                operation = "BAC_AUTHENTICATION",
                correlationId = correlationId,
                onTimeout = {
                    ErrorLogger.logAuthenticationError(
                        protocol = "BAC",
                        message = "BAC authentication timed out after ${timeoutMs}ms",
                        error = SimulatorError.ProtocolError.BacAuthenticationFailed,
                        correlationId = correlationId
                    )
                }
            ),
            operation = operation
        )
    }
    
    /**
     * Executes a PACE authentication operation with timeout.
     */
    suspend fun <T> withPaceTimeout(
        step: String,
        operation: suspend () -> T,
        correlationId: String? = null,
        timeoutMs: Long = DEFAULT_PROTOCOL_STEP_TIMEOUT
    ): TimeoutResult<T> {
        return withTimeout(
            config = TimeoutConfig(
                timeoutMs = timeoutMs,
                operation = "PACE_$step",
                correlationId = correlationId,
                onTimeout = {
                    ErrorLogger.logAuthenticationError(
                        protocol = "PACE",
                        message = "PACE $step timed out after ${timeoutMs}ms",
                        error = SimulatorError.ProtocolError.PaceAuthenticationFailed,
                        correlationId = correlationId
                    )
                }
            ),
            operation = operation
        )
    }
    
    /**
     * Executes an APDU processing operation with timeout.
     */
    suspend fun <T> withApduTimeout(
        apduType: String,
        operation: suspend () -> T,
        correlationId: String? = null,
        timeoutMs: Long = DEFAULT_APDU_TIMEOUT
    ): TimeoutResult<T> {
        return withTimeout(
            config = TimeoutConfig(
                timeoutMs = timeoutMs,
                operation = "APDU_$apduType",
                correlationId = correlationId,
                onTimeout = {
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.WARNING,
                        category = ErrorLogger.ErrorCategory.TIMEOUT,
                        message = "APDU processing timed out: $apduType",
                        context = mapOf("apduType" to apduType),
                        correlationId = correlationId
                    )
                }
            ),
            operation = operation
        )
    }
    
    /**
     * Executes a connection operation with timeout.
     */
    suspend fun <T> withConnectionTimeout(
        operation: suspend () -> T,
        correlationId: String? = null,
        timeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT
    ): TimeoutResult<T> {
        return withTimeout(
            config = TimeoutConfig(
                timeoutMs = timeoutMs,
                operation = "NFC_CONNECTION",
                correlationId = correlationId,
                onTimeout = {
                    ErrorLogger.logNfcError(
                        message = "NFC connection timed out after ${timeoutMs}ms",
                        error = SimulatorError.NfcError.ConnectionFailed("Connection timeout"),
                        correlationId = correlationId
                    )
                }
            ),
            operation = operation
        )
    }
    
    /**
     * Cancels a specific operation by correlation ID.
     */
    suspend fun cancelOperation(correlationId: String) {
        val operationsToCancel = activeOperations.filter { (key, _) ->
            key.contains(correlationId)
        }
        
        operationsToCancel.forEach { (operationId, job) ->
            job.cancel("Operation cancelled by correlation ID: $correlationId")
            activeOperations.remove(operationId)
            
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.INFO,
                category = ErrorLogger.ErrorCategory.TIMEOUT,
                message = "Operation cancelled: $operationId",
                correlationId = correlationId
            )
        }
    }
    
    /**
     * Cancels all active operations.
     */
    suspend fun cancelAllOperations() {
        val operations = activeOperations.toMap()
        activeOperations.clear()
        
        operations.forEach { (operationId, job) ->
            job.cancel("All operations cancelled")
            
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.INFO,
                category = ErrorLogger.ErrorCategory.TIMEOUT,
                message = "Operation cancelled (bulk): $operationId"
            )
        }
    }
    
    /**
     * Gets information about currently active operations.
     */
    fun getActiveOperations(): Map<String, String> {
        return activeOperations.mapValues { (operationId, job) ->
            when {
                job.isCompleted -> "COMPLETED"
                job.isCancelled -> "CANCELLED"
                job.isActive -> "ACTIVE"
                else -> "UNKNOWN"
            }
        }
    }
    
    /**
     * Gets timeout statistics for monitoring.
     */
    fun getTimeoutStatistics(): Map<String, Any> {
        val timeoutEntries = ErrorLogger.getLogEntries(category = ErrorLogger.ErrorCategory.TIMEOUT)
        
        return mapOf(
            "totalTimeouts" to timeoutEntries.size,
            "activeOperations" to activeOperations.size,
            "timeoutsByOperation" to timeoutEntries.groupBy { 
                it.context["operation"] as? String ?: "unknown" 
            }.mapValues { it.value.size },
            "recentTimeouts" to timeoutEntries.filter {
                (Clock.System.now() - it.timestamp).inWholeMinutes < 5
            }.size
        )
    }
}