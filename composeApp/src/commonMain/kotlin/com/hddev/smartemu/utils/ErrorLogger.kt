package com.hddev.smartemu.utils

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.domain.SimulatorError
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Centralized error logging utility for the passport simulator.
 * Provides structured logging with different severity levels and error categorization.
 */
object ErrorLogger {
    
    /**
     * Log severity levels for error categorization.
     */
    enum class LogLevel {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
    
    /**
     * Error categories for better organization and filtering.
     */
    enum class ErrorCategory {
        NFC_HARDWARE,
        AUTHENTICATION,
        PROTOCOL_VIOLATION,
        CRYPTOGRAPHIC,
        VALIDATION,
        SYSTEM,
        TIMEOUT,
        RECOVERY
    }
    
    /**
     * Detailed error log entry with structured information.
     */
    data class ErrorLogEntry(
        val timestamp: Instant,
        val level: LogLevel,
        val category: ErrorCategory,
        val message: String,
        val error: SimulatorError? = null,
        val context: Map<String, Any> = emptyMap(),
        val stackTrace: String? = null,
        val correlationId: String? = null
    )
    
    private val logEntries = mutableListOf<ErrorLogEntry>()
    private const val MAX_LOG_ENTRIES = 1000
    
    /**
     * Logs an error with detailed context information.
     */
    fun logError(
        level: LogLevel,
        category: ErrorCategory,
        message: String,
        error: SimulatorError? = null,
        context: Map<String, Any> = emptyMap(),
        throwable: Throwable? = null,
        correlationId: String? = null
    ): ErrorLogEntry {
        val entry = ErrorLogEntry(
            timestamp = Clock.System.now(),
            level = level,
            category = category,
            message = message,
            error = error,
            context = context,
            stackTrace = throwable?.stackTraceToString(),
            correlationId = correlationId
        )
        
        synchronized(logEntries) {
            logEntries.add(entry)
            
            // Keep only the most recent entries to prevent memory issues
            if (logEntries.size > MAX_LOG_ENTRIES) {
                logEntries.removeAt(0)
            }
        }
        
        // Print to console for debugging
        printLogEntry(entry)
        
        return entry
    }
    
