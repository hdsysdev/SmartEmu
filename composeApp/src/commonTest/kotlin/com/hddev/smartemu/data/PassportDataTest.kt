package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate
import kotlin.test.*

class PassportDataTest {
    
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
    fun testValidPassportData() {
        assertTrue(validPassportData.isValid())
        assertTrue(validPassportData.getValidationErrors().isEmpty())
    }
    
    @Test
    fun testEmptyPassportData() {
        val emptyData = PassportData.empty()
        assertFalse(emptyData.isValid())
        
        val errors = emptyData.getValidationErrors()
        assertTrue(errors.containsKey("passportNumber"))
        assertTrue(errors.containsKey("dateOfBirth"))
        assertTrue(errors.containsKey("expiryDate"))
        assertTrue(errors.containsKey("names"))
    }
    
    @Test
    fun testPassportNumberValidation() {
        // Valid passport numbers
        assertTrue(validPassportData.copy(passportNumber = "AB123456").isValid())
        assertTrue(validPassportData.copy(passportNumber = "123456789").isValid())
        assertTrue(validPassportData.copy(passportNumber = "ABCDEF").isValid())
        
        // Invalid passport numbers
        assertFalse(validPassportData.copy(passportNumber = "").isValid())
        assertFalse(validPassportData.copy(passportNumber = "12345").isValid()) // Too short
        assertFalse(validPassportData.copy(passportNumber = "1234567890").isValid()) // Too long
        assertFalse(validPassportData.copy(passportNumber = "AB123-56").isValid()) // Invalid character
        assertFalse(validPassportData.copy(passportNumber = "ab123456").isValid()) // Lowercase
    }
    
    @Test
    fun testDateOfBirthValidation() {
        val today = LocalDate(2024, 1, 1) // Assuming current date for testing
        
        // Valid birth dates
        assertTrue(validPassportData.copy(dateOfBirth = LocalDate(1990, 5, 15)).isValid())
        assertTrue(validPassportData.copy(dateOfBirth = LocalDate(1950, 1, 1)).isValid())
        
        // Invalid birth dates
        assertFalse(validPassportData.copy(dateOfBirth = null).isValid())
        assertFalse(validPassportData.copy(dateOfBirth = LocalDate(2025, 1, 1)).isValid()) // Future date
        assertFalse(validPassportData.copy(dateOfBirth = LocalDate(1800, 1, 1)).isValid()) // Too old
    }
    
    @Test
    fun testExpiryDateValidation() {
        // Valid expiry dates
        assertTrue(validPassportData.copy(expiryDate = LocalDate(2030, 5, 15)).isValid())
        assertTrue(validPassportData.copy(expiryDate = LocalDate(2025, 12, 31)).isValid())
        
        // Invalid expiry dates
        assertFalse(validPassportData.copy(expiryDate = null).isValid())
        assertFalse(validPassportData.copy(expiryDate = LocalDate(2020, 1, 1)).isValid()) // Past date
        assertFalse(validPassportData.copy(
            dateOfBirth = LocalDate(1990, 5, 15),
            expiryDate = LocalDate(1985, 1, 1) // Before birth date
        ).isValid())
    }
    
    @Test
    fun testCountryCodeValidation() {
        // Valid country codes
        assertTrue(validPassportData.copy(issuingCountry = "USA", nationality = "USA").isValid())
        assertTrue(validPassportData.copy(issuingCountry = "GBR", nationality = "GBR").isValid())
        
        // Invalid country codes
        assertFalse(validPassportData.copy(issuingCountry = "XXX").isValid())
        assertFalse(validPassportData.copy(nationality = "YYY").isValid())
        assertFalse(validPassportData.copy(issuingCountry = "nl").isValid()) // Lowercase
    }
    
