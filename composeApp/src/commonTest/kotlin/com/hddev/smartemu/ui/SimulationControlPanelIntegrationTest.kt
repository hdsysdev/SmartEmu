package com.hddev.smartemu.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hddev.smartemu.data.*
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.ui.components.SimulationControlPanel
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Integration tests for SimulationControlPanel with ViewModel.
 * Tests the complete interaction flow between UI and business logic.
 */
class SimulationControlPanelIntegrationTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    private class MockNfcSimulatorRepository : NfcSimulatorRepository {
        private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
        private val _nfcEvents = MutableStateFlow<NfcEvent?>(null)
        
        var isNfcAvailableResult = Result.success(true)
        var hasNfcPermissionsResult = Result.success(true)
        var requestNfcPermissionsResult = Result.success(true)
        var startSimulationResult = Result.success(Unit)
        var stopSimulationResult = Result.success(Unit)
        
        var startSimulationCalled = false
        var stopSimulationCalled = false
        var requestPermissionsCalled = false
        var clearEventsCalled = false
        
        override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
            startSimulationCalled = true
            if (startSimulationResult.isSuccess) {
                _simulationStatus.value = SimulationStatus.STARTING
                // Simulate transition to active
                _simulationStatus.value = SimulationStatus.ACTIVE
            }
            return startSimulationResult
        }
        
        override suspend fun stopSimulation(): Result<Unit> {
            stopSimulationCalled = true
            if (stopSimulationResult.isSuccess) {
                _simulationStatus.value = SimulationStatus.STOPPING
                // Simulate transition to stopped
                _simulationStatus.value = SimulationStatus.STOPPED
            }
            return stopSimulationResult
        }
        
        override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
        
        override fun getNfcEvents(): Flow<NfcEvent> = 
            _nfcEvents.filterNotNull()
        
        override suspend fun isNfcAvailable(): Result<Boolean> = isNfcAvailableResult
        
        override suspend fun hasNfcPermissions(): Result<Boolean> = hasNfcPermissionsResult
        
        override suspend fun requestNfcPermissions(): Result<Boolean> {
            requestPermissionsCalled = true
            return requestNfcPermissionsResult
        }
        
        override suspend fun clearEvents() {
            clearEventsCalled = true
        }
        
        fun setSimulationStatus(status: SimulationStatus) {
            _simulationStatus.value = status
        }
        
        fun emitNfcEvent(event: NfcEvent) {
            _nfcEvents.value = event
        }
    }
    
    @Test
    fun simulationControlPanel_integrationWithViewModel_startsSimulation() {
        val mockRepository = MockNfcSimulatorRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Set up valid passport data
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = kotlinx.datetime.LocalDate(1990, 1, 1),
            expiryDate = kotlinx.datetime.LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "USA",
            nationality = "USA"
        )
        viewModel.updatePassportData(validPassportData)
        
        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            
            SimulationControlPanel(
                simulationStatus = uiState.value.simulationStatus,
                nfcAvailable = uiState.value.nfcAvailable,
                hasNfcPermission = uiState.value.hasNfcPermission,
                canStartSimulation = uiState.value.canStartSimulation(),
                canStopSimulation = uiState.value.canStopSimulation(),
                isLoading = uiState.value.isLoading,
                errorMessage = uiState.value.errorMessage,
                onStartSimulation = viewModel::startSimulation,
                onStopSimulation = viewModel::stopSimulation,
                onRequestPermissions = viewModel::requestNfcPermissions,
                onClearError = viewModel::clearError
            )
        }
        
        // Click start button
        composeTestRule
            .onNodeWithText("Start")
            .performClick()
        
        // Verify repository method was called
        assertEquals(true, mockRepository.startSimulationCalled)
    }
    
    @Test
    fun simulationControlPanel_integrationWithViewModel_stopsSimulation() {
        val mockRepository = MockNfcSimulatorRepository()
        mockRepository.setSimulationStatus(SimulationStatus.ACTIVE)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            
            SimulationControlPanel(
                simulationStatus = uiState.value.simulationStatus,
                nfcAvailable = uiState.value.nfcAvailable,
                hasNfcPermission = uiState.value.hasNfcPermission,
                canStartSimulation = uiState.value.canStartSimulation(),
                canStopSimulation = uiState.value.canStopSimulation(),
                isLoading = uiState.value.isLoading,
                errorMessage = uiState.value.errorMessage,
                onStartSimulation = viewModel::startSimulation,
                onStopSimulation = viewModel::stopSimulation,
                onRequestPermissions = viewModel::requestNfcPermissions,
                onClearError = viewModel::clearError
            )
        }
        
        // Wait for UI to update with active status
        composeTestRule.waitForIdle()
        
        // Click stop button
        composeTestRule
            .onNodeWithText("Stop")
            .performClick()
        
        // Verify repository method was called
        assertEquals(true, mockRepository.stopSimulationCalled)
    }
    
    @Test
    fun simulationControlPanel_integrationWithViewModel_requestsPermissions() {
        val mockRepository = MockNfcSimulatorRepository()
        mockRepository.hasNfcPermissionsResult = Result.success(false)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            
            SimulationControlPanel(
                simulationStatus = uiState.value.simulationStatus,
                nfcAvailable = uiState.value.nfcAvailable,
                hasNfcPermission = uiState.value.hasNfcPermission,
                canStartSimulation = uiState.value.canStartSimulation(),
                canStopSimulation = uiState.value.canStopSimulation(),
                isLoading = uiState.value.isLoading,
                errorMessage = uiState.value.errorMessage,
                onStartSimulation = viewModel::startSimulation,
                onStopSimulation = viewModel::stopSimulation,
                onRequestPermissions = viewModel::requestNfcPermissions,
                onClearError = viewModel::clearError
            )
        }
        
        // Wait for UI to update
        composeTestRule.waitForIdle()
        
        // Verify permission request section is shown
        composeTestRule
            .onNodeWithText("Grant Permissions")
            .assertIsDisplayed()
            .performClick()
        
        // Verify repository method was called
        assertEquals(true, mockRepository.requestPermissionsCalled)
    }
    
    @Test
    fun simulationControlPanel_integrationWithViewModel_displaysNfcNotAvailable() {
        val mockRepository = MockNfcSimulatorRepository()
        mockRepository.isNfcAvailableResult = Result.success(false)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            
            SimulationControlPanel(
                simulationStatus = uiState.value.simulationStatus,
                nfcAvailable = uiState.value.nfcAvailable,
                hasNfcPermission = uiState.value.hasNfcPermission,
                canStartSimulation = uiState.value.canStartSimulation(),
                canStopSimulation = uiState.value.canStopSimulation(),
                isLoading = uiState.value.isLoading,
                errorMessage = uiState.value.errorMessage,
                onStartSimulation = viewModel::startSimulation,
                onStopSimulation = viewModel::stopSimulation,
                onRequestPermissions = viewModel::requestNfcPermissions,
                onClearError = viewModel::clearError
            )
        }
        
        // Wait for UI to update
        composeTestRule.waitForIdle()
        
        // Verify NFC not available is displayed
        composeTestRule
            .onNodeWithText("Not Available")
            .assertIsDisplayed()
        
        // Verify start button is disabled
        composeTestRule
            .onNodeWithText("Start")
            .assertIsNotEnabled()
    }
    
    @Test
    fun simulationControlPanel_integrationWithViewModel_handlesErrors() {
        val mockRepository = MockNfcSimulatorRepository()
        mockRepository.startSimulationResult = Result.failure(Exception("Test error"))
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // Set up valid passport data
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = kotlinx.datetime.LocalDate(1990, 1, 1),
            expiryDate = kotlinx.datetime.LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "USA",
            nationality = "USA"
        )
        viewModel.updatePassportData(validPassportData)
        
        composeTestRule.setContent {
            val uiState = viewModel.uiState.collectAsStateWithLifecycle()
            
            SimulationControlPanel(
                simulationStatus = uiState.value.simulationStatus,
                nfcAvailable = uiState.value.nfcAvailable,
                hasNfcPermission = uiState.value.hasNfcPermission,
                canStartSimulation = uiState.value.canStartSimulation(),
                canStopSimulation = uiState.value.canStopSimulation(),
                isLoading = uiState.value.isLoading,
                errorMessage = uiState.value.errorMessage,
                onStartSimulation = viewModel::startSimulation,
                onStopSimulation = viewModel::stopSimulation,
                onRequestPermissions = viewModel::requestNfcPermissions,
                onClearError = viewModel::clearError
            )
        }
        
        // Click start button
        composeTestRule
            .onNodeWithText("Start")
            .performClick()
        
        // Wait for error to appear
        composeTestRule.waitForIdle()
        
        // Verify error is displayed
        composeTestRule
            .onNodeWithText("Error")
            .assertIsDisplayed()
        
        // Verify error can be cleared
        composeTestRule
            .onNodeWithContentDescription("Clear error")
            .performClick()
    }
}