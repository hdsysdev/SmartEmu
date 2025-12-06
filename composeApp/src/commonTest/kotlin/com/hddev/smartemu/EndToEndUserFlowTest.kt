package com.hddev.smartemu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hddev.smartemu.data.*
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end integration tests for complete user workflows.
 * Tests the entire user journey from app launch to simulation completion.
 * Covers all major user scenarios and edge cases.
 */
class EndToEndUserFlowTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun completeUserFlow_enterPassportDataAndStartSimulation() {
        // Given
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Verify initial state
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Simulation").assertIsNotEnabled()
        
        // Step 2: Enter passport data
        composeTestRule.onNodeWithText("Passport Number").performTextInput("AB1234567")
        composeTestRule.onNodeWithText("First Name").performTextInput("John")
        composeTestRule.onNodeWithText("Last Name").performTextInput("Doe")
        
        // Step 3: Set dates (simplified for test)
        // Note: In a real test, we'd interact with date pickers
        
        // Step 4: Verify form validation
        // Start button should become enabled when all required fields are filled
        
        // Step 5: Start simulation
        composeTestRule.onNodeWithText("Start Simulation").performClick()
        
        // Step 6: Verify simulation is active
        composeTestRule.onNodeWithText("Stop Simulation").assertIsDisplayed()
        
        // Step 7: Stop simulation
        composeTestRule.onNodeWithText("Stop Simulation").performClick()
        
