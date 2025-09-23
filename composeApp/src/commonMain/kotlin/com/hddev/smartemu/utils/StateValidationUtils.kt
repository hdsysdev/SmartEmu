package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.ValidationResult

/**
 * Utility class for validating UI state transitions and ensuring state consistency.
 */
object StateValidationUtils {
    
    /**
     * Validates if a simulation status transition is allowed given the current UI state.
     */
    fun validateStatusTransition(
        currentState: PassportSimulatorUiState,
        newStatus: SimulationStatus
    ): ValidationResult {
        // Check if the basic status transition is valid
        if (!SimulationStatus.isValidTransition(currentState.simulationStatus, newStatus)) {
            return ValidationResult(
                isValid = false,
                errorMessage = "Invalid status transition from ${currentState.simulationStatus} to $newStatus"
            )
        }
        
        // Additional validation based on the target status
        return when (newStatus) {
            SimulationStatus.STARTING -> validateStartingTransition(currentState)
            SimulationStatus.ACTIVE -> validateActiveTransition(currentState)
            SimulationStatus.STOPPING -> validateStoppingTransition(currentState)
            SimulationStatus.STOPPED -> validateStoppedTransition(currentState)
            SimulationStatus.ERROR -> ValidationResult(true) // Error transitions are always allowed
        }
    }
    
    /**
     * Validates if the UI state is consistent and all required conditions are met.
     */
    fun validateUiStateConsistency(state: PassportSimulatorUiState): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Check NFC availability consistency
        if (!state.nfcAvailable && state.hasNfcPermission) {
            errors.add("Cannot have NFC permission without NFC availability")
        }
        
        // Check simulation status consistency
        if (state.simulationStatus.isActiveOrStarting() && !state.nfcAvailable) {
            errors.add("Cannot have active simulation without NFC availability")
        }
        
        if (state.simulationStatus.isActiveOrStarting() && !state.hasNfcPermission) {
            errors.add("Cannot have active simulation without NFC permission")
        }
        
        // Check passport data consistency
        if (state.simulationStatus == SimulationStatus.ACTIVE && !state.passportData.isValid()) {
            errors.add("Cannot have active simulation with invalid passport data")
        }
        
        // Check loading state consistency
        if (state.isLoading && state.simulationStatus.isTransitioning()) {
            // This is expected - loading during transitions
        } else if (state.isLoading && !state.simulationStatus.isTransitioning()) {
            errors.add("Loading state should only be true during status transitions")
        }
        
        // Check event count limits
        if (state.nfcEvents.size > state.maxEventCount) {
            errors.add("Event count exceeds maximum limit")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult(true)
        } else {
            ValidationResult(false, errors.joinToString("; "))
        }
    }
    
    /**
     * Validates if adding a new NFC event is appropriate given the current state.
     */
    fun validateEventAddition(
        currentState: PassportSimulatorUiState,
        newEvent: NfcEvent
    ): ValidationResult {
        // Check if event type is appropriate for current simulation status
        return when (newEvent.type) {
            NfcEventType.CONNECTION_ESTABLISHED -> {
                if (currentState.simulationStatus != SimulationStatus.ACTIVE) {
                    ValidationResult(false, "Connection events should only occur when simulation is active")
                } else {
                    ValidationResult(true)
                }
            }
            
            NfcEventType.BAC_AUTHENTICATION_REQUEST,
            NfcEventType.PACE_AUTHENTICATION_REQUEST,
            NfcEventType.AUTHENTICATION_SUCCESS,
            NfcEventType.AUTHENTICATION_FAILURE -> {
                if (currentState.simulationStatus != SimulationStatus.ACTIVE) {
                    ValidationResult(false, "Authentication events should only occur when simulation is active")
                } else {
                    ValidationResult(true)
                }
            }
            
            NfcEventType.CONNECTION_LOST -> {
                // Connection lost can happen in any active state
                ValidationResult(true)
            }
            
            NfcEventType.ERROR -> {
                // Error events can happen at any time
                ValidationResult(true)
            }
        }
    }
    
    /**
     * Suggests the next appropriate action based on the current UI state.
     */
    fun suggestNextAction(state: PassportSimulatorUiState): String {
        return when {
            !state.nfcAvailable -> "Enable NFC on your device to use the simulator"
            !state.hasNfcPermission -> "Grant NFC permissions to start simulation"
            state.hasValidationErrors() -> "Fix passport data validation errors"
            state.simulationStatus == SimulationStatus.STOPPED && state.passportData.isValid() -> 
                "Ready to start simulation"
            state.simulationStatus == SimulationStatus.ACTIVE -> 
                "Simulation is active - bring another NFC device close to test"
            state.simulationStatus.isTransitioning() -> "Please wait..."
            state.simulationStatus == SimulationStatus.ERROR -> 
                "Check error message and try restarting simulation"
            else -> "Check your settings and try again"
        }
    }
    
    private fun validateStartingTransition(state: PassportSimulatorUiState): ValidationResult {
        return when {
            !state.nfcAvailable -> ValidationResult(false, "NFC is not available")
            !state.hasNfcPermission -> ValidationResult(false, "NFC permission is required")
            !state.passportData.isValid() -> ValidationResult(false, "Passport data is invalid")
            state.isLoading -> ValidationResult(false, "Another operation is in progress")
            else -> ValidationResult(true)
        }
    }
    
    private fun validateActiveTransition(state: PassportSimulatorUiState): ValidationResult {
        return when {
            state.simulationStatus != SimulationStatus.STARTING -> 
                ValidationResult(false, "Can only transition to ACTIVE from STARTING")
            !state.nfcAvailable -> ValidationResult(false, "NFC is not available")
            !state.hasNfcPermission -> ValidationResult(false, "NFC permission is required")
            else -> ValidationResult(true)
        }
    }
    
    private fun validateStoppingTransition(state: PassportSimulatorUiState): ValidationResult {
        return when {
            state.simulationStatus != SimulationStatus.ACTIVE -> 
                ValidationResult(false, "Can only stop from ACTIVE status")
            state.isLoading -> ValidationResult(false, "Another operation is in progress")
            else -> ValidationResult(true)
        }
    }
    
    private fun validateStoppedTransition(state: PassportSimulatorUiState): ValidationResult {
        return when {
            state.simulationStatus !in listOf(SimulationStatus.STOPPING, SimulationStatus.STARTING, SimulationStatus.ERROR) -> 
                ValidationResult(false, "Invalid transition to STOPPED")
            else -> ValidationResult(true)
        }
    }
}

