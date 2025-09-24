package com.hddev.smartemu.ui.components

import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for PassportInputForm component.
 * Tests form validation logic and data handling.
 */
class PassportInputFormTest {
    
    @Test
    fun passportInputForm_validatesPassportData() {
        // Given
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
        
        // When
        val validationErrors = validPassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.isEmpty(), "Valid passport data should have no validation errors")
        assertTrue(validPassportData.isValid(), "Valid passport data should pass validation")
    }
    
    @Test
    fun passportInputForm_invalidPassportNumber_hasValidationError() {
        // Given
        val invalidPassportData = PassportData(
            passportNumber = "INVALID123", // Too long
            firstName = "John",
            lastName = "Doe"
        )
        
        // When
        val validationErrors = invalidPassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.containsKey("passportNumber"), "Invalid passport number should have validation error")
    }
    
    @Test
    fun passportInputForm_emptyData_hasValidationErrors() {
        // Given
        val emptyPassportData = PassportData.empty()
        
        // When
        val validationErrors = emptyPassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.isNotEmpty(), "Empty passport data should have validation errors")
    }
    
    @Test
    fun passportInputForm_invalidDates_hasValidationErrors() {
        // Given
        val invalidDatePassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(2025, 1, 1), // Future birth date
            expiryDate = LocalDate(1990, 1, 1), // Past expiry date
            gender = "M",
            issuingCountry = "NLD",
            nationality = "NLD"
        )
        
        // When
        val validationErrors = invalidDatePassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.containsKey("dateOfBirth") || validationErrors.containsKey("expiryDate"), 
                  "Invalid dates should have validation errors")
    }
    
    @Test
    fun passportInputForm_validatesRequiredFields() {
        // Given
        val incompletePassportData = PassportData(
            passportNumber = "", // Empty required field
            firstName = "John",
            lastName = "Doe",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1)
        )
        
        // When
        val validationErrors = incompletePassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.containsKey("passportNumber"), "Empty passport number should have validation error")
    }
    
    @Test
    fun passportInputForm_validatesPassportNumberFormat() {
        // Given
        val invalidFormatPassportData = PassportData(
            passportNumber = "123", // Too short
            firstName = "John",
            lastName = "Doe"
        )
        
        // When
        val validationErrors = invalidFormatPassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.containsKey("passportNumber"), "Invalid format passport number should have validation error")
    }
    
    @Test
    fun passportInputForm_validatesNameFields() {
        // Given
        val invalidNamePassportData = PassportData(
            passportNumber = "AB123456",
            firstName = "", // Empty required field
            lastName = "", // Empty required field
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1)
        )
        
        // When
        val validationErrors = invalidNamePassportData.getValidationErrors()
        
        // Then
        assertTrue(validationErrors.containsKey("firstName") || validationErrors.containsKey("lastName"), 
                  "Empty name fields should have validation errors")
    }
}