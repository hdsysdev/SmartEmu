package com.hddev.smartemu.data

import kotlinx.datetime.Instant

/**
 * Represents an NFC-related event that occurs during passport simulation.
 * Used for logging and displaying real-time feedback to users.
 */
data class NfcEvent(
    val timestamp: Instant,
    val type: NfcEventType,
    val message: String,
    val details: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * Creates a connection established event.
         */
        fun connectionEstablished(timestamp: Instant, readerInfo: String = ""): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.CONNECTION_ESTABLISHED,
                message = "NFC connection established",
                details = if (readerInfo.isNotEmpty()) mapOf("readerInfo" to readerInfo) else emptyMap()
            )
        }
        
        /**
         * Creates a BAC authentication request event.
         */
        fun bacAuthenticationRequest(timestamp: Instant, challengeData: String = ""): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.BAC_AUTHENTICATION_REQUEST,
                message = "BAC authentication requested",
                details = if (challengeData.isNotEmpty()) mapOf("challenge" to challengeData) else emptyMap()
            )
        }
        
        /**
         * Creates a PACE authentication request event.
         */
        fun paceAuthenticationRequest(timestamp: Instant, protocolInfo: String = ""): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.PACE_AUTHENTICATION_REQUEST,
                message = "PACE authentication requested",
                details = if (protocolInfo.isNotEmpty()) mapOf("protocol" to protocolInfo) else emptyMap()
            )
        }
        
        /**
         * Creates an authentication success event.
         */
        fun authenticationSuccess(timestamp: Instant, protocol: String): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.AUTHENTICATION_SUCCESS,
                message = "$protocol authentication successful",
                details = mapOf("protocol" to protocol)
            )
        }
        
        /**
         * Creates an authentication failure event.
         */
        fun authenticationFailure(timestamp: Instant, protocol: String, reason: String): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.AUTHENTICATION_FAILURE,
                message = "$protocol authentication failed: $reason",
                details = mapOf("protocol" to protocol, "reason" to reason)
            )
        }
        
        /**
         * Creates a connection lost event.
         */
        fun connectionLost(timestamp: Instant, reason: String = ""): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.CONNECTION_LOST,
                message = "NFC connection lost",
                details = if (reason.isNotEmpty()) mapOf("reason" to reason) else emptyMap()
            )
        }
        
        /**
         * Creates an error event.
         */
        fun error(timestamp: Instant, errorMessage: String, errorCode: String? = null): NfcEvent {
            return NfcEvent(
                timestamp = timestamp,
                type = NfcEventType.ERROR,
                message = "Error: $errorMessage",
                details = errorCode?.let { mapOf("errorCode" to it) } ?: emptyMap()
            )
        }
    }
}

/**
 * Types of NFC events that can occur during passport simulation.
 */
enum class NfcEventType {
    /**
     * NFC connection has been established with a reader device.
     */
    CONNECTION_ESTABLISHED,
    
    /**
     * A BAC (Basic Access Control) authentication request has been received.
     */
    BAC_AUTHENTICATION_REQUEST,
    
    /**
     * A PACE (Password Authenticated Connection Establishment) authentication request has been received.
     */
    PACE_AUTHENTICATION_REQUEST,
    
    /**
     * Authentication (BAC or PACE) has completed successfully.
     */
    AUTHENTICATION_SUCCESS,
    
    /**
     * Authentication (BAC or PACE) has failed.
     */
    AUTHENTICATION_FAILURE,
    
    /**
     * NFC connection has been lost or terminated.
     */
    CONNECTION_LOST,
    
    /**
     * An error has occurred during NFC operations.
     */
    ERROR;
    
    /**
     * Returns a human-readable description of the event type.
     */
    fun getDescription(): String {
        return when (this) {
            CONNECTION_ESTABLISHED -> "Connection Established"
            BAC_AUTHENTICATION_REQUEST -> "BAC Authentication Request"
            PACE_AUTHENTICATION_REQUEST -> "PACE Authentication Request"
            AUTHENTICATION_SUCCESS -> "Authentication Success"
            AUTHENTICATION_FAILURE -> "Authentication Failure"
            CONNECTION_LOST -> "Connection Lost"
            ERROR -> "Error"
        }
    }
    
    /**
     * Returns true if this event type represents an error condition.
     */
    fun isError(): Boolean {
        return this == AUTHENTICATION_FAILURE || this == ERROR
    }
    
    /**
     * Returns true if this event type represents a successful operation.
     */
    fun isSuccess(): Boolean {
        return this == CONNECTION_ESTABLISHED || this == AUTHENTICATION_SUCCESS
    }
}