        // Step 8: Verify simulation is stopped
        composeTestRule.onNodeWithText("Start Simulation").assertIsDisplayed()
    }
    
    @Test
    fun userFlow_handleNfcPermissionRequest() {
        // Given - NFC available but no permissions
        val mockRepository = createMockRepository(nfcAvailable = true, hasPermissions = false)
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Verify permission error is shown
        composeTestRule.onNodeWithText("NFC permissions are required for simulation").assertIsDisplayed()
        
        // Step 2: Request permissions
        composeTestRule.onNodeWithText("Request Permissions").performClick()
        
        // Step 3: Verify permission status is updated
        // Note: In a real test, this would depend on the mock repository behavior
    }
    
    @Test
    fun userFlow_viewAndClearEventLog() {
        // Given - Simulation with events
        val mockRepository = createMockRepositoryWithEvents()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Verify events are displayed
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
        composeTestRule.onNodeWithText("NFC connection established").assertIsDisplayed()
        
        // Step 2: Clear events
        composeTestRule.onNodeWithText("Clear").performClick()
        
        // Step 3: Verify events are cleared
        composeTestRule.onNodeWithText("No events yet").assertIsDisplayed()
    }
    
    @Test
    fun userFlow_handleValidationErrors() {
        // Given
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Enter invalid passport number
        composeTestRule.onNodeWithText("Passport Number").performTextInput("INVALID")
        
        // Step 2: Verify validation error is shown
        // Note: This would require the validation to be implemented in the form
        
        // Step 3: Correct the error
        composeTestRule.onNodeWithText("Passport Number").performTextClearance()
        composeTestRule.onNodeWithText("Passport Number").performTextInput("AB1234567")
        
        // Step 4: Verify error is cleared
        // Note: This would require the validation error to be cleared
    }
    
    @Test
    fun userFlow_errorRecovery() {
        // Given - Repository that will fail
        val mockRepository = createFailingMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Try to start simulation (will fail)
        composeTestRule.onNodeWithText("Passport Number").performTextInput("AB1234567")
        composeTestRule.onNodeWithText("First Name").performTextInput("John")
        composeTestRule.onNodeWithText("Last Name").performTextInput("Doe")
        composeTestRule.onNodeWithText("Start Simulation").performClick()
        
        // Step 2: Verify error is displayed
        composeTestRule.onNodeWithText("Failed to start simulation").assertIsDisplayed()
        
        // Step 3: Dismiss error
        composeTestRule.onNodeWithText("Dismiss").performClick()
        
        // Step 4: Verify error is cleared and user can try again
        composeTestRule.onNodeWithText("Start Simulation").assertIsDisplayed()
    }
    
    @Test
    fun userFlow_scrollThroughLongContent() {
        // Given - App with all components
        val mockRepository = createMockRepositoryWithManyEvents()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Step 1: Scroll to bottom to see event log
        composeTestRule.onRoot().performScrollToNode(hasText("Event Log"))
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
        
        // Step 2: Scroll back to top
        composeTestRule.onRoot().performScrollToNode(hasText("Passport NFC Simulator"))
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
    }
    
    @Test
    fun completeWorkflow_multipleSimulationCycles() {
        // Test multiple start/stop cycles to ensure state management works correctly
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        repeat(3) { cycle ->
            // Enter passport data
            composeTestRule.onNodeWithText("Passport Number").performTextClearance()
            composeTestRule.onNodeWithText("Passport Number").performTextInput("AB123456$cycle")
            composeTestRule.onNodeWithText("First Name").performTextClearance()
            composeTestRule.onNodeWithText("First Name").performTextInput("Test$cycle")
            composeTestRule.onNodeWithText("Last Name").performTextClearance()
            composeTestRule.onNodeWithText("Last Name").performTextInput("User$cycle")
            
            // Start simulation
            composeTestRule.onNodeWithText("Start Simulation").performClick()
            composeTestRule.onNodeWithText("Stop Simulation").assertIsDisplayed()
            
            // Stop simulation
            composeTestRule.onNodeWithText("Stop Simulation").performClick()
            composeTestRule.onNodeWithText("Start Simulation").assertIsDisplayed()
        }
    }
    
    @Test
    fun userFlow_rapidFormInteraction() {
        // Test rapid user interactions to ensure UI remains responsive
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Rapidly interact with form fields
        repeat(10) { i ->
            composeTestRule.onNodeWithText("Passport Number").performTextClearance()
            composeTestRule.onNodeWithText("Passport Number").performTextInput("TEST$i")
            composeTestRule.onNodeWithText("First Name").performTextClearance()
            composeTestRule.onNodeWithText("First Name").performTextInput("Name$i")
        }
        
        // Verify final state is correct
        composeTestRule.onNodeWithText("TEST9").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name9").assertIsDisplayed()
    }
    
    @Test
    fun userFlow_simulationWithEventGeneration() {
        // Test complete simulation workflow with event generation
        val mockRepository = createMockRepositoryWithEventGeneration()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Enter valid passport data
        composeTestRule.onNodeWithText("Passport Number").performTextInput("L898902C3")
        composeTestRule.onNodeWithText("First Name").performTextInput("ANNA")
        composeTestRule.onNodeWithText("Last Name").performTextInput("ERIKSSON")
        
        // Start simulation
        composeTestRule.onNodeWithText("Start Simulation").performClick()
        
        // Wait for events to be generated
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            try {
                composeTestRule.onNodeWithText("BAC authentication requested").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        
        // Verify events are displayed
        composeTestRule.onNodeWithText("BAC authentication requested").assertIsDisplayed()
        composeTestRule.onNodeWithText("Authentication successful").assertIsDisplayed()
        
        // Stop simulation
        composeTestRule.onNodeWithText("Stop Simulation").performClick()
        composeTestRule.onNodeWithText("Simulation stopped").assertIsDisplayed()
    }
    
    @Test
    fun userFlow_accessibilityAndUsability() {
        // Test accessibility features and usability
        val mockRepository = createMockRepository()
        val viewModel = PassportSimulatorViewModel(mockRepository)
        
        composeTestRule.setContent {
            App(viewModel = viewModel)
        }
        
        // Verify important elements have content descriptions
        composeTestRule.onNodeWithContentDescription("Passport number input field").assertExists()
        composeTestRule.onNodeWithContentDescription("Start simulation button").assertExists()
        
        // Test keyboard navigation (if supported)
        composeTestRule.onNodeWithText("Passport Number").performClick()
        composeTestRule.onNodeWithText("Passport Number").assertIsFocused()
        
        // Test that error states are accessible
        composeTestRule.onNodeWithText("Start Simulation").performClick()
        // Should show validation error for empty required fields
        composeTestRule.onNodeWithText("Passport number is required").assertIsDisplayed()
    }
    
    private fun createMockRepository(
        nfcAvailable: Boolean = true,
        hasPermissions: Boolean = true
    ): NfcSimulatorRepository {
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
            private val _events = MutableStateFlow<List<NfcEvent>>(emptyList())
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
                _simulationStatus.value = SimulationStatus.ACTIVE
                return Result.success(Unit)
            }
            
            override suspend fun stopSimulation(): Result<Unit> {
                _simulationStatus.value = SimulationStatus.STOPPED
                return Result.success(Unit)
            }
            
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            
            override fun getNfcEvents(): Flow<NfcEvent> = flowOf()
            
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(nfcAvailable)
            
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(hasPermissions)
            
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(true)
            
            override suspend fun clearEvents() {
                _events.value = emptyList()
            }
        }
    }
    
    private fun createMockRepositoryWithEvents(): NfcSimulatorRepository {
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(SimulationStatus.ACTIVE)
            private val _events = MutableStateFlow(listOf(
                NfcEvent(
                    timestamp = Clock.System.now(),
                    type = NfcEventType.CONNECTION_ESTABLISHED,
                    message = "NFC connection established"
                )
            ))
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> = Result.success(Unit)
            override suspend fun stopSimulation(): Result<Unit> = Result.success(Unit)
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            override fun getNfcEvents(): Flow<NfcEvent> = flowOf(_events.value.first())
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun clearEvents() {
                _events.value = emptyList()
            }
        }
    }
    
    private fun createMockRepositoryWithManyEvents(): NfcSimulatorRepository {
        val events = (1..20).map { i ->
            NfcEvent(
                timestamp = Clock.System.now(),
                type = NfcEventType.CONNECTION_ESTABLISHED,
                message = "Event $i"
            )
        }
        
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(SimulationStatus.ACTIVE)
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> = Result.success(Unit)
            override suspend fun stopSimulation(): Result<Unit> = Result.success(Unit)
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            override fun getNfcEvents(): Flow<NfcEvent> = flowOf(*events.toTypedArray())
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun clearEvents() {}
        }
    }
    
    private fun createFailingMockRepository(): NfcSimulatorRepository {
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
                return Result.failure(Exception("Simulation failed"))
            }
            
            override suspend fun stopSimulation(): Result<Unit> = Result.success(Unit)
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            override fun getNfcEvents(): Flow<NfcEvent> = flowOf()
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun clearEvents() {}
        }
    }
    
    private fun createMockRepositoryWithEventGeneration(): NfcSimulatorRepository {
        return object : NfcSimulatorRepository {
            private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
            private val _events = MutableStateFlow<List<NfcEvent>>(emptyList())
            
            override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
                _simulationStatus.value = SimulationStatus.ACTIVE
                
                // Simulate event generation
                val events = listOf(
                    NfcEvent(
                        timestamp = Clock.System.now(),
                        type = NfcEventType.CONNECTION_ESTABLISHED,
                        message = "NFC connection established"
                    ),
                    NfcEvent(
                        timestamp = Clock.System.now(),
                        type = NfcEventType.BAC_AUTHENTICATION_REQUEST,
                        message = "BAC authentication requested"
                    ),
                    NfcEvent(
                        timestamp = Clock.System.now(),
                        type = NfcEventType.AUTHENTICATION_SUCCESS,
                        message = "Authentication successful"
                    )
                )
                _events.value = events
                
                return Result.success(Unit)
            }
            
            override suspend fun stopSimulation(): Result<Unit> {
                _simulationStatus.value = SimulationStatus.STOPPED
                _events.value = _events.value + NfcEvent(
                    timestamp = Clock.System.now(),
                    type = NfcEventType.CONNECTION_LOST,
                    message = "Simulation stopped"
                )
                return Result.success(Unit)
            }
            
            override fun getSimulationStatus(): Flow<SimulationStatus> = _simulationStatus
            override fun getNfcEvents(): Flow<NfcEvent> = _events.asStateFlow().flatMapLatest { events ->
                flowOf(*events.toTypedArray())
            }
            override suspend fun isNfcAvailable(): Result<Boolean> = Result.success(true)
            override suspend fun hasNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun requestNfcPermissions(): Result<Boolean> = Result.success(true)
            override suspend fun clearEvents() {
                _events.value = emptyList()
            }
        }
    }
}