package com.hddev.smartemu.data

import com.hddev.smartemu.domain.PassportValidator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeFormat

/**
 * Data class representing passport information for NFC simulation.
 * Contains validation methods and MRZ generation functionality.
 */
data class PassportData(
    val passportNumber: String = "",
    val dateOfBirth: LocalDate? = null,
    val expiryDate: LocalDate? = null,
    val issuingCountry: String = "NLD",
    val nationality: String = "NLD",
    val firstName: String = "",
    val lastName: String = "",
    val gender: String = "M"
) {
    
    companion object {
        fun empty(): PassportData = PassportData()
        
        private val MRZ_DATE_FORMAT = LocalDate.Format {
            year()
            monthNumber()
            dayOfMonth()
        }
        
        // ICAO passport number validation regex
        private val PASSPORT_NUMBER_REGEX = Regex("^[A-Z0-9]{6,9}$")
        
        // Valid country codes (ISO 3166-1 alpha-3)
        private val VALID_COUNTRIES = setOf(
            "NLD", "USA", "GBR", "DEU", "FRA", "ESP", "ITA", "CAN", "AUS", "JPN"
        )
    }
    
    /**
     * Validates all passport data fields according to ICAO standards.
     */
    fun isValid(): Boolean {
        return PassportValidator.validatePassportData(this).isValid
    }
    
    /**
     * Gets all validation errors for the passport data.
     */
    fun getValidationErrors(): Map<String, String> {
        return PassportValidator.validatePassportData(this).errors
    }
    
    /**
     * Generates MRZ (Machine Readable Zone) data for BAC/PACE protocols.
     * Returns the MRZ string in TD3 format (passport format).
     */
    fun toMrzData(): String {
        if (!isValid()) {
            throw IllegalStateException("Cannot generate MRZ for invalid passport data")
        }
        
        val mrzDateOfBirth = dateOfBirth?.format(MRZ_DATE_FORMAT) ?: "000000"
        val mrzExpiryDate = expiryDate?.format(MRZ_DATE_FORMAT) ?: "000000"
        
        // Line 1: P<COUNTRY<<LASTNAME<<FIRSTNAME<<<<<<<<<<<<<<<<<<<
        val line1 = buildMrzLine1()
        
        // Line 2: PASSPORTNUMBER<COUNTRY<BIRTHDATE<GENDER<EXPIRYDATE<PERSONALNUM<<CHECKDIGIT
        val line2 = buildMrzLine2(mrzDateOfBirth, mrzExpiryDate)
        
        return line1 + line2
    }
    
    private fun buildMrzLine1(): String {
        val cleanLastName = lastName.uppercase().replace(" ", "").take(39)
        val cleanFirstName = firstName.uppercase().replace(" ", "").take(39)
        
        val nameSection = "$cleanLastName<<$cleanFirstName"
        val paddedNameSection = nameSection.padEnd(39, '<')
        
        return "P<$issuingCountry$paddedNameSection"
    }
    
    private fun buildMrzLine2(mrzDateOfBirth: String, mrzExpiryDate: String): String {
        val paddedPassportNumber = passportNumber.padEnd(9, '<')
        val checkDigit1 = calculateCheckDigit(passportNumber)
        
        val birthDateCheckDigit = calculateCheckDigit(mrzDateOfBirth)
        val expiryDateCheckDigit = calculateCheckDigit(mrzExpiryDate)
        
        val personalNumber = "<<<<<<<<<<<"
        val personalNumberCheckDigit = calculateCheckDigit(personalNumber)
        
        val compositeData = paddedPassportNumber + checkDigit1 + nationality + 
                           mrzDateOfBirth + birthDateCheckDigit + gender + 
                           mrzExpiryDate + expiryDateCheckDigit + personalNumber + personalNumberCheckDigit
        
        val finalCheckDigit = calculateCheckDigit(compositeData)
        
        return compositeData + finalCheckDigit
    }
    
    /**
     * Calculates MRZ check digit according to ICAO standards.
     */
    private fun calculateCheckDigit(data: String): String {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        
        data.forEachIndexed { index, char ->
            val value = when {
                char.isDigit() -> char.digitToInt()
                char.isLetter() -> char.code - 'A'.code + 10
                char == '<' -> 0
                else -> 0
            }
            sum += value * weights[index % 3]
        }
        
        return (sum % 10).toString()
    }
    

}