    /**
     * Logs NFC hardware related errors.
     */
    fun logNfcError(
        message: String,
        error: SimulatorError.NfcError,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        return logError(
            level = LogLevel.ERROR,
            category = ErrorCategory.NFC_HARDWARE,
            message = message,
            error = error,
            context = context,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs authentication related errors.
     */
    fun logAuthenticationError(
        protocol: String,
        message: String,
        error: SimulatorError.ProtocolError,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf("protocol" to protocol)
        return logError(
            level = LogLevel.ERROR,
            category = ErrorCategory.AUTHENTICATION,
            message = message,
            error = error,
            context = enhancedContext,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs protocol violation errors.
     */
    fun logProtocolViolation(
        protocol: String,
        violation: String,
        apduCommand: String? = null,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf(
            "protocol" to protocol,
            "violation" to violation
        ) + (apduCommand?.let { mapOf("apduCommand" to it) } ?: emptyMap())
        
        return logError(
            level = LogLevel.WARNING,
            category = ErrorCategory.PROTOCOL_VIOLATION,
            message = "Protocol violation in $protocol: $violation",
            context = enhancedContext,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs cryptographic operation errors.
     */
    fun logCryptographicError(
        operation: String,
        message: String,
        throwable: Throwable,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf("operation" to operation)
        return logError(
            level = LogLevel.CRITICAL,
            category = ErrorCategory.CRYPTOGRAPHIC,
            message = message,
            context = enhancedContext,
            throwable = throwable,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs timeout related errors.
     */
    fun logTimeout(
        operation: String,
        timeoutMs: Long,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf(
            "operation" to operation,
            "timeoutMs" to timeoutMs
        )
        
        return logError(
            level = LogLevel.WARNING,
            category = ErrorCategory.TIMEOUT,
            message = "Operation timeout: $operation (${timeoutMs}ms)",
            context = enhancedContext,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs error recovery attempts.
     */
    fun logRecoveryAttempt(
        operation: String,
        attemptNumber: Int,
        maxAttempts: Int,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf(
            "operation" to operation,
            "attemptNumber" to attemptNumber,
            "maxAttempts" to maxAttempts
        )
        
        return logError(
            level = LogLevel.INFO,
            category = ErrorCategory.RECOVERY,
            message = "Recovery attempt $attemptNumber/$maxAttempts for: $operation",
            context = enhancedContext,
            correlationId = correlationId
        )
    }
    
    /**
     * Logs successful error recovery.
     */
    fun logRecoverySuccess(
        operation: String,
        attemptNumber: Int,
        context: Map<String, Any> = emptyMap(),
        correlationId: String? = null
    ): ErrorLogEntry {
        val enhancedContext = context + mapOf(
            "operation" to operation,
            "attemptNumber" to attemptNumber
        )
        
        return logError(
            level = LogLevel.INFO,
            category = ErrorCategory.RECOVERY,
            message = "Recovery successful for: $operation (attempt $attemptNumber)",
            context = enhancedContext,
            correlationId = correlationId
        )
    }
    
    /**
     * Converts an error log entry to an NFC event for UI display.
     */
    fun toNfcEvent(entry: ErrorLogEntry): NfcEvent {
        val eventType = when (entry.level) {
            LogLevel.ERROR, LogLevel.CRITICAL -> NfcEventType.ERROR
            LogLevel.WARNING -> NfcEventType.ERROR
            else -> NfcEventType.CONNECTION_ESTABLISHED
        }
        
        val details = mutableMapOf<String, Any>(
            "level" to entry.level.name,
            "category" to entry.category.name
        )
        
        entry.correlationId?.let { details["correlationId"] = it }
        details.putAll(entry.context)
        
        return NfcEvent(
            timestamp = entry.timestamp,
            type = eventType,
            message = entry.message,
            details = details
        )
    }
    
    /**
     * Gets all log entries, optionally filtered by level or category.
     */
    fun getLogEntries(
        minLevel: LogLevel? = null,
        category: ErrorCategory? = null,
        correlationId: String? = null
    ): List<ErrorLogEntry> {
        synchronized(logEntries) {
            return logEntries.filter { entry ->
                (minLevel == null || entry.level.ordinal >= minLevel.ordinal) &&
                (category == null || entry.category == category) &&
                (correlationId == null || entry.correlationId == correlationId)
            }
        }
    }
    
    /**
     * Clears all log entries.
     */
    fun clearLogs() {
        synchronized(logEntries) {
            logEntries.clear()
        }
    }
    
    /**
     * Gets error statistics for monitoring and debugging.
     */
    fun getErrorStatistics(): Map<String, Any> {
        synchronized(logEntries) {
            val totalErrors = logEntries.size
            val errorsByLevel = logEntries.groupBy { it.level }.mapValues { it.value.size }
            val errorsByCategory = logEntries.groupBy { it.category }.mapValues { it.value.size }
            val recentErrors = logEntries.filter { 
                (Clock.System.now() - it.timestamp).inWholeMinutes < 5 
            }.size
            
            return mapOf<String, Any>(
                "totalErrors" to totalErrors,
                "errorsByLevel" to errorsByLevel,
                "errorsByCategory" to errorsByCategory,
                "recentErrors" to recentErrors,
                "oldestEntry" to (logEntries.firstOrNull()?.timestamp ?: "none"),
                "newestEntry" to (logEntries.lastOrNull()?.timestamp ?: "none")
            )
        }
    }
    
    /**
     * Prints a log entry to console with formatting.
     */
    private fun printLogEntry(entry: ErrorLogEntry) {
        val levelPrefix = when (entry.level) {
            LogLevel.DEBUG -> "🔍 DEBUG"
            LogLevel.INFO -> "ℹ️ INFO"
            LogLevel.WARNING -> "⚠️ WARNING"
            LogLevel.ERROR -> "❌ ERROR"
            LogLevel.CRITICAL -> "🚨 CRITICAL"
        }
        
        val categoryPrefix = "[${entry.category.name}]"
        val correlationPrefix = entry.correlationId?.let { " [$it]" } ?: ""
        
        println("$levelPrefix $categoryPrefix$correlationPrefix ${entry.message}")
        
        if (entry.context.isNotEmpty()) {
            println("  Context: ${entry.context}")
        }
        
        if (entry.error != null) {
            println("  Error: ${entry.error}")
        }
        
        if (entry.stackTrace != null && entry.level == LogLevel.CRITICAL) {
            println("  Stack trace: ${entry.stackTrace}")
        }
    }
}