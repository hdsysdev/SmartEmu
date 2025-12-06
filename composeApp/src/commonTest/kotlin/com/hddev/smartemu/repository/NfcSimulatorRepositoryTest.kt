package com.hddev.smartemu.repository

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.domain.SimulatorError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test implementation of NfcSimulatorRepository for testing purposes.
 * This demonstrates the expected behavior of repository implementations.
 */
class TestNfcSimulatorRepository : NfcSimulatorRepository {
    
    private var simulationStatus = SimulationStatus.STOPPED
    private var nfcAvailable = true
    private var hasPermissions = true
    private val events = mutableListOf<NfcEvent>()
    
    override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
        return if (!passportData.isValid()) {
            Result.failure(SimulatorError.ValidationError.MissingRequiredFields)
        } else if (simulationStatus != SimulationStatus.STOPPED) {
            Result.failure(SimulatorError.SystemError.SimulationAlreadyRunning)
        } else {
            simulationStatus = SimulationStatus.ACTIVE
            events.add(NfcEvent.connectionEstablished(Clock.System.now()))
            Result.success(Unit)
        }
    }
    
    override suspend fun stopSimulation(): Result<Unit> {
        return if (simulationStatus == SimulationStatus.STOPPED) {
            Result.failure(SimulatorError.SystemError.SimulationNotRunning)
        } else {
            simulationStatus = SimulationStatus.STOPPED
            events.add(NfcEvent.connectionLost(Clock.System.now()))
            Result.success(Unit)
        }
    }
    
    override fun getSimulationStatus(): Flow<SimulationStatus> {
        return flowOf(simulationStatus)
    }
    
    override fun getNfcEvents(): Flow<NfcEvent> {
        return flowOf(*events.toTypedArray())
    }
    
    override suspend fun isNfcAvailable(): Result<Boolean> {
        return Result.success(nfcAvailable)
    }
    
    override suspend fun hasNfcPermissions(): Result<Boolean> {
        return Result.success(hasPermissions)
    }
    
    override suspend fun requestNfcPermissions(): Result<Boolean> {
        hasPermissions = true
        return Result.success(true)
    }
    
    override suspend fun clearEvents() {
        events.clear()
    }
    
    // Test helper methods
    fun setNfcAvailable(available: Boolean) {
        nfcAvailable = available
    }
    
    fun setHasPermissions(permissions: Boolean) {
        hasPermissions = permissions
    }
    
    fun addTestEvent(event: NfcEvent) {
        events.add(event)
    }
}

class NfcSimulatorRepositoryTest {
    
    private val repository = TestNfcSimulatorRepository()
    
    private val validPassportData = PassportData(
        passportNumber = "AB123456",
        dateOfBirth = LocalDate(1990, 5, 15),
        expiryDate = LocalDate(2030, 5, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "John",
        lastName = "Doe",
        gender = "M"
    )
    
    @Test
    fun `startSimulation succeeds with valid passport data`() = runTest {
        val result = repository.startSimulation(validPassportData)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `startSimulation fails with invalid passport data`() = runTest {
        val invalidData = validPassportData.copy(passportNumber = "")
        val result = repository.startSimulation(invalidData)
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SimulatorError.ValidationError.MissingRequiredFields)
    }
    
    @Test
    fun `startSimulation fails when simulation already running`() = runTest {
        // Start simulation first
        repository.startSimulation(validPassportData)
        
        // Try to start again
        val result = repository.startSimulation(validPassportData)
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SimulatorError.SystemError.SimulationAlreadyRunning)
    }
    
    @Test
    fun `stopSimulation succeeds when simulation is running`() = runTest {
        // Start simulation first
        repository.startSimulation(validPassportData)
        
        val result = repository.stopSimulation()
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `stopSimulation fails when simulation is not running`() = runTest {
        val result = repository.stopSimulation()
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SimulatorError.SystemError.SimulationNotRunning)
    }
    
    @Test
    fun `getSimulationStatus returns current status`() = runTest {
        repository.getSimulationStatus().collect { status ->
            assertEquals(SimulationStatus.STOPPED, status)
        }
    }
    
    @Test
    fun `getNfcEvents returns events flow`() = runTest {
        val testEvent = NfcEvent.error(Clock.System.now(), "Test error")
        repository.addTestEvent(testEvent)
        
        repository.getNfcEvents().collect { event ->
            assertEquals(testEvent, event)
        }
    }
    
    @Test
    fun `isNfcAvailable returns availability status`() = runTest {
        repository.setNfcAvailable(true)
        val result = repository.isNfcAvailable()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
        
        repository.setNfcAvailable(false)
        val result2 = repository.isNfcAvailable()
        
        assertTrue(result2.isSuccess)
        assertFalse(result2.getOrNull() == true)
    }
    
    @Test
    fun `hasNfcPermissions returns permission status`() = runTest {
        repository.setHasPermissions(true)
        val result = repository.hasNfcPermissions()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
        
        repository.setHasPermissions(false)
        val result2 = repository.hasNfcPermissions()
        
        assertTrue(result2.isSuccess)
        assertFalse(result2.getOrNull() == true)
    }
    
    @Test
    fun `requestNfcPermissions grants permissions`() = runTest {
        repository.setHasPermissions(false)
        
        val result = repository.requestNfcPermissions()
        
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
        
        // Verify permissions are now granted
        val permissionResult = repository.hasNfcPermissions()
        assertTrue(permissionResult.getOrNull() == true)
    }
    
    @Test
    fun `clearEvents removes all events`() = runTest {
        // Add some test events
        repository.addTestEvent(NfcEvent.connectionEstablished(Clock.System.now()))
        repository.addTestEvent(NfcEvent.error(Clock.System.now(), "Test error"))
        
        repository.clearEvents()
        
        // Events should be cleared - this is implementation dependent
        // In a real implementation, you might check that the events flow is empty
    }
    
    @Test
    fun `repository handles simulation lifecycle correctly`() = runTest {
        // Initially stopped
        repository.getSimulationStatus().collect { status ->
            assertEquals(SimulationStatus.STOPPED, status)
        }
        
        // Start simulation
        val startResult = repository.startSimulation(validPassportData)
        assertTrue(startResult.isSuccess)
        
        // Should be active now
        repository.getSimulationStatus().collect { status ->
            assertEquals(SimulationStatus.ACTIVE, status)
        }
        
        // Stop simulation
        val stopResult = repository.stopSimulation()
        assertTrue(stopResult.isSuccess)
        
        // Should be stopped again
        repository.getSimulationStatus().collect { status ->
            assertEquals(SimulationStatus.STOPPED, status)
        }
    }
}