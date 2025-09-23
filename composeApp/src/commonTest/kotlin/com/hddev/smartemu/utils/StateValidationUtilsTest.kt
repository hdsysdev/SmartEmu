package com.hddev.smartemu.utils

import com.hddev.smartemu.data.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateValidationUtilsTest {
    
    private val validPassportData = PassportData(
        passportNumber = "AB123456C",
        dateOfBirth = LocalDate(1990, 1, 1),
        expiryDate = LocalDate(2030, 12, 31),
        firstName = "John",
        lastName = "Doe"
    )
    
    private val testTimestamp = Instant.fromEpochSeconds(1640995200)
    
    @Test
    fun testValidStatusTransitionStoppedToStarting() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            isLoading = false
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STARTING)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testInvalidStatusTransitionStoppedToActive() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.ACTIVE)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Invalid status transition"))
    }
    
    @Test
    fun testValidStatusTransitionStartingToActive() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STARTING,
            nfcAvailable = true,
            hasNfcPermission = true
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.ACTIVE)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testInvalidStatusTransitionStartingWithoutNfc() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = false, // No NFC
            hasNfcPermission = true,
            passportData = validPassportData
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STARTING)
        assertFalse(result.isValid)
        assertEquals("NFC is not available", result.errorMessage)
    }
    
    @Test
    fun testInvalidStatusTransitionStartingWithoutPermission() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = false, // No permission
            passportData = validPassportData
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STARTING)
        assertFalse(result.isValid)
        assertEquals("NFC permission is required", result.errorMessage)
    }
    
    @Test
    fun testInvalidStatusTransitionStartingWithInvalidData() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            passportData = PassportData.empty() // Invalid data
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STARTING)
        assertFalse(result.isValid)
        assertEquals("Passport data is invalid", result.errorMessage)
    }
    
    @Test
    fun testInvalidStatusTransitionStartingWhileLoading() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            passportData = validPassportData,
            isLoading = true // Loading
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STARTING)
        assertFalse(result.isValid)
        assertEquals("Another operation is in progress", result.errorMessage)
    }
    
    @Test
    fun testValidStatusTransitionActiveToStopping() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            isLoading = false
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STOPPING)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testInvalidStatusTransitionActiveToStoppingWhileLoading() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            isLoading = true
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.STOPPING)
        assertFalse(result.isValid)
        assertEquals("Another operation is in progress", result.errorMessage)
    }
    
    @Test
    fun testValidStatusTransitionToError() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        
        val result = StateValidationUtils.validateStatusTransition(state, SimulationStatus.ERROR)
        assertTrue(result.isValid) // Error transitions are always allowed
    }
    
    @Test
    fun testValidUiStateConsistency() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.ACTIVE,
            nfcAvailable = true,
            hasNfcPermission = true,
            isLoading = false,
            nfcEvents = listOf(NfcEvent.connectionEstablished(testTimestamp))
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testInvalidUiStateConsistencyNfcPermissionWithoutAvailability() {
        val state = PassportSimulatorUiState(
            nfcAvailable = false,
            hasNfcPermission = true // Invalid: permission without availability
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Cannot have NFC permission without NFC availability"))
    }
    
    @Test
    fun testInvalidUiStateConsistencyActiveSimulationWithoutNfc() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            nfcAvailable = false // Invalid: active simulation without NFC
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Cannot have active simulation without NFC availability"))
    }
    
    @Test
    fun testInvalidUiStateConsistencyActiveSimulationWithoutPermission() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            nfcAvailable = true,
            hasNfcPermission = false // Invalid: active simulation without permission
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Cannot have active simulation without NFC permission"))
    }
    
    @Test
    fun testInvalidUiStateConsistencyActiveSimulationWithInvalidData() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            nfcAvailable = true,
            hasNfcPermission = true,
            passportData = PassportData.empty() // Invalid data
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Cannot have active simulation with invalid passport data"))
    }
    
    @Test
    fun testInvalidUiStateConsistencyTooManyEvents() {
        val events = (1..150).map { i ->
            NfcEvent.connectionEstablished(Instant.fromEpochSeconds(i.toLong()))
        }
        
        val state = PassportSimulatorUiState(
            nfcEvents = events,
            maxEventCount = 100
        )
        
        val result = StateValidationUtils.validateUiStateConsistency(state)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Event count exceeds maximum limit"))
    }
    
    @Test
    fun testValidEventAdditionConnectionEstablished() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        val event = NfcEvent.connectionEstablished(testTimestamp)
        
        val result = StateValidationUtils.validateEventAddition(state, event)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testInvalidEventAdditionConnectionEstablishedWhenStopped() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED
        )
        val event = NfcEvent.connectionEstablished(testTimestamp)
        
        val result = StateValidationUtils.validateEventAddition(state, event)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Connection events should only occur when simulation is active"))
    }
    
    @Test
    fun testValidEventAdditionAuthenticationRequest() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        val bacEvent = NfcEvent.bacAuthenticationRequest(testTimestamp)
        val paceEvent = NfcEvent.paceAuthenticationRequest(testTimestamp)
        
        assertTrue(StateValidationUtils.validateEventAddition(state, bacEvent).isValid)
        assertTrue(StateValidationUtils.validateEventAddition(state, paceEvent).isValid)
    }
    
    @Test
    fun testInvalidEventAdditionAuthenticationRequestWhenStopped() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED
        )
        val event = NfcEvent.bacAuthenticationRequest(testTimestamp)
        
        val result = StateValidationUtils.validateEventAddition(state, event)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.contains("Authentication events should only occur when simulation is active"))
    }
    
    @Test
    fun testValidEventAdditionConnectionLost() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        val event = NfcEvent.connectionLost(testTimestamp)
        
        val result = StateValidationUtils.validateEventAddition(state, event)
        assertTrue(result.isValid)
    }
    
    @Test
    fun testValidEventAdditionError() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED
        )
        val event = NfcEvent.error(testTimestamp, "Test error")
        
        val result = StateValidationUtils.validateEventAddition(state, event)
        assertTrue(result.isValid) // Error events can happen at any time
    }
    
    @Test
    fun testSuggestNextActionNfcNotAvailable() {
        val state = PassportSimulatorUiState(
            nfcAvailable = false
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Enable NFC on your device to use the simulator", suggestion)
    }
    
    @Test
    fun testSuggestNextActionNfcPermissionNeeded() {
        val state = PassportSimulatorUiState(
            nfcAvailable = true,
            hasNfcPermission = false
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Grant NFC permissions to start simulation", suggestion)
    }
    
    @Test
    fun testSuggestNextActionValidationErrors() {
        val state = PassportSimulatorUiState(
            nfcAvailable = true,
            hasNfcPermission = true,
            validationErrors = mapOf("field" to "error")
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Fix passport data validation errors", suggestion)
    }
    
    @Test
    fun testSuggestNextActionReadyToStart() {
        val state = PassportSimulatorUiState(
            nfcAvailable = true,
            hasNfcPermission = true,
            simulationStatus = SimulationStatus.STOPPED,
            passportData = validPassportData
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Ready to start simulation", suggestion)
    }
    
    @Test
    fun testSuggestNextActionSimulationActive() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Simulation is active - bring another NFC device close to test", suggestion)
    }
    
    @Test
    fun testSuggestNextActionTransitioning() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STARTING
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Please wait...", suggestion)
    }
    
    @Test
    fun testSuggestNextActionError() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ERROR
        )
        
        val suggestion = StateValidationUtils.suggestNextAction(state)
        assertEquals("Check error message and try restarting simulation", suggestion)
    }
}