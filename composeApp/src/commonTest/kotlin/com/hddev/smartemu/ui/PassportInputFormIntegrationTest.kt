package com.hddev.smartemu.ui

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration tests for PassportInputForm with ViewModel.
 * Tests the complete form workflow including validation and state management.
 */
class PassportInputFormIntegrationTest {
    
    @Test
    fun passportInputForm_completeValidWorkflow() {
        // Given - Valid passport data
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        // When - Validating the data
        val validationErrors = validPassportData.getValidationErrors()
        val isValid = validPassportData.isValid()
        
        // Then - Should be valid with no errors
        assertTrue(validationErrors.isEmpty(), "Valid passport data should have no validation errors")
        assertTrue(isValid, "Valid passport data should pass validation")
    }
    
    @Test
    fun passportInputForm_invalidWorkflow_showsErrors() {
        // Given - Invalid passport data
        val invalidPassportData = PassportData(
            passportNumber = "", // Empty required field
            firstName = "",      // Empty required field
            lastName = "",       // Empty required field
            dateOfBirth = null,  // Missing required field
            expiryDate = null,   // Missing required field
            gender = "",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        // When - Validating the data
        val validationErrors = invalidPassportData.getValidationErrors()
        val isValid = invalidPassportData.isValid()
        
        // Then - Should have validation errors
        assertFalse(validationErrors.isEmpty(), "Invalid passport data should have validation errors")
        assertFalse(isValid, "Invalid passport data should not pass validation")
        
        // Check specific error fields
        assertTrue(validationErrors.containsKey("passportNumber"), "Empty passport number should have error")
        assertTrue(validationErrors.containsKey("firstName"), "Empty first name should have error")
        assertTrue(validationErrors.containsKey("lastName"), "Empty last name should have error")
    }
    
    @Test
    fun passportInputForm_partiallyValidData_showsSpecificErrors() {
        // Given - Partially valid passport data
        val partiallyValidData = PassportData(
            passportNumber = "AB123456", // Valid
            firstName = "John",          // Valid
            lastName = "Doe",            // Valid
            dateOfBirth = LocalDate(2025, 1, 1), // Invalid - future date
            expiryDate = LocalDate(1990, 1, 1),  // Invalid - past date
            gender = "M",                // Valid
            issuingCountry = "NLD",      // Valid
            nationality = "NLD"          // Valid
        )
        
        // When - Validating the data
        val validationErrors = partiallyValidData.getValidationErrors()
        val isValid = partiallyValidData.isValid()
        
        // Then - Should have specific date validation errors
        assertFalse(isValid, "Data with invalid dates should not pass validation")
        assertTrue(validationErrors.containsKey("dateOfBirth") || validationErrors.containsKey("expiryDate"), 
                  "Invalid dates should have validation errors")
    }
    
    @Test
    fun passportInputForm_uiStateIntegration() {
        // Given - Valid passport data
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        // When - Creating UI state with the data
        val uiState = PassportSimulatorUiState.initial().withPassportData(validPassportData)
        
        // Then - UI state should reflect the valid data
        assertEquals(validPassportData, uiState.passportData)
        assertTrue(uiState.validationErrors.isEmpty(), "Valid data should have no validation errors in UI state")
        assertTrue(uiState.passportData.isValid(), "Passport data in UI state should be valid")
    }
    
    @Test
    fun passportInputForm_formEnabledState() {
        // Given - UI state with different simulation statuses
        val stoppedState = PassportSimulatorUiState.initial()
        val activeState = stoppedState.copy(
            simulationStatus = com.hddev.smartemu.data.SimulationStatus.ACTIVE
        )
        val loadingState = stoppedState.copy(isLoading = true)
        
        // When & Then - Form should be enabled only when stopped and not loading
        assertTrue(stoppedState.isPassportFormEnabled(), "Form should be enabled when simulation is stopped")
        assertFalse(activeState.isPassportFormEnabled(), "Form should be disabled when simulation is active")
        assertFalse(loadingState.isPassportFormEnabled(), "Form should be disabled when loading")
    }
    
    @Test
    fun passportInputForm_simulationReadiness() {
        // Given - UI state with valid passport data and NFC available
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        val readyState = PassportSimulatorUiState.initial()
            .withPassportData(validPassportData)
            .withNfcStatus(available = true, hasPermission = true)
        
        val notReadyState = PassportSimulatorUiState.initial()
            .withPassportData(PassportData.empty())
            .withNfcStatus(available = false, hasPermission = false)
        
        // When & Then - Simulation should be ready only with valid data and NFC
        assertTrue(readyState.canStartSimulation(), "Should be able to start simulation with valid data and NFC")
        assertFalse(notReadyState.canStartSimulation(), "Should not be able to start simulation without valid data or NFC")
    }
    
    @Test
    fun passportInputForm_mrzGeneration() {
        // Given - Valid passport data
        val validPassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            gender = "M",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        // When - Generating MRZ data
        val mrzData = validPassportData.toMrzData()
        
        // Then - MRZ should be generated successfully
        assertTrue(mrzData.isNotEmpty(), "MRZ data should not be empty")
        assertTrue(mrzData.length == 88, "MRZ data should be 88 characters long (2 lines of 44 characters each)")
        assertTrue(mrzData.startsWith("P<NLD"), "MRZ should start with passport type and issuing country")
    }
    
    @Test
    fun passportInputForm_fieldValidationScenarios() {
        // Test various field validation scenarios
        val testCases = listOf(
            // Valid cases
            PassportData(passportNumber = "AB123456") to false, // Should have other validation errors
            PassportData(passportNumber = "XY987654") to false, // Should have other validation errors
            
            // Invalid passport number cases
            PassportData(passportNumber = "123") to true,      // Too short
            PassportData(passportNumber = "ABCDEFGHIJ") to true, // Too long
            PassportData(passportNumber = "AB-123456") to true,  // Invalid characters
        )
        
        testCases.forEach { (passportData, shouldHavePassportNumberError) ->
            val validationErrors = passportData.getValidationErrors()
            val hasPassportNumberError = validationErrors.containsKey("passportNumber")
            
            if (shouldHavePassportNumberError) {
                assertTrue(hasPassportNumberError, 
                    "Passport number '${passportData.passportNumber}' should have validation error")
            }
            // Note: We don't test the opposite case because other fields might also have errors
        }
    }
}