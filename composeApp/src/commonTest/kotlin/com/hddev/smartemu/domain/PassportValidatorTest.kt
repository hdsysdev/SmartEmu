package com.hddev.smartemu.domain

import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassportValidatorTest {
    
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
    fun `validatePassportData returns valid result for correct data`() {
        val result = PassportValidator.validatePassportData(validPassportData)
        
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `validatePassportNumber accepts valid passport numbers`() {
        val validNumbers = listOf("AB123456", "123456789", "A1B2C3D4", "XYZ123")
        
        validNumbers.forEach { number ->
            val error = PassportValidator.validatePassportNumber(number)
            assertNull(error, "Expected no error for passport number: $number")
        }
    }
    
    @Test
    fun `validatePassportNumber rejects invalid passport numbers`() {
        val invalidNumbers = mapOf(
            "" to "Passport number is required",
            "12345" to "Passport number must be at least 6 characters",
            "1234567890" to "Passport number must be at most 9 characters",
            "AB-123456" to "Passport number must contain only letters and numbers",
            "ab123456" to "Passport number must contain only letters and numbers"
        )
        
        invalidNumbers.forEach { (number, expectedError) ->
            val error = PassportValidator.validatePassportNumber(number)
            assertNotNull(error, "Expected error for passport number: $number")
            assertEquals(expectedError, error)
        }
    }
    
    @Test
    fun `validateDateOfBirth accepts valid birth dates`() {
        val validDates = listOf(
            LocalDate(1990, 5, 15),
            LocalDate(1980, 1, 1),
            LocalDate(2000, 12, 31)
        )
        
        validDates.forEach { date ->
            val error = PassportValidator.validateDateOfBirth(date)
            assertNull(error, "Expected no error for birth date: $date")
        }
    }
    
    @Test
    fun `validateDateOfBirth rejects invalid birth dates`() {
        val futureDate = LocalDate(2030, 1, 1)
        val veryOldDate = LocalDate(1800, 1, 1)
        
        assertNotNull(PassportValidator.validateDateOfBirth(null))
        assertNotNull(PassportValidator.validateDateOfBirth(futureDate))
        assertNotNull(PassportValidator.validateDateOfBirth(veryOldDate))
    }
    
    @Test
    fun `validateExpiryDate accepts valid expiry dates`() {
        val birthDate = LocalDate(1990, 5, 15)
        val validExpiryDates = listOf(
            LocalDate(2030, 5, 15),
            LocalDate(2025, 1, 1),
            LocalDate(2040, 12, 31)
        )
        
        validExpiryDates.forEach { date ->
            val error = PassportValidator.validateExpiryDate(date, birthDate)
            assertNull(error, "Expected no error for expiry date: $date")
        }
    }
    
    @Test
    fun `validateExpiryDate rejects invalid expiry dates`() {
        val birthDate = LocalDate(1990, 5, 15)
        val pastDate = LocalDate(2020, 1, 1)
        
        assertNotNull(PassportValidator.validateExpiryDate(null, birthDate))
        assertNotNull(PassportValidator.validateExpiryDate(pastDate, birthDate))
    }
    
    @Test
    fun `validateCountryCode accepts valid country codes`() {
        val validCodes = listOf("NLD", "USA", "GBR", "DEU", "FRA")
        
        validCodes.forEach { code ->
            val error = PassportValidator.validateCountryCode(code, "test country")
            assertNull(error, "Expected no error for country code: $code")
        }
    }
    
    @Test
    fun `validateCountryCode rejects invalid country codes`() {
        val invalidCodes = mapOf(
            "" to "Test country is required",
            "NL" to "Test country must be 3 characters",
            "NETH" to "Test country must be 3 characters",
            "XXX" to "Invalid test country code. Must be a valid ISO 3166-1 alpha-3 code"
        )
        
        invalidCodes.forEach { (code, expectedError) ->
            val error = PassportValidator.validateCountryCode(code, "test country")
            assertNotNull(error, "Expected error for country code: $code")
            assertEquals(expectedError, error)
        }
    }
    
    @Test
    fun `validateName accepts valid names`() {
        val validNames = listOf("John", "Mary-Jane", "O'Connor", "Van Der Berg")
        
        validNames.forEach { name ->
            val error = PassportValidator.validateName(name, "test name")
            assertNull(error, "Expected no error for name: $name")
        }
    }
    
    @Test
    fun `validateName rejects invalid names`() {
        val invalidNames = mapOf(
            "" to "Test name is required",
            "A".repeat(40) to "Test name must be at most 39 characters",
            "John123" to "Test name can only contain letters, spaces, hyphens, and apostrophes",
            " John" to "Test name cannot start or end with spaces",
            "John " to "Test name cannot start or end with spaces"
        )
        
        invalidNames.forEach { (name, expectedError) ->
            val error = PassportValidator.validateName(name, "test name")
            assertNotNull(error, "Expected error for name: $name")
            assertEquals(expectedError, error)
        }
    }
    
    @Test
    fun `validateGender accepts valid gender codes`() {
        val validGenders = listOf("M", "F", "X")
        
        validGenders.forEach { gender ->
            val error = PassportValidator.validateGender(gender)
            assertNull(error, "Expected no error for gender: $gender")
        }
    }
    
    @Test
    fun `validateGender rejects invalid gender codes`() {
        val invalidGenders = mapOf(
            "" to "Gender is required",
            "Male" to "Gender must be M (Male), F (Female), or X (Unspecified)",
            "A" to "Gender must be M (Male), F (Female), or X (Unspecified)"
        )
        
        invalidGenders.forEach { (gender, expectedError) ->
            val error = PassportValidator.validateGender(gender)
            assertNotNull(error, "Expected error for gender: $gender")
            assertEquals(expectedError, error)
        }
    }
    
    @Test
    fun `validateForMrzGeneration succeeds for valid data`() {
        val result = PassportValidator.validateForMrzGeneration(validPassportData)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `validateForMrzGeneration fails for invalid data`() {
        val invalidData = validPassportData.copy(passportNumber = "")
        val result = PassportValidator.validateForMrzGeneration(invalidData)
        
        assertTrue(result.isFailure)
        assertTrue(result.getSimulatorError() is SimulatorError.ValidationError)
    }
    
    @Test
    fun `validateForMrzGeneration fails for names too long for MRZ`() {
        val longNameData = validPassportData.copy(
            firstName = "A".repeat(30),
            lastName = "B".repeat(30)
        )
        val result = PassportValidator.validateForMrzGeneration(longNameData)
        
        assertTrue(result.isFailure)
        val error = result.getSimulatorError()
        assertTrue(error is SimulatorError.ValidationError.CustomValidation)
        assertEquals("MRZ", error.field)
    }
    
    @Test
    fun `isValidForSimulation returns correct boolean`() {
        assertTrue(PassportValidator.isValidForSimulation(validPassportData))
        
        val invalidData = validPassportData.copy(passportNumber = "")
        assertFalse(PassportValidator.isValidForSimulation(invalidData))
    }
    
    @Test
    fun `getValidationErrors returns appropriate error types`() {
        val invalidData = validPassportData.copy(
            passportNumber = "",
            dateOfBirth = null,
            expiryDate = null
        )
        
        val errors = PassportValidator.getValidationErrors(invalidData)
        
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it is SimulatorError.ValidationError.InvalidPassportNumber })
        assertTrue(errors.any { it is SimulatorError.ValidationError.InvalidDateOfBirth })
        assertTrue(errors.any { it is SimulatorError.ValidationError.InvalidExpiryDate })
    }
}