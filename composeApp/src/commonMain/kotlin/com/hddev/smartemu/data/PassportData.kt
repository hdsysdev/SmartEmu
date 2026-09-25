package com.hddev.smartemu.data

import com.hddev.smartemu.domain.PassportValidator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * Data class representing passport information for NFC simulation, and the access control the chip enforces.
 * Contains validation methods and MRZ generation functionality.
 *
 * [can] is the Card Access Number printed on the data page, an alternative PACE password to the MRZ.
 * Blank means the document has no CAN.
 *
 * [portrait] is the holder's photo; without one the data page and EF.DG2 carry a placeholder.
 */
data class PassportData(
    val passportNumber: String = "",
    val dateOfBirth: LocalDate? = null,
    val expiryDate: LocalDate? = null,
    val issuingCountry: String = "NLD",
    val nationality: String = "NLD",
    val firstName: String = "",
    val lastName: String = "",
    val gender: String = "M",
    val accessControl: AccessControl = AccessControl.BAC_AND_PACE,
    val paceMapping: PaceMapping = PaceMapping.GENERIC,
    val can: String = "",
    val portrait: Portrait? = null
) {

    companion object {
        fun empty(): PassportData = PassportData()

        const val CAN_LENGTH = 6
        const val MRZ_LINE_LENGTH = 44

        private const val MRZ_NAME_LENGTH = 39
        
        // ICAO passport number validation regex
        private val PASSPORT_NUMBER_REGEX = Regex("^[A-Z0-9]{6,9}$")
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
     * Returns the MRZ string in TD3 format (passport format): two lines of 44 characters, concatenated.
     */
    fun toMrzData(): String {
        if (!isValid()) {
            throw IllegalStateException("Cannot generate MRZ for invalid passport data")
        }
        
        // Line 1: P<COUNTRY + LASTNAME<<FIRSTNAME padded to 39 characters
        val line1 = "P<$issuingCountry${mrzName().padEnd(MRZ_NAME_LENGTH, '<')}"
        
        return line1 + buildMrzLine2()
    }

    /**
     * The two MRZ lines as printed at the bottom of the data page.
     */
    fun toMrzLines(): List<String> = toMrzData().chunked(MRZ_LINE_LENGTH)

    /**
     * Whether the chip accepts the CAN as a PACE password.
     */
    fun hasCan(): Boolean = can.isNotBlank()

    /**
     * Date of birth in the MRZ YYMMDD form, as used for the BAC key seed.
     */
    fun mrzDateOfBirth(): String = dateOfBirth?.let { toMrzDate(it) } ?: "000000"
    
    /**
     * Expiry date in the MRZ YYMMDD form, as used for the BAC key seed.
     */
    fun mrzExpiryDate(): String = expiryDate?.let { toMrzDate(it) } ?: "000000"
    
    /**
     * Surname as it appears in the MRZ name field, truncated with the given names to fit 39 characters.
     */
    fun mrzPrimaryIdentifier(): String = mrzName().substringBefore("<<").trimEnd('<')
    
    /**
     * Given names as they appear in the MRZ name field, truncated with the surname to fit 39 characters.
     */
    fun mrzSecondaryIdentifier(): String = mrzName().substringAfter("<<", "").trimEnd('<')
    
    /**
     * Sex field of the MRZ; unspecified ("X") is encoded as a filler per ICAO 9303.
     */
    fun mrzGender(): Char = when (gender.uppercase()) {
        "M" -> 'M'
        "F" -> 'F'
        else -> '<'
    }
    
    private fun mrzName(): String {
        return "${toMrzNameComponent(lastName)}<<${toMrzNameComponent(firstName)}".take(MRZ_NAME_LENGTH)
    }
    
    private fun buildMrzLine2(): String {
        val documentNumberField = passportNumber.uppercase().padEnd(9, '<')
        val documentNumberCheckDigit = calculateCheckDigit(documentNumberField)
        
        val birthDate = mrzDateOfBirth()
        val birthDateCheckDigit = calculateCheckDigit(birthDate)
        val expiry = mrzExpiryDate()
        val expiryDateCheckDigit = calculateCheckDigit(expiry)
        
        // No optional data: 14 fillers, and a filler check digit as ICAO 9303 permits
        val personalNumber = "".padEnd(14, '<')
        val personalNumberCheckDigit = "<"
        
        // Composite check digit covers document number, dates and personal number (with their check digits),
        // but not nationality or sex
        val compositeData = documentNumberField + documentNumberCheckDigit +
                           birthDate + birthDateCheckDigit +
                           expiry + expiryDateCheckDigit +
                           personalNumber + personalNumberCheckDigit
        val compositeCheckDigit = calculateCheckDigit(compositeData)
        
        return documentNumberField + documentNumberCheckDigit + nationality +
               birthDate + birthDateCheckDigit + mrzGender() +
               expiry + expiryDateCheckDigit +
               personalNumber + personalNumberCheckDigit + compositeCheckDigit
    }
    
    private fun toMrzDate(date: LocalDate): String {
        val year = (date.year % 100).toString().padStart(2, '0')
        val month = date.month.number.toString().padStart(2, '0')
        val day = date.day.toString().padStart(2, '0')
        return year + month + day
    }
    
    /**
     * Upper case, apostrophes dropped, each run of other separators replaced by a single '<'.
     */
    private fun toMrzNameComponent(name: String): String {
        return name.uppercase()
            .replace("'", "")
            .map { if (it in 'A'..'Z') it else '<' }
            .joinToString("")
            .replace(Regex("<+"), "<")
            .trim('<')
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
                char in 'A'..'Z' -> char.code - 'A'.code + 10
                else -> 0
            }
            sum += value * weights[index % 3]
        }
        
        return (sum % 10).toString()
    }

}