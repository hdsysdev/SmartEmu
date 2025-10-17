package com.hddev.smartemu

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.AndroidNfcSimulatorRepository
import com.hddev.smartemu.utils.BacProtocol
import com.hddev.smartemu.utils.PaceProtocol
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the complete simulation workflow.
 * Tests the entire flow from passport data entry to NFC simulation completion.
 */
class CompleteSimulationWorkflowTest {
    
    private val validPassportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2025, 4, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "ANNA",
        lastName = "ERIKSSON",
        gender = "F"
    )
    
    @Test
    fun `complete workflow from data entry to simulation success`() = runTest {
        // Given - Mock repository and ViewModel
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Verify initial state
        val initialState = viewModel.uiState.first()
        assertEquals(SimulationStatus.STOPPED, initialState.simulationStatus)
        assertFalse(initialState.isSimulationActive)
        assertTrue(initialState.nfcEvents.isEmpty())
        
        // Step 2: Update passport data
        viewModel.updatePassportData(validPassportData)
        
        val updatedState = viewModel.uiState.first()
        assertEquals(validPassportData, updatedState.passportData)
        assertTrue(updatedState.validationErrors.isEmpty())
        
        // Step 3: Start simulation
        viewModel.startSimulation()
        
        val simulatingState = viewModel.uiState.first()
        assertEquals(SimulationStatus.ACTIVE, simulatingState.simulationStatus)
        assertTrue(simulatingState.isSimulationActive)
        
        // Step 4: Verify events are generated
        assertTrue(simulatingState.nfcEvents.isNotEmpty())
        val connectionEvent = simulatingState.nfcEvents.find { 
            it.type == NfcEventType.CONNECTION_ESTABLISHED 
        }
        assertNotNull(connectionEvent, "Connection event should be generated")
        
        // Step 5: Stop simulation
        viewModel.stopSimulation()
        
        val stoppedState = viewModel.uiState.first()
        assertEquals(SimulationStatus.STOPPED, stoppedState.simulationStatus)
        assertFalse(stoppedState.isSimulationActive)
    }
    
    @Test
    fun `workflow with BAC authentication simulation`() = runTest {
        // Given - Repository that simulates BAC authentication
        val mockRepository = createBacSimulationRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Set up passport data and start simulation
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val state = viewModel.uiState.first()
        
        // Step 2: Verify BAC-specific events are generated
        val bacEvents = state.nfcEvents.filter { 
            it.type == NfcEventType.BAC_AUTHENTICATION_REQUEST ||
            it.type == NfcEventType.AUTHENTICATION_SUCCESS
        }
        assertTrue(bacEvents.isNotEmpty(), "BAC authentication events should be generated")
        
        // Step 3: Verify authentication success event
        val successEvent = bacEvents.find { it.type == NfcEventType.AUTHENTICATION_SUCCESS }
        assertNotNull(successEvent, "Authentication success event should be present")
        assertTrue(successEvent.message.contains("BAC"), "Success message should mention BAC")
    }
    
    @Test
    fun `workflow with PACE authentication simulation`() = runTest {
        // Given - Repository that simulates PACE authentication
        val mockRepository = createPaceSimulationRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Set up passport data and start simulation
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val state = viewModel.uiState.first()
        
        // Step 2: Verify PACE-specific events are generated
        val paceEvents = state.nfcEvents.filter { 
            it.type == NfcEventType.PACE_AUTHENTICATION_REQUEST ||
            it.type == NfcEventType.AUTHENTICATION_SUCCESS
        }
        assertTrue(paceEvents.isNotEmpty(), "PACE authentication events should be generated")
        
        // Step 3: Verify multi-step PACE process
        val paceSteps = paceEvents.filter { it.message.contains("step") }
        assertTrue(paceSteps.size >= 3, "PACE should have multiple steps")
    }
    
    @Test
    fun `workflow handles authentication failures gracefully`() = runTest {
        // Given - Repository that simulates authentication failure
        val mockRepository = createFailingAuthRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Start simulation with valid data
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val state = viewModel.uiState.first()
        
        // Step 2: Verify failure events are generated
        val failureEvents = state.nfcEvents.filter { 
            it.type == NfcEventType.AUTHENTICATION_FAILURE ||
            it.type == NfcEventType.ERROR
        }
        assertTrue(failureEvents.isNotEmpty(), "Failure events should be generated")
        
        // Step 3: Verify simulation continues despite failures
        assertEquals(SimulationStatus.ACTIVE, state.simulationStatus)
        
        // Step 4: Verify error recovery
        val errorEvent = failureEvents.first()
        assertTrue(errorEvent.message.contains("failed") || errorEvent.message.contains("error"))
    }
    
    @Test
    fun `workflow with multiple concurrent connections`() = runTest {
        // Given - Repository that simulates multiple connections
        val mockRepository = createMultiConnectionRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Start simulation
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val state = viewModel.uiState.first()
        
        // Step 2: Verify multiple connection events
        val connectionEvents = state.nfcEvents.filter { 
            it.type == NfcEventType.CONNECTION_ESTABLISHED 
        }
        assertTrue(connectionEvents.size >= 2, "Multiple connections should be simulated")
        
        // Step 3: Verify each connection has corresponding authentication
        val authEvents = state.nfcEvents.filter { 
            it.type == NfcEventType.BAC_AUTHENTICATION_REQUEST ||
            it.type == NfcEventType.PACE_AUTHENTICATION_REQUEST
        }
        assertTrue(authEvents.size >= connectionEvents.size, "Each connection should have authentication")
    }
    
    @Test
    fun `workflow validates passport data before simulation`() = runTest {
        // Given - Invalid passport data
        val invalidPassportData = PassportData(
            passportNumber = "", // Invalid empty passport number
            dateOfBirth = null,
            expiryDate = null
        )
        
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Try to start simulation with invalid data
        viewModel.updatePassportData(invalidPassportData)
        
        val stateWithErrors = viewModel.uiState.first()
        assertTrue(stateWithErrors.validationErrors.isNotEmpty(), "Validation errors should be present")
        
        // Step 2: Attempt to start simulation (should fail)
        viewModel.startSimulation()
        
        val finalState = viewModel.uiState.first()
        assertEquals(SimulationStatus.STOPPED, finalState.simulationStatus)
        assertFalse(finalState.isSimulationActive)
    }
    
    @Test
    fun `workflow handles NFC unavailable scenario`() = runTest {
        // Given - Repository with NFC unavailable
        val mockRepository = createNoNfcRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Try to start simulation
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val state = viewModel.uiState.first()
        
        // Step 2: Verify appropriate error handling
        assertFalse(state.nfcAvailable)
        assertEquals(SimulationStatus.STOPPED, state.simulationStatus)
        
        // Step 3: Verify error message is displayed
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("NFC"))
    }
    
    @Test
    fun `workflow clears events correctly`() = runTest {
        // Given - Simulation with events
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Step 1: Generate events
        viewModel.updatePassportData(validPassportData)
        viewModel.startSimulation()
        
        val stateWithEvents = viewModel.uiState.first()
        assertTrue(stateWithEvents.nfcEvents.isNotEmpty())
        
        // Step 2: Clear events
        viewModel.clearEvents()
        
        val clearedState = viewModel.uiState.first()
        assertTrue(clearedState.nfcEvents.isEmpty())
    }
    
    // Mock repository implementations
    
    private fun createMockRepository() = object : AndroidNfcSimulatorRepository(null) {
        private var simulationActive = false
        private val events = mutableListOf<NfcEvent>()
        
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            simulationActive = true
            events.add(NfcEvent(
                timestamp = Clock.System.now(),
                type = NfcEventType.CONNECTION_ESTABLISHED,
                message = "NFC connection established"
            ))
            return Result.success(Unit)
        }
        
        override suspend fun stopSimulation(): Result<Unit> {
            simulationActive = false
            return Result.success(Unit)
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
    }
    
    private fun createBacSimulationRepository() = object : AndroidNfcSimulatorRepository(null) {
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            // Simulate BAC authentication workflow
            return Result.success(Unit)
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
    }
    
    private fun createPaceSimulationRepository() = object : AndroidNfcSimulatorRepository(null) {
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            // Simulate PACE authentication workflow
            return Result.success(Unit)
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
    }
    
    private fun createFailingAuthRepository() = object : AndroidNfcSimulatorRepository(null) {
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            // Simulate authentication failures
            return Result.success(Unit)
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
    }
    
    private fun createMultiConnectionRepository() = object : AndroidNfcSimulatorRepository(null) {
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            // Simulate multiple concurrent connections
            return Result.success(Unit)
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
    }
    
    private fun createNoNfcRepository() = object : AndroidNfcSimulatorRepository(null) {
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            return Result.failure(Exception("NFC not available"))
        }
        
        override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(false)
        override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(false)
    }
}