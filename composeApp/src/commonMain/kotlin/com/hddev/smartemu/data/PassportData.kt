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
     * Generates EF.COM file content indicating presence of DG1 and DG2.
     * Tag: 60, LDS Version: 0107, Tag List: 0102 (DG1, DG2).
     */
    fun generateEfCom(): ByteArray {
        // Tag 60 (EF.COM), Length calculated later
        // 5F01 04 31303730 (LDS Version 1.7)
        // 5C 02 6175 (Tag List: 61=DG1, 75=DG2)
        val ldsVersion = byteArrayOf(0x5F.toByte(), 0x01.toByte(), 0x04.toByte(), 0x30.toByte(), 0x31.toByte(), 0x30.toByte(), 0x37.toByte())
        val tagList = byteArrayOf(0x5C.toByte(), 0x02.toByte(), 0x61.toByte(), 0x75.toByte())
        
        val content = ldsVersion + tagList
        // Simple length encoding (assuming small size < 127)
        return byteArrayOf(0x60.toByte(), content.size.toByte()) + content
    }

    /**
     * Generates EF.DG1 containing the MRZ.
     * Tag: 61
     */
    fun generateDg1(): ByteArray {
        val mrz = toMrzData()
        val mrzBytes = mrz.encodeToByteArray()
        
        // MRZ Data Object (Tag 5F1F)
        // Length encoding for MRZ (88 bytes for TD3)
        // 5F1F 58 [MRZ Bytes]
        val mrzTag = byteArrayOf(0x5F.toByte(), 0x1F.toByte())
        val mrzLen = mrzBytes.size.toByte()
        
        val content = mrzTag + mrzLen + mrzBytes
        
        // DG1 Tag (61)
        return byteArrayOf(0x61.toByte(), content.size.toByte()) + content
    }

    /**
     * Generates EF.DG2 containing a dummy face image header.
     * Tag: 75
     */
    fun generateDg2(): ByteArray {
        // Biometric Information Group Template (Tag 7F61)
        // Number of Biometric Templates (Tag 02) = 1
        // Biometric Information Template (Tag 7F60)
        // Biometric Header Template (Tag A1)
        // Biometric Data Block (Tag 5F2E) = Empty/Dummy
        
        // Simplified dummy structure for testing connectivity:
        // 75 [Len]
        //   7F61 [Len]
        //      02 01 01 (Count=1)
        //      7F60 [Len] 
        //         A1 05 (Header) ...
        //         5F2E 04 01020304 (Dummy Image Data)
        
        val dummyImageBlock = byteArrayOf(0x5F.toByte(), 0x2E.toByte(), 0x04.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte())
        val biometicHeader = byteArrayOf(0xA1.toByte(), 0x05.toByte(), 0x81.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte()) // Minimal
        val bitContent = biometicHeader + dummyImageBlock
        val bit = byteArrayOf(0x7F.toByte(), 0x60.toByte(), bitContent.size.toByte()) + bitContent
        
        val count = byteArrayOf(0x02.toByte(), 0x01.toByte(), 0x01.toByte())
        val bigtContent = count + bit
        val bigt = byteArrayOf(0x7F.toByte(), 0x61.toByte(), bigtContent.size.toByte()) + bigtContent
        
        return byteArrayOf(0x75.toByte(), bigt.size.toByte()) + bigt
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