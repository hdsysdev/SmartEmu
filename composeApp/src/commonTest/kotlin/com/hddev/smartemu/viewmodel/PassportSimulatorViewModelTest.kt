package com.hddev.smartemu.viewmodel

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.NfcSimulatorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PassportSimulatorViewModelTest {
    
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: MockNfcSimulatorRepository
    private lateinit var viewModel: PassportSimulatorViewModel
    
    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = MockNfcSimulatorRepository()
        viewModel = PassportSimulatorViewModel(mockRepository)
    }
    
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    @Test
    fun `initial state should be correct`() = runTest {
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        
        assertEquals(PassportData.empty(), state.passportData)
        assertEquals(SimulationStatus.STOPPED, state.simulationStatus)
        assertFalse(state.nfcAvailable)
        assertFalse(state.hasNfcPermission)
        assertTrue(state.validationErrors.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertTrue(state.nfcEvents.isEmpty())
    }
    
    @Test
    fun `updatePassportData should update state and validate`() = runTest {
        val passportData = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(passportData)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(passportData, state.passportData)
    }
    
    @Test
    fun `updatePassportNumber should update passport data`() = runTest {
        val passportNumber = "AB123456"
        
        viewModel.updatePassportNumber(passportNumber)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(passportNumber, state.passportData.passportNumber)
    }
    
    @Test
    fun `updateDateOfBirth should update passport data`() = runTest {
        val dateOfBirth = LocalDate(1990, 1, 1)
        
        viewModel.updateDateOfBirth(dateOfBirth)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(dateOfBirth, state.passportData.dateOfBirth)
    }
    
    @Test
    fun `updateExpiryDate should update passport data`() = runTest {
        val expiryDate = LocalDate(2030, 1, 1)
        
        viewModel.updateExpiryDate(expiryDate)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(expiryDate, state.passportData.expiryDate)
    }
    
    @Test
    fun `updateFirstName should update passport data`() = runTest {
        val firstName = "John"
        
        viewModel.updateFirstName(firstName)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(firstName, state.passportData.firstName)
    }
    
    @Test
    fun `updateLastName should update passport data`() = runTest {
        val lastName = "Doe"
        
        viewModel.updateLastName(lastName)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(lastName, state.passportData.lastName)
    }
    
    @Test
    fun `startSimulation should succeed when conditions are met`() = runTest {
        // Setup valid state
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(true)
        
        val validPassportData = PassportData(
            passportNumber = "AB1234567",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(validPassportData)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        // Start simulation
        mockRepository.setStartSimulationResult(Result.success(Unit))
        viewModel.startSimulation()
        advanceUntilIdle()
        
        assertTrue(mockRepository.startSimulationCalled)
        assertEquals(validPassportData, mockRepository.lastPassportData)
    }
    
    @Test
    fun `startSimulation should fail when NFC not available`() = runTest {
        mockRepository.setNfcAvailable(false)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertFalse(mockRepository.startSimulationCalled)
    }
    
    @Test
    fun `startSimulation should fail when no permissions`() = runTest {
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(false)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertFalse(mockRepository.startSimulationCalled)
    }
    
    @Test
    fun `startSimulation should handle repository failure`() = runTest {
        // Setup valid state
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(true)
        
        val validPassportData = PassportData(
            passportNumber = "AB1234567",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(validPassportData)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        // Set repository to fail
        mockRepository.setStartSimulationResult(Result.failure(Exception("Test error")))
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Failed to start simulation"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `stopSimulation should succeed when simulation is active`() = runTest {
        // Set simulation as active
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        mockRepository.setStopSimulationResult(Result.success(Unit))
        viewModel.stopSimulation()
        advanceUntilIdle()
        
        assertTrue(mockRepository.stopSimulationCalled)
    }
    
    @Test
    fun `stopSimulation should handle repository failure`() = runTest {
        // Set simulation as active
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        mockRepository.setStopSimulationResult(Result.failure(Exception("Test error")))
        viewModel.stopSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Failed to stop simulation"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `requestNfcPermissions should update permission status on success`() = runTest {
        mockRepository.setRequestPermissionsResult(Result.success(true))
        
        viewModel.requestNfcPermissions()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertTrue(state.hasNfcPermission)
        assertNull(state.errorMessage)
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `requestNfcPermissions should handle permission denial`() = runTest {
        mockRepository.setRequestPermissionsResult(Result.success(false))
        
        viewModel.requestNfcPermissions()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertFalse(state.hasNfcPermission)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("NFC permissions are required"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `clearEvents should clear NFC events`() = runTest {
        // Add some events first
        val event = NfcEvent.connectionEstablished(Clock.System.now())
        mockRepository.nfcEventsFlow.emit(event)
        advanceUntilIdle()
        
        viewModel.clearEvents()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertTrue(state.nfcEvents.isEmpty())
        assertTrue(mockRepository.clearEventsCalled)
    }
    
    @Test
    fun `clearError should clear error message`() = runTest {
        // Set an error first
        viewModel.updatePassportData(PassportData()) // Invalid data
        advanceUntilIdle()
        
        viewModel.clearError()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNull(state.errorMessage)
    }
    
    @Test
    fun `simulation status changes should update UI state`() = runTest {
        mockRepository.simulationStatusFlow.value = SimulationStatus.STARTING
        advanceUntilIdle()
        
        var state = viewModel.uiState.first()
        assertEquals(SimulationStatus.STARTING, state.simulationStatus)
        assertTrue(state.isLoading)
        
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        state = viewModel.uiState.first()
        assertEquals(SimulationStatus.ACTIVE, state.simulationStatus)
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `NFC events should be added to UI state`() = runTest {
        val event1 = NfcEvent.connectionEstablished(Clock.System.now())
        val event2 = NfcEvent.bacAuthenticationRequest(Clock.System.now())
        
        mockRepository.nfcEventsFlow.emit(event1)
        advanceUntilIdle()
        
        var state = viewModel.uiState.first()
        assertEquals(1, state.nfcEvents.size)
        assertEquals(event1, state.nfcEvents[0])
        
        mockRepository.nfcEventsFlow.emit(event2)
        advanceUntilIdle()
        
        state = viewModel.uiState.first()
        assertEquals(2, state.nfcEvents.size)
        assertEquals(event2, state.nfcEvents[1])
    }
    
    @Test
    fun `error events should set error message`() = runTest {
        val errorEvent = NfcEvent.error(Clock.System.now(), "Test error")
        
        mockRepository.nfcEventsFlow.emit(errorEvent)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Test error"))
    }
    
    @Test
    fun `authentication failure events should set error message`() = runTest {
        val failureEvent = NfcEvent.authenticationFailure(Clock.System.now(), "BAC", "Invalid MRZ")
        
        mockRepository.nfcEventsFlow.emit(failureEvent)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("BAC authentication failed"))
    }
}

/**
 * Mock implementation of NfcSimulatorRepository for testing.
 */
private class MockNfcSimulatorRepository : NfcSimulatorRepository {
    
    val simulationStatusFlow = MutableStateFlow(SimulationStatus.STOPPED)
    val nfcEventsFlow = MutableSharedFlow<NfcEvent>()
    
    private var nfcAvailable = false
    private var hasPermissions = false
    private var startSimulationResult: Result<Unit> = Result.success(Unit)
    private var stopSimulationResult: Result<Unit> = Result.success(Unit)
    private var requestPermissionsResult: Result<Boolean> = Result.success(true)
    
    var startSimulationCalled = false
    var stopSimulationCalled = false
    var clearEventsCalled = false
    var lastPassportData: PassportData? = null
    
    fun setNfcAvailable(available: Boolean) {
        nfcAvailable = available
    }
    
    fun setHasPermissions(permissions: Boolean) {
        hasPermissions = permissions
    }
    
    fun setStartSimulationResult(result: Result<Unit>) {
        startSimulationResult = result
    }
    
    fun setStopSimulationResult(result: Result<Unit>) {
        stopSimulationResult = result
    }
    
    fun setRequestPermissionsResult(result: Result<Boolean>) {
        requestPermissionsResult = result
    }
    
    override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
        startSimulationCalled = true
        lastPassportData = passportData
        return startSimulationResult
    }
    
    override suspend fun stopSimulation(): Result<Unit> {
        stopSimulationCalled = true
        return stopSimulationResult
    }
    
    override fun getSimulationStatus() = simulationStatusFlow
    
    override fun getNfcEvents() = nfcEventsFlow
    
    override suspend fun isNfcAvailable(): Result<Boolean> {
        return Result.success(nfcAvailable)
    }
    
    override suspend fun hasNfcPermissions(): Result<Boolean> {
        return Result.success(hasPermissions)
    }
    
    override suspend fun requestNfcPermissions(): Result<Boolean> {
        return requestPermissionsResult
    }
    
    override suspend fun clearEvents() {
        clearEventsCalled = true
    }
}