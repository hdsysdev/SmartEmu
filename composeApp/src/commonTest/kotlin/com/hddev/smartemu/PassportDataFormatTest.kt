package com.hddev.smartemu

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.domain.PassportValidator
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test scenarios for various passport data formats.
 * Tests validation and processing of different passport data combinations.
 */
class PassportDataFormatTest {
    
    private val validator = PassportValidator()
    
    @Test
    fun `test various passport number formats`() {
        val testCases = listOf(
            // Standard formats
            "L898902C3" to true,    // Standard 9-character format
            "AB1234567" to true,    // Letters + numbers
            "123456789" to true,    // All numbers
            "ABCDEFGHI" to true,    // All letters
            
            // Edge cases
            "A12345678" to true,    // 9 characters mixed
            "12345678A" to true,    // Numbers + letter at end
            "A1B2C3D4E" to true,    // Alternating pattern
            
            // Invalid formats
            "" to false,            // Empty
            "12345678" to false,    // Too short (8 chars)
            "1234567890" to false,  // Too long (10 chars)
            "ABC-12345" to false,   // Contains hyphen
            "ABC 12345" to false,   // Contains space
            "ABC@12345" to false,   // Contains special character
            "abc123456" to false,   // Lowercase letters
        )
        
        testCases.forEach { (passportNumber, expectedValid) ->
            val passportData = createTestPassportData(passportNumber = passportNumber)
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Passport number '$passportNumber' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Passport number '$passportNumber' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test various date formats and edge cases`() {
        val currentYear = 2024
        val testCases = listOf(
            // Valid birth dates
            LocalDate(1990, 6, 15) to true,   // Normal date
            LocalDate(1900, 1, 1) to true,    // Very old person
            LocalDate(2023, 12, 31) to true,  // Recent birth
            LocalDate(1950, 2, 29) to true,   // Leap year
            LocalDate(2000, 2, 29) to true,   // Y2K leap year
            
            // Invalid birth dates
            LocalDate(2025, 1, 1) to false,   // Future date
            LocalDate(1850, 1, 1) to false,   // Too old (unrealistic)
        )
        
        testCases.forEach { (birthDate, expectedValid) ->
            val passportData = createTestPassportData(dateOfBirth = birthDate)
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Birth date '$birthDate' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Birth date '$birthDate' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test expiry date validation scenarios`() {
        val today = LocalDate(2024, 6, 15)
        val testCases = listOf(
            // Valid expiry dates
            LocalDate(2025, 1, 1) to true,    // Future date
            LocalDate(2030, 12, 31) to true,  // Far future
            LocalDate(2024, 12, 31) to true,  // Later this year
            
            // Invalid expiry dates
            LocalDate(2024, 1, 1) to false,   // Past date
            LocalDate(2023, 12, 31) to false, // Last year
            LocalDate(2050, 1, 1) to false,   // Too far in future (unrealistic)
        )
        
        testCases.forEach { (expiryDate, expectedValid) ->
            val passportData = createTestPassportData(expiryDate = expiryDate)
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Expiry date '$expiryDate' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Expiry date '$expiryDate' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test various country code formats`() {
        val testCases = listOf(
            // Valid ISO 3166-1 alpha-3 codes
            "USA" to true,
            "GBR" to true,
            "DEU" to true,
            "FRA" to true,
            "JPN" to true,
            "AUS" to true,
            "CAN" to true,
            "NLD" to true,
            "SWE" to true,
            "NOR" to true,
            
            // Invalid codes
            "" to false,        // Empty
            "US" to false,      // Too short (alpha-2)
            "USAA" to false,    // Too long
            "usa" to false,     // Lowercase
            "123" to false,     // Numbers
            "U$A" to false,     // Special characters
        )
        
        testCases.forEach { (countryCode, expectedValid) ->
            val passportData = createTestPassportData(
                issuingCountry = countryCode,
                nationality = countryCode
            )
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Country code '$countryCode' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Country code '$countryCode' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test various name formats`() {
        val testCases = listOf(
            // Valid names
            ("John", "Doe") to true,
            ("ANNA", "ERIKSSON") to true,      // All caps
            ("Jean-Pierre", "Van Der Berg") to true, // Hyphens and spaces
            ("O'Connor", "McDonald") to true,   // Apostrophes
            ("李", "王") to true,                // Non-Latin characters
            ("José", "García") to true,         // Accented characters
            ("A", "B") to true,                 // Single characters
            
            // Edge cases
            ("A".repeat(39), "B".repeat(39)) to true, // Maximum length
            
            // Invalid names
            ("", "Doe") to false,              // Empty first name
            ("John", "") to false,             // Empty last name
            ("", "") to false,                 // Both empty
            ("John123", "Doe") to false,       // Numbers in name
            ("John@", "Doe") to false,         // Special characters
            ("A".repeat(40), "Doe") to false,  // Too long first name
            ("John", "B".repeat(40)) to false, // Too long last name
        )
        
        testCases.forEach { (names, expectedValid) ->
            val (firstName, lastName) = names
            val passportData = createTestPassportData(
                firstName = firstName,
                lastName = lastName
            )
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Names '$firstName' '$lastName' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Names '$firstName' '$lastName' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test gender field validation`() {
        val testCases = listOf(
            "M" to true,    // Male
            "F" to true,    // Female
            "X" to true,    // Unspecified (some countries allow this)
            
            "" to false,    // Empty
            "m" to false,   // Lowercase
            "f" to false,   // Lowercase
            "Male" to false, // Full word
            "1" to false,   // Number
            "?" to false,   // Special character
        )
        
        testCases.forEach { (gender, expectedValid) ->
            val passportData = createTestPassportData(gender = gender)
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Gender '$gender' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Gender '$gender' should be invalid but validation passed"
                )
            }
        }
    }
    
    @Test
    fun `test MRZ generation for various passport formats`() {
        val testCases = listOf(
            // Standard European passport
            createTestPassportData(
                passportNumber = "L898902C3",
                firstName = "ANNA",
                lastName = "ERIKSSON",
                issuingCountry = "NLD",
                nationality = "NLD"
            ),
            
            // US passport format
            createTestPassportData(
                passportNumber = "123456789",
                firstName = "JOHN",
                lastName = "DOE",
                issuingCountry = "USA",
                nationality = "USA"
            ),
            
            // Long names (should be truncated)
            createTestPassportData(
                passportNumber = "AB1234567",
                firstName = "JEAN-PIERRE-ALEXANDRE",
                lastName = "VAN-DER-BERG-SMITH",
                issuingCountry = "FRA",
                nationality = "FRA"
            ),
            
            // Short names
            createTestPassportData(
                passportNumber = "XY9876543",
                firstName = "A",
                lastName = "B",
                issuingCountry = "GBR",
                nationality = "GBR"
            )
        )
        
        testCases.forEach { passportData ->
            val mrzData = passportData.toMrzData()
            
            // Basic MRZ format validation
            assertNotNull(mrzData, "MRZ should not be null")
            assertEquals(88, mrzData.length, "MRZ should be exactly 88 characters")
            
            // Should start with passport type indicator
            assertTrue(mrzData.startsWith("P<"), "MRZ should start with 'P<'")
            
            // Should contain country codes
            assertTrue(
                mrzData.contains(passportData.issuingCountry),
                "MRZ should contain issuing country: ${passportData.issuingCountry}"
            )
            
            // Should contain passport number
            assertTrue(
                mrzData.contains(passportData.passportNumber),
                "MRZ should contain passport number: ${passportData.passportNumber}"
            )
            
            // Verify MRZ structure (two lines of 44 characters each)
            val lines = mrzData.chunked(44)
            assertEquals(2, lines.size, "MRZ should have exactly 2 lines")
            lines.forEach { line ->
                assertEquals(44, line.length, "Each MRZ line should be 44 characters")
            }
        }
    }
    
    @Test
    fun `test passport data with null and optional fields`() {
        // Test with minimal required fields only
        val minimalPassport = PassportData(
            passportNumber = "AB1234567",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2025, 1, 1)
            // Other fields use defaults or are optional
        )
        
        val validationResult = validator.validatePassportData(minimalPassport)
        assertTrue(
            validationResult.isValid,
            "Minimal passport data should be valid: ${validationResult.errors}"
        )
        
        // Verify MRZ can still be generated
        val mrzData = minimalPassport.toMrzData()
        assertNotNull(mrzData, "MRZ should be generated even with minimal data")
        assertEquals(88, mrzData.length, "MRZ should still be correct length")
    }
    
    @Test
    fun `test date boundary conditions`() {
        val testCases = listOf(
            // Leap year dates
            LocalDate(2000, 2, 29) to true,   // Valid leap year
            LocalDate(1900, 2, 29) to false,  // Invalid leap year (1900 not divisible by 400)
            LocalDate(2004, 2, 29) to true,   // Valid leap year
            
            // Month boundaries
            LocalDate(1990, 1, 31) to true,   // January 31st
            LocalDate(1990, 4, 31) to false,  // April 31st (invalid)
            LocalDate(1990, 2, 30) to false,  // February 30th (invalid)
            
            // Year boundaries
            LocalDate(1899, 12, 31) to false, // Too old
            LocalDate(1900, 1, 1) to true,    // Minimum valid year
            LocalDate(2099, 12, 31) to true,  // Maximum reasonable year
            LocalDate(2100, 1, 1) to false,   // Too far in future
        )
        
        testCases.forEach { (date, expectedValid) ->
            val passportData = createTestPassportData(dateOfBirth = date)
            val validationResult = validator.validatePassportData(passportData)
            
            if (expectedValid) {
                assertTrue(
                    validationResult.isValid,
                    "Date '$date' should be valid but validation failed: ${validationResult.errors}"
                )
            } else {
                assertFalse(
                    validationResult.isValid,
                    "Date '$date' should be invalid but validation passed"
                )
            }
        }
    }
    
    private fun createTestPassportData(
        passportNumber: String = "L898902C3",
        dateOfBirth: LocalDate = LocalDate(1974, 8, 12),
        expiryDate: LocalDate = LocalDate(2025, 4, 15),
        issuingCountry: String = "NLD",
        nationality: String = "NLD",
        firstName: String = "ANNA",
        lastName: String = "ERIKSSON",
        gender: String = "F"
    ): PassportData {
        return PassportData(
            passportNumber = passportNumber,
            dateOfBirth = dateOfBirth,
            expiryDate = expiryDate,
            issuingCountry = issuingCountry,
            nationality = nationality,
            firstName = firstName,
            lastName = lastName,
            gender = gender
        )
    }
}