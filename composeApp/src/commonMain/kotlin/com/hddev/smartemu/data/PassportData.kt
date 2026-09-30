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
 *
 * [documentType] sets the MRZ format: TD3 for a passport, TD1 for a card. [activeAuthentication] gives the chip a
 * key pair in DG15 to answer INTERNAL AUTHENTICATE with, and [chipFault] breaks one check on purpose.
 *
 * [chipProfileId] names the [ChipProfile] the chip behaves like, which [chipConfiguration] resolves with the settings
 * above. [personalNumber] and [placeOfBirth] go in DG11 where the profile has it, and the personal number in the MRZ
 * where the issuer puts it there; [issuingAuthority] and [dateOfIssue] go in DG12.
 *
 * [exactCryptography] isn't a detail of the document but how the chip is emulated: with it, the profile's
 * cryptography is used as it is, even where the r2w nfc-library can't read it. It's set when the emulation starts.
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
    val portrait: Portrait? = null,
    val documentType: DocumentType = DocumentType.PASSPORT,
    val activeAuthentication: Boolean = false,
    val chipFault: ChipFault = ChipFault.NONE,
    val chipProfileId: String = ChipProfiles.GENERIC_ID,
    val personalNumber: String = "",
    val placeOfBirth: String = "",
    val issuingAuthority: String = "",
    val dateOfIssue: LocalDate? = null,
    val exactCryptography: Boolean = false
) {

    companion object {
        fun empty(): PassportData = PassportData()

        const val CAN_LENGTH = 6

        /** Length of a passport's (TD3) MRZ lines; see [DocumentType.mrzLineLength] for the others. */
        const val MRZ_LINE_LENGTH = 44

        /** Characters of optional data on a TD3 line 2, and on each of the TD1 lines 1 and 2. */
        private const val TD3_OPTIONAL_LENGTH = 14
        private const val TD1_OPTIONAL_1_LENGTH = 15
        private const val TD1_OPTIONAL_2_LENGTH = 11

        /** The longest personal number the MRZ's optional data has room for, on a passport and on a card. */
        const val MAX_MRZ_PERSONAL_NUMBER_LENGTH_TD3 = TD3_OPTIONAL_LENGTH
        const val MAX_MRZ_PERSONAL_NUMBER_LENGTH_TD1 = TD1_OPTIONAL_1_LENGTH

        /**
         * A state's code as its MRZ has it: ICAO 9303-3 gives Germany "D", padded with fillers, rather than "DEU".
         */
        fun mrzCountryCode(code: String): String = if (code.uppercase() == "DEU") "D<<" else code.uppercase()
    }

    /**
     * The profile the chip behaves like: the one [chipProfileId] names, if it fits the document type, and the
     * generic one otherwise.
     */
    val chipProfile: ChipProfile
        get() = ChipProfiles.byId(chipProfileId).takeIf { it.fits(documentType) } ?: ChipProfiles.default

    /** Whether the personal number goes in the MRZ's optional data, as the profile's issuer puts it. */
    fun hasPersonalNumberInMrz(): Boolean = chipProfile.mrz.personalNumberInMrz && personalNumber.isNotBlank()

    /** The personal number as the MRZ has it: upper case, with fillers for spaces. */
    fun mrzPersonalNumber(): String =
        if (hasPersonalNumberInMrz()) personalNumber.uppercase().replace(' ', '<') else ""

    /** The document code without fillers: the profile's, as the Dutch ID card's "I", or the ICAO one. */
    fun mrzDocumentCodeValue(): String = chipProfile.mrz.documentCode ?: documentType.documentCode
    
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
     * Generates MRZ (Machine Readable Zone) data for BAC/PACE protocols: the lines of [toMrzLines], concatenated.
     */
    fun toMrzData(): String = toMrzLines().joinToString("")

    /**
     * The MRZ lines as printed on the document: two lines of 44 characters on a passport's data page (TD3), or
     * three of 30 on the back of a card (TD1).
     */
    fun toMrzLines(): List<String> {
        if (!isValid()) {
            throw IllegalStateException("Cannot generate MRZ for invalid passport data")
        }
        return if (documentType.isCard) buildTd1Lines() else buildTd3Lines()
    }

    /**
     * The document number, date of birth and expiry date with their check digits, as the MRZ has them: the
     * password BAC and PACE derive their keys from.
     */
    fun mrzKey(): String =
        mrzDocumentNumber().let { it + calculateCheckDigit(it) } +
            mrzDateOfBirth().let { it + calculateCheckDigit(it) } +
            mrzExpiryDate().let { it + calculateCheckDigit(it) }

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
    
    /**
     * The holder's full name for DG11, as ICAO 9303-10 has it: surname, "<<", then the given names, upper case and in
     * national characters, with '<' between words, and not cut short as in the MRZ.
     */
    fun fullNameOfHolder(): String {
        val words: (String) -> String = { it.trim().uppercase().split(Regex("\\s+")).joinToString("<") }
        return "${words(lastName)}<<${words(firstName)}"
    }

    private fun mrzName(): String {
        return "${toMrzNameComponent(lastName)}<<${toMrzNameComponent(firstName)}".take(documentType.mrzNameLength)
    }

    /** The document code padded to its two characters, "P<" or "ID". */
    private fun mrzDocumentCode(): String = mrzDocumentCodeValue().padEnd(2, '<')

    private fun mrzDocumentNumber(): String = passportNumber.uppercase().padEnd(9, '<')

    /**
     * TD3, ICAO 9303-4: line 1 has the document code, issuing state and name; line 2 the document number,
     * nationality, dates and sex, and the personal number as optional data where the issuer puts it there.
     */
    private fun buildTd3Lines(): List<String> {
        val line1 = mrzDocumentCode() + mrzCountryCode(issuingCountry) + mrzName().padEnd(documentType.mrzNameLength, '<')

        val documentNumber = mrzDocumentNumber().let { it + calculateCheckDigit(it) }
        val birthDate = mrzDateOfBirth().let { it + calculateCheckDigit(it) }
        val expiry = mrzExpiryDate().let { it + calculateCheckDigit(it) }
        // No optional data is all fillers, with a filler check digit as ICAO 9303 permits
        val personalNumber = mrzPersonalNumber()
        val optionalData = personalNumber.padEnd(TD3_OPTIONAL_LENGTH, '<').let {
            it + if (personalNumber.isEmpty()) "<" else calculateCheckDigit(it)
        }

        // The composite check digit covers the document number, dates and optional data with their check
        // digits, but not nationality or sex
        val composite = calculateCheckDigit(documentNumber + birthDate + expiry + optionalData)
        val line2 = documentNumber + mrzCountryCode(nationality) + birthDate + mrzGender() + expiry + optionalData + composite
        return listOf(line1, line2)
    }

    /**
     * TD1, ICAO 9303-5: line 1 has the document code, issuing state and document number; line 2 the dates, sex
     * and nationality; line 3 the name. The personal number goes in the first optional data field where the issuer
     * puts it there, as the Netherlands does; the second is unused.
     */
    private fun buildTd1Lines(): List<String> {
        val documentNumber = mrzDocumentNumber().let { it + calculateCheckDigit(it) }
        val optionalData1 = mrzPersonalNumber().padEnd(TD1_OPTIONAL_1_LENGTH, '<')
        val line1 = mrzDocumentCode() + mrzCountryCode(issuingCountry) + documentNumber + optionalData1

        val birthDate = mrzDateOfBirth().let { it + calculateCheckDigit(it) }
        val expiry = mrzExpiryDate().let { it + calculateCheckDigit(it) }
        val optionalData2 = "".padEnd(TD1_OPTIONAL_2_LENGTH, '<')
        // The composite check digit covers line 1 after the issuing state, and line 2 but for sex and nationality
        val composite = calculateCheckDigit(documentNumber + optionalData1 + birthDate + expiry + optionalData2)
        val line2 = birthDate + mrzGender() + expiry + mrzCountryCode(nationality) + optionalData2 + composite

        val line3 = mrzName().padEnd(documentType.mrzLineLength, '<')
        return listOf(line1, line2, line3)
    }
    
    private fun toMrzDate(date: LocalDate): String {
        val year = (date.year % 100).toString().padStart(2, '0')
        val month = date.month.number.toString().padStart(2, '0')
        val day = date.day.toString().padStart(2, '0')
        return year + month + day
    }
    
    /**
     * Upper case, transliterated to A to Z as [MrzTransliteration] does, apostrophes dropped, and each run of
     * other separators replaced by a single '<'.
     */
    private fun toMrzNameComponent(name: String): String {
        return MrzTransliteration.transliterate(name.uppercase())
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