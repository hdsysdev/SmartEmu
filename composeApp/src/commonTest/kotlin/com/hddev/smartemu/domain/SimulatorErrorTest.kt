package com.hddev.smartemu.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatorErrorTest {
    
    @Test
    fun `ValidationError toUserMessage returns user-friendly messages`() {
        val errors = listOf(
            SimulatorError.ValidationError.InvalidPassportNumber to 
                "Please enter a valid passport number (6-9 alphanumeric characters)",
            SimulatorError.ValidationError.InvalidDateOfBirth to 
                "Please enter a valid date of birth in the past",
            SimulatorError.ValidationError.InvalidExpiryDate to 
                "Please enter a valid expiry date in the future",
            SimulatorError.ValidationError.InvalidCountryCode to 
                "Please select a valid country",
            SimulatorError.ValidationError.InvalidNames to 
                "Please enter valid first and last names",
            SimulatorError.ValidationError.MissingRequiredFields to 
                "Please fill in all required fields"
        )
        
        errors.forEach { (error, expectedMessage) ->
            assertEquals(expectedMessage, error.toUserMessage())
        }
    }
    
    @Test
    fun `CustomValidation error returns custom reason`() {
        val error = SimulatorError.ValidationError.CustomValidation("testField", "custom reason")
        assertEquals("custom reason", error.toUserMessage())
    }
    
    @Test
    fun `NfcError toUserMessage returns appropriate messages`() {
        val errors = listOf(
            SimulatorError.NfcError.NfcNotAvailable to 
                "This device does not support NFC functionality",
            SimulatorError.NfcError.NfcDisabled to 
                "Please enable NFC in your device settings",
            SimulatorError.NfcError.PermissionDenied to 
                "NFC permissions are required for simulation",
            SimulatorError.NfcError.HceNotSupported to 
                "This device does not support Host Card Emulation"
        )
        
        errors.forEach { (error, expectedMessage) ->
            assertEquals(expectedMessage, error.toUserMessage())
        }
    }
    
    @Test
    fun `ConnectionFailed error includes reason in message`() {
        val error = SimulatorError.NfcError.ConnectionFailed("timeout")
        assertEquals("Failed to establish NFC connection: timeout", error.toUserMessage())
    }
    
    @Test
    fun `ProtocolError toUserMessage returns appropriate messages`() {
        val errors = listOf(
            SimulatorError.ProtocolError.BacAuthenticationFailed to 
                "BAC authentication failed",
            SimulatorError.ProtocolError.PaceAuthenticationFailed to 
                "PACE authentication failed"
        )
        
        errors.forEach { (error, expectedMessage) ->
            assertEquals(expectedMessage, error.toUserMessage())
        }
    }
    
    @Test
    fun `SystemError toUserMessage returns appropriate messages`() {
        val errors = listOf(
            SimulatorError.SystemError.SimulationAlreadyRunning to 
                "Simulation is already running",
            SimulatorError.SystemError.SimulationNotRunning to 
                "No simulation is currently running"
        )
        
        errors.forEach { (error, expectedMessage) ->
            assertEquals(expectedMessage, error.toUserMessage())
        }
    }
    
    @Test
    fun `ValidationError isRecoverable returns true`() {
        val validationErrors = listOf(
            SimulatorError.ValidationError.InvalidPassportNumber,
            SimulatorError.ValidationError.InvalidDateOfBirth,
            SimulatorError.ValidationError.MissingRequiredFields
        )
        
        validationErrors.forEach { error ->
            assertTrue(error.isRecoverable(), "Expected ${error::class.simpleName} to be recoverable")
        }
    }
    
    @Test
    fun `recoverable NfcError isRecoverable returns true`() {
        val recoverableErrors = listOf(
            SimulatorError.NfcError.NfcDisabled,
            SimulatorError.NfcError.PermissionDenied,
            SimulatorError.NfcError.ConnectionFailed("test")
        )
        
        recoverableErrors.forEach { error ->
            assertTrue(error.isRecoverable(), "Expected ${error::class.simpleName} to be recoverable")
        }
    }
    
    @Test
    fun `non-recoverable errors isRecoverable returns false`() {
        val nonRecoverableErrors = listOf(
            SimulatorError.NfcError.NfcNotAvailable,
            SimulatorError.NfcError.HceNotSupported,
            SimulatorError.ProtocolError.BacAuthenticationFailed,
            SimulatorError.SystemError.UnexpectedError(RuntimeException("test"))
        )
        
        nonRecoverableErrors.forEach { error ->
            assertFalse(error.isRecoverable(), "Expected ${error::class.simpleName} to not be recoverable")
        }
    }
    
    @Test
    fun `recoverable SystemError isRecoverable returns true`() {
        val recoverableErrors = listOf(
            SimulatorError.SystemError.SimulationAlreadyRunning,
            SimulatorError.SystemError.SimulationNotRunning
        )
        
        recoverableErrors.forEach { error ->
            assertTrue(error.isRecoverable(), "Expected ${error::class.simpleName} to be recoverable")
        }
    }
    
    @Test
    fun `LibraryInitializationFailed includes library name`() {
        val error = SimulatorError.SystemError.LibraryInitializationFailed(
            "SCUBA", 
            RuntimeException("init failed")
        )
        assertEquals("Failed to initialize SCUBA library", error.toUserMessage())
    }
    
    @Test
    fun `UnexpectedError includes throwable message`() {
        val throwable = RuntimeException("Something went wrong")
        val error = SimulatorError.SystemError.UnexpectedError(throwable)
        assertEquals("Unexpected error occurred: Something went wrong", error.message)
    }
    
    @Test
    fun `ConfigurationError includes setting name`() {
        val error = SimulatorError.SystemError.ConfigurationError("NFC timeout")
        assertEquals("Configuration error: NFC timeout", error.toUserMessage())
    }
}