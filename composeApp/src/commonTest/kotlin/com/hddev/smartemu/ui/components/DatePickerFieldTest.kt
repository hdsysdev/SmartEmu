package com.hddev.smartemu.ui.components

import com.hddev.smartemu.utils.DateValidationUtils
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for DatePickerField component.
 * Tests date validation and formatting logic.
 */
class DatePickerFieldTest {
    
    @Test
    fun datePickerField_formatsDateForDisplay() {
        // Given
        val date = LocalDate(2023, 12, 25)
        
        // When
        val formattedDate = DateValidationUtils.formatForDisplay(date)
        
        // Then
        assertEquals("25/12/2023", formattedDate)
    }
    
    @Test
    fun datePickerField_parsesValidDateString() {
        // Given
        val dateString = "25/12/2023"
        
        // When
        val parsedDate = DateValidationUtils.parseDisplayDate(dateString)
        
        // Then
        assertEquals(LocalDate(2023, 12, 25), parsedDate)
    }
    
    @Test
    fun datePickerField_parsesInvalidDateString_returnsNull() {
        // Given
        val invalidDateString = "32/13/2023"
        
        // When
        val parsedDate = DateValidationUtils.parseDisplayDate(invalidDateString)
        
        // Then
        assertNull(parsedDate)
    }
    
    @Test
    fun datePickerField_parsesEmptyString_returnsNull() {
        // Given
        val emptyString = ""
        
        // When
        val parsedDate = DateValidationUtils.parseDisplayDate(emptyString)
        
        // Then
        assertNull(parsedDate)
    }
    
    @Test
    fun datePickerField_parsesIncompleteString_returnsNull() {
        // Given
        val incompleteString = "25/12"
        
        // When
        val parsedDate = DateValidationUtils.parseDisplayDate(incompleteString)
        
        // Then
        assertNull(parsedDate)
    }
    
    @Test
    fun datePickerField_validatesPastDate() {
        // Given
        val pastDate = LocalDate(2020, 1, 1)
        
        // When
        val isPast = DateValidationUtils.isPastDate(pastDate)
        
        // Then
        assertTrue(isPast, "Date in 2020 should be considered past")
    }
    
    @Test
    fun datePickerField_validatesFutureDate() {
        // Given
        val futureDate = LocalDate(2030, 1, 1)
        
        // When
        val isFuture = DateValidationUtils.isFutureDate(futureDate)
        
        // Then
        assertTrue(isFuture, "Date in 2030 should be considered future")
    }
    
    @Test
    fun datePickerField_validatesReasonableBirthDate() {
        // Given
        val reasonableBirthDate = LocalDate(1990, 1, 1)
        
        // When
        val isReasonable = DateValidationUtils.isReasonableBirthDate(reasonableBirthDate)
        
        // Then
        assertTrue(isReasonable, "Birth date in 1990 should be considered reasonable")
    }
    
    @Test
    fun datePickerField_validatesExpiryDate() {
        // Given
        val birthDate = LocalDate(1990, 1, 1)
        val expiryDate = LocalDate(2030, 1, 1)
        
        // When
        val isValidExpiry = DateValidationUtils.isValidExpiryDate(birthDate, expiryDate)
        
        // Then
        assertTrue(isValidExpiry, "Expiry date in 2030 should be valid for birth date in 1990")
    }
}