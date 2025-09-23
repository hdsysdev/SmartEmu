package com.hddev.smartemu

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.DateValidationUtils
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Basic compilation and functionality test to verify the core data models work correctly.
 */
class BasicCompilationTest {
    
    @Test
    fun testPassportDataCreation() {
        val passportData = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 5, 15),
            expiryDate = LocalDate(2030, 5, 15),
            firstName = "John",
            lastName = "Doe"
        )
        
        assertNotNull(passportData)
        assertEquals("AB123456", passportData.passportNumber)
        assertEquals("John", passportData.firstName)
        assertEquals("Doe", passportData.lastName)
    }
    
    @Test
    fun testDateValidationUtilsBasicFunctionality() {
        val pastDate = LocalDate(2020, 1, 1)
        val futureDate = LocalDate(2030, 12, 31)
        
        assertTrue(DateValidationUtils.isPastDate(pastDate))
        assertTrue(DateValidationUtils.isFutureDate(futureDate))
        assertFalse(DateValidationUtils.isPastDate(futureDate))
        assertFalse(DateValidationUtils.isFutureDate(pastDate))
    }
    
    @Test
    fun testPassportDataValidation() {
        val validData = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 5, 15),
            expiryDate = LocalDate(2030, 5, 15),
            firstName = "John",
            lastName = "Doe"
        )
        
        assertTrue(validData.isValid())
        
        val invalidData = PassportData.empty()
        assertFalse(invalidData.isValid())
    }
    
    @Test
    fun testMrzGenerationBasics() {
        val validData = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 5, 15),
            expiryDate = LocalDate(2030, 5, 15),
            firstName = "John",
            lastName = "Doe"
        )
        
        val mrz = validData.toMrzData()
        assertNotNull(mrz)
        assertEquals(88, mrz.length) // Standard MRZ length
        assertTrue(mrz.startsWith("P<NLD")) // Should start with document type and country
    }
}