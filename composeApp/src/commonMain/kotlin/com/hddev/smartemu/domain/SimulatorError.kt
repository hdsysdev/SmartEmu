package com.hddev.smartemu.domain

/**
 * Sealed class representing different types of errors that can occur in the passport simulator.
 */
sealed class SimulatorError(
    message: String,
    cause: Throwable? = null
) : Throwable(message, cause) {
    
    /**
     * Errors related to passport data validation.
     */
    sealed class ValidationError(message: String) : SimulatorError(message) {
        object InvalidPassportNumber : ValidationError("Invalid passport number format")
        object InvalidDateOfBirth : ValidationError("Invalid date of birth")
        object InvalidExpiryDate : ValidationError("Invalid expiry date")
        object InvalidCountryCode : ValidationError("Invalid country code")
        object InvalidNames : ValidationError("Invalid first name or last name")
        object MissingRequiredFields : ValidationError("Required fields are missing")
        data class CustomValidation(val field: String, val reason: String) : 
            ValidationError("Validation failed for $field: $reason")
    }
    
    /**
     * Errors related to NFC hardware and permissions.
     */
    sealed class NfcError(message: String, cause: Throwable? = null) : SimulatorError(message, cause) {
        object NfcNotAvailable : NfcError("NFC hardware is not available on this device")
        object NfcDisabled : NfcError("NFC is disabled in device settings")
        object PermissionDenied : NfcError("NFC permissions have been denied")
        object HceNotSupported : NfcError("Host Card Emulation is not supported on this device")
        data class ConnectionFailed(val reason: String) : NfcError("NFC connection failed: $reason")
        data class ServiceError(val reason: String, val throwable: Throwable? = null) : 
            NfcError("NFC service error: $reason", throwable)
    }
    
    /**
     * Errors related to authentication protocols (BAC/PACE).
     */
    sealed class ProtocolError(message: String, cause: Throwable? = null) : SimulatorError(message, cause) {
        object BacAuthenticationFailed : ProtocolError("BAC authentication failed")
        object PaceAuthenticationFailed : ProtocolError("PACE authentication failed")
        data class InvalidApduCommand(val command: String) : 
            ProtocolError("Invalid APDU command: $command")
        data class ProtocolViolation(val protocol: String, val reason: String) : 
            ProtocolError("Protocol violation in $protocol: $reason")
        data class CryptographicError(val operation: String, val throwable: Throwable) : 
            ProtocolError("Cryptographic error during $operation", throwable)
    }
    
    /**
     * System-level errors and unexpected exceptions.
     */
    sealed class SystemError(message: String, cause: Throwable? = null) : SimulatorError(message, cause) {
        data class LibraryInitializationFailed(val library: String, val throwable: Throwable) : 
            SystemError("Failed to initialize $library library", throwable)
        data class UnexpectedError(val throwable: Throwable) : 
            SystemError("Unexpected error occurred: ${throwable.message}", throwable)
        object SimulationAlreadyRunning : SystemError("Simulation is already running")
        object SimulationNotRunning : SystemError("Simulation is not currently running")
        data class ConfigurationError(val setting: String) : 
            SystemError("Configuration error: $setting")
    }
    
    /**
     * Converts this error to a user-friendly message.
     */
    fun toUserMessage(): String {
        return when (this) {
            is ValidationError.InvalidPassportNumber -> "Please enter a valid passport number (6-9 alphanumeric characters)"
            is ValidationError.InvalidDateOfBirth -> "Please enter a valid date of birth in the past"
            is ValidationError.InvalidExpiryDate -> "Please enter a valid expiry date in the future"
            is ValidationError.InvalidCountryCode -> "Please select a valid country"
            is ValidationError.InvalidNames -> "Please enter valid first and last names"
            is ValidationError.MissingRequiredFields -> "Please fill in all required fields"
            is ValidationError.CustomValidation -> reason
            
            is NfcError.NfcNotAvailable -> "This device does not support NFC functionality"
            is NfcError.NfcDisabled -> "Please enable NFC in your device settings"
            is NfcError.PermissionDenied -> "NFC permissions are required for simulation"
            is NfcError.HceNotSupported -> "This device does not support Host Card Emulation"
            is NfcError.ConnectionFailed -> "Failed to establish NFC connection: $reason"
            is NfcError.ServiceError -> "NFC service error occurred"
            
            is ProtocolError.BacAuthenticationFailed -> "BAC authentication failed"
            is ProtocolError.PaceAuthenticationFailed -> "PACE authentication failed"
            is ProtocolError.InvalidApduCommand -> "Invalid command received from NFC reader"
            is ProtocolError.ProtocolViolation -> "Protocol error occurred"
            is ProtocolError.CryptographicError -> "Cryptographic operation failed"
            
            is SystemError.LibraryInitializationFailed -> "Failed to initialize required libraries"
            is SystemError.UnexpectedError -> "An unexpected error occurred"
            is SystemError.SimulationAlreadyRunning -> "Simulation is already running"
            is SystemError.SimulationNotRunning -> "No simulation is currently running"
            is SystemError.ConfigurationError -> "Configuration error: $setting"
        }
    }
    
    /**
     * Returns true if this error is recoverable and the user can retry the operation.
     */
    fun isRecoverable(): Boolean {
        return when (this) {
            is ValidationError -> true
            is NfcError.NfcDisabled -> true
            is NfcError.PermissionDenied -> true
            is NfcError.ConnectionFailed -> true
            is SystemError.SimulationAlreadyRunning -> true
            is SystemError.SimulationNotRunning -> true
            else -> false
        }
    }
}