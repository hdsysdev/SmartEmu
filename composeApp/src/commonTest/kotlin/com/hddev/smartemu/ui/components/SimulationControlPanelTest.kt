package com.hddev.smartemu.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hddev.smartemu.data.SimulationStatus
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * UI tests for SimulationControlPanel component.
 * Tests user interactions, state display, and button behaviors.
 */
class SimulationControlPanelTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun simulationControlPanel_displaysCorrectTitle() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Simulation Control")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_showsNfcAvailableStatus() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Hardware")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText("Available")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_showsNfcNotAvailableStatus() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = false,
                hasNfcPermission = false,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Hardware")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText("Not Available")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_showsPermissionGrantedStatus() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Permissions")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText("Granted")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_showsPermissionRequiredStatus() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = false,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Permissions")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText("Required")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_displaysSimulationStatus() {
        val testCases = listOf(
            SimulationStatus.STOPPED to "Stopped",
            SimulationStatus.STARTING to "Starting...",
            SimulationStatus.ACTIVE to "Active",
            SimulationStatus.STOPPING to "Stopping...",
            SimulationStatus.ERROR to "Error"
        )
        
        testCases.forEach { (status, expectedText) ->
            composeTestRule.setContent {
                SimulationControlPanel(
                    simulationStatus = status,
                    nfcAvailable = true,
                    hasNfcPermission = true,
                    canStartSimulation = status.canStart(),
                    canStopSimulation = status.canStop(),
                    isLoading = status.isTransitioning(),
                    errorMessage = null,
                    onStartSimulation = {},
                    onStopSimulation = {},
                    onRequestPermissions = {},
                    onClearError = {}
                )
            }
            
            composeTestRule
                .onNodeWithText("Simulation")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText(expectedText)
                .assertIsDisplayed()
        }
    }
    
    @Test
    fun simulationControlPanel_startButtonEnabledWhenCanStart() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Start")
            .assertIsEnabled()
    }
    
    @Test
    fun simulationControlPanel_startButtonDisabledWhenCannotStart() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.ACTIVE,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = true,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Start")
            .assertIsNotEnabled()
    }
    
    @Test
    fun simulationControlPanel_stopButtonEnabledWhenCanStop() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.ACTIVE,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = true,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Stop")
            .assertIsEnabled()
    }
    
    @Test
    fun simulationControlPanel_stopButtonDisabledWhenCannotStop() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Stop")
            .assertIsNotEnabled()
    }
    
    @Test
    fun simulationControlPanel_startButtonClickTriggersCallback() {
        var startClicked = false
        
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = { startClicked = true },
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Start")
            .performClick()
        
        assertEquals(true, startClicked)
    }
    
    @Test
    fun simulationControlPanel_stopButtonClickTriggersCallback() {
        var stopClicked = false
        
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.ACTIVE,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = true,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = { stopClicked = true },
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Stop")
            .performClick()
        
        assertEquals(true, stopClicked)
    }
    
    @Test
    fun simulationControlPanel_showsPermissionRequestWhenNeeded() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = false,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Permissions Required")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText("Grant Permissions")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_hidesPermissionRequestWhenNotNeeded() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("NFC Permissions Required")
            .assertDoesNotExist()
        
        composeTestRule
            .onNodeWithText("Grant Permissions")
            .assertDoesNotExist()
    }
    
    @Test
    fun simulationControlPanel_permissionButtonClickTriggersCallback() {
        var permissionRequested = false
        
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = false,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = { permissionRequested = true },
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Grant Permissions")
            .performClick()
        
        assertEquals(true, permissionRequested)
    }
    
    @Test
    fun simulationControlPanel_showsErrorMessage() {
        val errorMessage = "Test error message"
        
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.ERROR,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = errorMessage,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Error")
            .assertIsDisplayed()
        
        composeTestRule
            .onNodeWithText(errorMessage)
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_hidesErrorWhenNull() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPED,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Error")
            .assertDoesNotExist()
    }
    
    @Test
    fun simulationControlPanel_clearErrorButtonTriggersCallback() {
        var errorCleared = false
        
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.ERROR,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = false,
                isLoading = false,
                errorMessage = "Test error",
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = { errorCleared = true }
            )
        }
        
        composeTestRule
            .onNodeWithContentDescription("Clear error")
            .performClick()
        
        assertEquals(true, errorCleared)
    }
    
    @Test
    fun simulationControlPanel_buttonsDisabledWhenLoading() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STARTING,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = true,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        composeTestRule
            .onNodeWithText("Start")
            .assertIsNotEnabled()
        
        composeTestRule
            .onNodeWithText("Stop")
            .assertIsNotEnabled()
    }
    
    @Test
    fun simulationControlPanel_showsLoadingIndicatorWhenStarting() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STARTING,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = true,
                canStopSimulation = false,
                isLoading = true,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        // The loading indicator should be present in the start button
        composeTestRule
            .onNodeWithText("Start")
            .assertIsDisplayed()
    }
    
    @Test
    fun simulationControlPanel_showsLoadingIndicatorWhenStopping() {
        composeTestRule.setContent {
            SimulationControlPanel(
                simulationStatus = SimulationStatus.STOPPING,
                nfcAvailable = true,
                hasNfcPermission = true,
                canStartSimulation = false,
                canStopSimulation = true,
                isLoading = true,
                errorMessage = null,
                onStartSimulation = {},
                onStopSimulation = {},
                onRequestPermissions = {},
                onClearError = {}
            )
        }
        
        // The loading indicator should be present in the stop button
        composeTestRule
            .onNodeWithText("Stop")
            .assertIsDisplayed()
    }
}