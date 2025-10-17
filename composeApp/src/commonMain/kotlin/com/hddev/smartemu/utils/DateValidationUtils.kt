package com.hddev.smartemu.utils

import kotlinx.datetime.*

/**
 * Utility object for date validation and formatting operations.
 */
object DateValidationUtils {
    
    /**
     * Checks if the given date is in the past.
     */
    fun isPastDate(date: LocalDate): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return date < today
    }
    
    /**
     * Checks if the given date is in the future.
     */
    fun isFutureDate(date: LocalDate): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return date > today
    }
    
    /**
     * Checks if the birth date is within a reasonable range (not too old, not in future).
     */
    fun isReasonableBirthDate(birthDate: LocalDate): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val maxAge = 150
        val minBirthDate = today.minus(maxAge, DateTimeUnit.YEAR)
        
        return birthDate >= minBirthDate && birthDate < today
    }
    
    /**
     * Checks if the expiry date is valid relative to the birth date.
     * Expiry date should be after birth date and within reasonable passport validity period.
     */
    fun isValidExpiryDate(birthDate: LocalDate, expiryDate: LocalDate): Boolean {
        if (expiryDate <= birthDate) return false
        
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val maxValidityYears = 20 // Maximum passport validity period
        val maxExpiryDate = today.plus(maxValidityYears, DateTimeUnit.YEAR)
        
        return expiryDate <= maxExpiryDate
    }
    
    /**
     * Formats a date for display in the UI.
     */
    fun formatForDisplay(date: LocalDate): String {
        return "${date.dayOfMonth.toString().padStart(2, '0')}/${date.monthNumber.toString().padStart(2, '0')}/${date.year}"
    }
    
    /**
     * Formats a date for MRZ generation (YYMMDD format).
     */
    fun formatForMrz(date: LocalDate): String {
        return date.format(LocalDate.Format {
            year()
            monthNumber()
            dayOfMonth()
        })
    }
    
    /**
     * Parses a date string in DD/MM/YYYY format.
     */
    fun parseDisplayDate(dateString: String): LocalDate? {
        return try {
            val parts = dateString.split("/")
            if (parts.size != 3) return null
            
            val day = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val year = parts[2].toIntOrNull() ?: return null
            
            LocalDate(year, month, day)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Gets the age in years from a birth date.
     */
    fun getAge(birthDate: LocalDate): Int {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        var age = today.year - birthDate.year
        
        if (today.monthNumber < birthDate.monthNumber || 
            (today.monthNumber == birthDate.monthNumber && today.dayOfMonth < birthDate.dayOfMonth)) {
            age--
        }
        
        return age
    }
    
    /**
     * Checks if a passport is expired.
     */
    fun isExpired(expiryDate: LocalDate): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return expiryDate < today
    }
    
    /**
     * Checks if a passport will expire within the given number of months.
     */
    fun willExpireSoon(expiryDate: LocalDate, monthsThreshold: Int = 6): Boolean {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val thresholdDate = today.plus(monthsThreshold, DateTimeUnit.MONTH)
        return expiryDate <= thresholdDate
    }
}