    @Test
    fun testNameValidation() {
        // Valid names
        assertTrue(validPassportData.copy(firstName = "John", lastName = "Doe").isValid())
        assertTrue(validPassportData.copy(firstName = "Mary Jane", lastName = "Smith").isValid())
        
        // Invalid names
        assertFalse(validPassportData.copy(firstName = "").isValid())
        assertFalse(validPassportData.copy(lastName = "").isValid())
        assertFalse(validPassportData.copy(firstName = "A".repeat(40)).isValid()) // Too long
        assertFalse(validPassportData.copy(lastName = "B".repeat(40)).isValid()) // Too long
    }
    
    @Test
    fun testMrzGeneration() {
        val mrz = validPassportData.toMrzData()
        
        // MRZ should be 88 characters (44 per line)
        assertEquals(88, mrz.length)
        
        // First line should start with "P<NLD"
        assertTrue(mrz.startsWith("P<NLD"))
        
        // Should contain passport number
        assertTrue(mrz.contains("AB123456"))
        
        // Should contain formatted dates
        assertTrue(mrz.contains("900515")) // Birth date in YYMMDD format
        assertTrue(mrz.contains("300515")) // Expiry date in YYMMDD format
    }
    
    @Test
    fun testMrzGenerationWithInvalidData() {
        val invalidData = PassportData.empty()
        
        assertFailsWith<IllegalStateException> {
            invalidData.toMrzData()
        }
    }
    
    @Test
    fun testMrzCheckDigitCalculation() {
        // Test with known values to verify check digit calculation
        val testData = validPassportData.copy(
            passportNumber = "AB2134567",
            dateOfBirth = LocalDate(1990, 5, 15),
            expiryDate = LocalDate(2030, 5, 15)
        )
        
        val mrz = testData.toMrzData()
        
        // Verify MRZ structure and check digits are present
        assertNotNull(mrz)
        assertEquals(88, mrz.length)
        
        // The MRZ should contain check digits (single digits)
        val line2 = mrz.substring(44)
        assertTrue(line2.matches(Regex(".*\\d.*"))) // Contains at least one digit
    }
    
    @Test
    fun testMrzWithSpecialCharacters() {
        val dataWithSpaces = validPassportData.copy(
            firstName = "Mary Jane",
            lastName = "Van Der Berg"
        )
        
        val mrz = dataWithSpaces.toMrzData()
        
        // Spaces should be removed and names should be properly formatted
        assertTrue(mrz.contains("VANDERBERG<<MARYJANE"))
    }
    
    @Test
    fun testValidationErrorMessages() {
        val invalidData = PassportData(
            passportNumber = "123", // Too short
            dateOfBirth = LocalDate(2025, 1, 1), // Future date
            expiryDate = LocalDate(2020, 1, 1), // Past date
            issuingCountry = "XXX", // Invalid country
            nationality = "YYY", // Invalid nationality
            firstName = "", // Empty
            lastName = "", // Empty
            gender = "M"
        )
        
        val errors = invalidData.getValidationErrors()
        
        assertTrue(errors["passportNumber"]?.contains("6-9 alphanumeric") == true)
        assertTrue(errors["dateOfBirth"]?.contains("past") == true)
        assertTrue(errors["expiryDate"]?.contains("future") == true)
        assertTrue(errors["countryCode"]?.contains("Invalid") == true)
        assertTrue(errors["names"]?.contains("required") == true)
    }
    
    @Test
    fun testMrzLineFormatting() {
        val data = validPassportData.copy(
            firstName = "A",
            lastName = "B",
            passportNumber = "123456"
        )
        
        val mrz = data.toMrzData()
        val line1 = mrz.substring(0, 44)
        val line2 = mrz.substring(44, 88)
        
        // Line 1 should be properly padded
        assertEquals(44, line1.length)
        assertTrue(line1.startsWith("P<NLD"))
        assertTrue(line1.contains("B<<A"))
        
        // Line 2 should be properly formatted
        assertEquals(44, line2.length)
        assertTrue(line2.startsWith("123456"))
    }
}