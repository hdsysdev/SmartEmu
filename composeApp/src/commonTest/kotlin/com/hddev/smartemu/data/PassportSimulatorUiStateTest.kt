package com.hddev.smartemu.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassportSimulatorUiStateTest {
    
    private val validPassportData = PassportData(
        passportNumber = "AB123456C",
        dateOfBirth = LocalDate(1990, 1, 1),
        expiryDate = LocalDate(2030, 12, 31),
        firstName = "John",
        lastName = "Doe"
    )
    
    private val testTimestamp = Instant.fromEpochSeconds(1640995200)
    
    @Test
    fun testInitialState() {
        val state = PassportSimulatorUiState.initial()
        
        assertEquals(PassportData.empty(), state.passportData)
        assertEquals(SimulationStatus.STOPPED, state.simulationStatus)
        assertFalse(state.nfcAvailable)
        assertFalse(state.hasNfcPermission)
        assertEquals(emptyMap(), state.validationErrors)
        assertFalse(state.isLoading)
        assertEquals(null, state.errorMessage)
        assertEquals(emptyList(), state.nfcEvents)
        assertEquals(100, state.maxEventCount)
    }
    
    @Test
    fun testCanStartSimulation() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            isLoading = false
        )
        
        assertTrue(state.canStartSimulation())
    }
    
    @Test
    fun testCannotStartSimulationWithInvalidData() {
        val state = PassportSimulatorUiState(
            passportData = PassportData.empty(), // Invalid data
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            isLoading = false
        )
        
        assertFalse(state.canStartSimulation())
    }
    
    @Test
    fun testCannotStartSimulationWithoutNfc() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = false, // No NFC
            hasNfcPermission = true,
            isLoading = false
        )
        
        assertFalse(state.canStartSimulation())
    }
    
    @Test
    fun testCannotStartSimulationWithoutPermission() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = false, // No permission
            isLoading = false
        )
        
        assertFalse(state.canStartSimulation())
    }
    
    @Test
    fun testCannotStartSimulationWhenLoading() {
        val state = PassportSimulatorUiState(
            passportData = validPassportData,
            simulationStatus = SimulationStatus.STOPPED,
            nfcAvailable = true,
            hasNfcPermission = true,
            isLoading = true // Loading
        )
        
        assertFalse(state.canStartSimulation())
    }
    
    @Test
    fun testCanStopSimulation() {
        val activeState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            isLoading = false
        )
        assertTrue(activeState.canStopSimulation())
        
        val startingState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STARTING,
            isLoading = false
        )
        assertTrue(startingState.canStopSimulation())
    }
    
    @Test
    fun testCannotStopSimulationWhenStopped() {
        val state = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            isLoading = false
        )
        
        assertFalse(state.canStopSimulation())
    }
    
    @Test
    fun testIsPassportFormEnabled() {
        val stoppedState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            isLoading = false
        )
        assertTrue(stoppedState.isPassportFormEnabled())
        
        val activeState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE,
            isLoading = false
        )
        assertFalse(activeState.isPassportFormEnabled())
        
        val loadingState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED,
            isLoading = true
        )
        assertFalse(loadingState.isPassportFormEnabled())
    }
    
    @Test
    fun testNeedsNfcPermission() {
        val needsPermission = PassportSimulatorUiState(
            nfcAvailable = true,
            hasNfcPermission = false
        )
        assertTrue(needsPermission.needsNfcPermission())
        
        val hasPermission = PassportSimulatorUiState(
            nfcAvailable = true,
            hasNfcPermission = true
        )
        assertFalse(hasPermission.needsNfcPermission())
        
        val noNfc = PassportSimulatorUiState(
            nfcAvailable = false,
            hasNfcPermission = false
        )
        assertFalse(noNfc.needsNfcPermission())
    }
    
    @Test
    fun testHasValidationErrors() {
        val withErrors = PassportSimulatorUiState(
            validationErrors = mapOf("field" to "error")
        )
        assertTrue(withErrors.hasValidationErrors())
        
        val withoutErrors = PassportSimulatorUiState(
            validationErrors = emptyMap()
        )
        assertFalse(withoutErrors.hasValidationErrors())
    }
    
    @Test
    fun testIsSimulationActive() {
        val activeState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.ACTIVE
        )
        assertTrue(activeState.isSimulationActive())
        
        val stoppedState = PassportSimulatorUiState(
            simulationStatus = SimulationStatus.STOPPED
        )
        assertFalse(stoppedState.isSimulationActive())
    }
    
    @Test
    fun testHasRecentErrors() {
        val errorEvent = NfcEvent.error(testTimestamp, "Test error")
        val successEvent = NfcEvent.connectionEstablished(testTimestamp)
        
        val withErrors = PassportSimulatorUiState(
            nfcEvents = listOf(successEvent, errorEvent)
        )
        assertTrue(withErrors.hasRecentErrors())
        
        val withoutErrors = PassportSimulatorUiState(
            nfcEvents = listOf(successEvent)
        )
        assertFalse(withoutErrors.hasRecentErrors())
    }
    
    @Test
    fun testGetRecentEvents() {
        val events = (1..15).map { i ->
            NfcEvent.connectionEstablished(Instant.fromEpochSeconds(i.toLong()))
        }
        
        val state = PassportSimulatorUiState(nfcEvents = events)
        val recentEvents = state.getRecentEvents(5)
        
        assertEquals(5, recentEvents.size)
        assertEquals(events.takeLast(5), recentEvents)
    }
    
    @Test
    fun testGetEventsByType() {
        val connectionEvent = NfcEvent.connectionEstablished(testTimestamp)
        val errorEvent = NfcEvent.error(testTimestamp, "Test error")
        val bacEvent = NfcEvent.bacAuthenticationRequest(testTimestamp)
        
        val state = PassportSimulatorUiState(
            nfcEvents = listOf(connectionEvent, errorEvent, bacEvent)
        )
        
        val errorEvents = state.getEventsByType(NfcEventType.ERROR)
        assertEquals(1, errorEvents.size)
        assertEquals(errorEvent, errorEvents.first())
        
        val connectionEvents = state.getEventsByType(NfcEventType.CONNECTION_ESTABLISHED)
        assertEquals(1, connectionEvents.size)
        assertEquals(connectionEvent, connectionEvents.first())
    }
    
    @Test
    fun testWithPassportData() {
        val initialState = PassportSimulatorUiState.initial()
        val newState = initialState.withPassportData(validPassportData)
        
        assertEquals(validPassportData, newState.passportData)
        assertEquals(validPassportData.getValidationErrors(), newState.validationErrors)
    }
    
    @Test
    fun testWithSimulationStatus() {
        val initialState = PassportSimulatorUiState.initial()
        val newState = initialState.withSimulationStatus(SimulationStatus.ACTIVE)
        
        assertEquals(SimulationStatus.ACTIVE, newState.simulationStatus)
    }
    
    @Test
    fun testWithNfcStatus() {
        val initialState = PassportSimulatorUiState.initial()
        val newState = initialState.withNfcStatus(available = true, hasPermission = true)
        
        assertTrue(newState.nfcAvailable)
        assertTrue(newState.hasNfcPermission)
    }
    
    @Test
    fun testWithNewEvent() {
        val initialState = PassportSimulatorUiState.initial()
        val event = NfcEvent.connectionEstablished(testTimestamp)
        val newState = initialState.withNewEvent(event)
        
        assertEquals(1, newState.nfcEvents.size)
        assertEquals(event, newState.nfcEvents.first())
    }
    
    @Test
    fun testWithNewEventRespectingMaxCount() {
        val events = (1..105).map { i ->
            NfcEvent.connectionEstablished(Instant.fromEpochSeconds(i.toLong()))
        }
        
        var state = PassportSimulatorUiState.initial()
        events.forEach { event ->
            state = state.withNewEvent(event)
        }
        
        assertEquals(100, state.nfcEvents.size) // Should respect maxEventCount
        assertEquals(events.takeLast(100), state.nfcEvents)
    }
    
    @Test
    fun testWithClearedEvents() {
        val event = NfcEvent.connectionEstablished(testTimestamp)
        val stateWithEvents = PassportSimulatorUiState.initial().withNewEvent(event)
        val clearedState = stateWithEvents.withClearedEvents()
        
        assertEquals(emptyList(), clearedState.nfcEvents)
    }
    
    @Test
    fun testWithLoading() {
        val initialState = PassportSimulatorUiState.initial()
        val loadingState = initialState.withLoading(true)
        
        assertTrue(loadingState.isLoading)
    }
    
    @Test
    fun testWithError() {
        val initialState = PassportSimulatorUiState.initial()
        val errorState = initialState.withError("Test error")
        
        assertEquals("Test error", errorState.errorMessage)
    }
    
    @Test
    fun testNfcNotAvailableFactory() {
        val state = PassportSimulatorUiState.nfcNotAvailable()
        
        assertFalse(state.nfcAvailable)
        assertEquals("NFC is not available on this device", state.errorMessage)
    }
    
    @Test
    fun testNfcPermissionDeniedFactory() {
        val state = PassportSimulatorUiState.nfcPermissionDenied()
        
        assertTrue(state.nfcAvailable)
        assertFalse(state.hasNfcPermission)
        assertEquals("NFC permissions are required for simulation", state.errorMessage)
    }
}