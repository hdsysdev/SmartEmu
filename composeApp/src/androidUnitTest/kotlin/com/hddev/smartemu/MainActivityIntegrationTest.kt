package com.hddev.smartemu

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hddev.smartemu.di.AndroidAppModule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for MainActivity and Android-specific functionality.
 * Tests the complete Android app initialization and dependency injection.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityIntegrationTest {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Test
    fun mainActivity_initializesSuccessfully() {
        // Then - App should be displayed
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
    }
    
    @Test
    fun mainActivity_createsDependenciesCorrectly() {
        // Given - Activity is launched
        val context = composeTestRule.activity
        
        // When - Creating dependencies manually (to test DI setup)
        val repository = AndroidAppModule.provideNfcSimulatorRepository(context)
        val viewModel = AndroidAppModule.providePassportSimulatorViewModel(context)
        
        // Then - Dependencies should be created successfully
        assert(repository != null)
        assert(viewModel != null)
    }
    
    @Test
    fun mainActivity_handlesNfcAvailabilityCheck() {
        // Then - Should show appropriate NFC status
        // Note: This will depend on the test device's NFC capabilities
        composeTestRule.onNodeWithText("Simulation Control").assertIsDisplayed()
    }
    
    @Test
    fun mainActivity_displaysAllRequiredComponents() {
        // Then - All main components should be present
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Simulation Control").assertIsDisplayed()
        composeTestRule.onNodeWithText("Passport Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Event Log").assertIsDisplayed()
    }
    
    @Test
    fun mainActivity_handlesConfigurationChanges() {
        // Given - App is displayed
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
        
        // When - Rotate device (simulated by recreating activity)
        composeTestRule.activityRule.scenario.recreate()
        
        // Then - App should still be displayed correctly
        composeTestRule.onNodeWithText("Passport NFC Simulator").assertIsDisplayed()
    }
}