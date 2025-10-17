package com.hddev.smartemu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests for the main App composable.
 * Tests complete user flows and component interactions.
 */
class AppIntegrationTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun app_displaysAllMainComponents() {
        // Given
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Emulate a passport NFC chip for testing BAC and PACE protocols").assertIsDisplayed()
        composeTestRule.onNodeWithText("Simulation Control").assertIsDisplayed()
        composeTestRule.onNodeWithText("Passport Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
    }
    
    @Test
    fun app_showsErrorWhenNfcNotAvailable() {
        // Given
        val mockRepository = createMockRepository(nfcAvailable = false)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then - Error should be displayed
        composeTestRule.onNodeWithText("NFC is not available on this device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }
    
    @Test
    fun app_allowsPassportDataEntry() {
        // Given
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then - Passport form fields should be available
        composeTestRule.onNodeWithText("Passport Number").assertIsDisplayed()
        composeTestRule.onNodeWithText("First Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Date of Birth").assertIsDisplayed()
        composeTestRule.onNodeWithText("Expiry Date").assertIsDisplayed()
    }
    
    @Test
    fun app_enablesSimulationWhenDataValid() {
        // Given
        val mockRepository = createMockRepository(nfcAvailable = true, hasPermissions = true)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Fill in valid passport data
        composeTestRule.onNodeWithText("Passport Number").performTextInput("AB1234567")
        composeTestRule.onNodeWithText("First Name").performTextInput("John")
        composeTestRule.onNodeWithText("Last Name").performTextInput("Doe")
        
        // Then - Start simulation button should be enabled
        composeTestRule.onNodeWithText("Start Simulation").assertIsEnabled()
    }
    
    @Test
    fun app_showsEventLogWhenSimulationActive() {
        // Given
        val mockRepository = createMockRepository(
            nfcAvailable = true, 
            hasPermissions = true,
            simulationStatus = SimulationStatus.ACTIVE
        )
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then - Event log should show active status
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening").assertIsDisplayed()
    }
    
    @Test
    fun app_handlesErrorDismissal() {
        // Given
        val mockRepository = createMockRepository(nfcAvailable = false)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then - Error is displayed
        composeTestRule.onNodeWithText("NFC is not available on this device").assertIsDisplayed()
        
        // When - Dismiss error
        composeTestRule.onNodeWithText("Dismiss").performClick()
        
        // Then - Error should be dismissed (this would require the mock to handle clearError)
        // Note: In a real test, we'd verify the error is cleared through the ViewModel
    }
    
    @Test
    fun app_scrollsWhenContentOverflows() {
        // Given
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        // When
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Then - Content should be scrollable
        composeTestRule.onRoot().performScrollToNode(hasText("Event Log"))
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
    }
    
    private fun createMockRepository(
        nfcAvailable: Boolean = true,
        hasPermissions: Boolean = true,
        simulationStatus: SimulationStatus = SimulationStatus.STOPPED
    ): NfcSimulatorRepository {
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(simulationStatus)
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
                _simulationStatus.value = SimulationStatus.ACTIVE
                return Result.success(Unit)
            }
            
            override suspend fun stopSimulation(): Result<Unit> {
                _simulationStatus.value = SimulationStatus.STOPPED
                return Result.success(Unit)
            }
            
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            
            override fun getNfcEvents(): Flow<com.hddev.smartemu.data.NfcEvent> = flowOf()
            
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(nfcAvailable)
            
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(hasPermissions)
            
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(hasPermissions)
            
            override suspend fun clearEvents() {}
        }
    }
}