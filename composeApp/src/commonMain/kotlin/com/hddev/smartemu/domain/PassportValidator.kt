package com.hddev.smartemu.domain

import com.hddev.smartemu.data.Countries
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.DateValidationUtils

/**
 * Validator for passport data with comprehensive validation rules.
 * Provides detailed validation results and error messages.
 */
object PassportValidator {
    
    // ICAO passport number validation regex
    private val PASSPORT_NUMBER_REGEX = Regex("^[A-Z0-9]{6,9}$")
    
    // Valid gender codes
    private val VALID_GENDERS = setOf("M", "F", "X")
    
    /**
     * Validates complete passport data and returns a comprehensive result.
     */
    fun validatePassportData(passportData: PassportData): ValidationResult {
        val errors = mutableMapOf<String, String>()
        
        // Validate each field
        validatePassportNumber(passportData.passportNumber)?.let { error ->
            errors["passportNumber"] = error
        }
        
        validateDateOfBirth(passportData.dateOfBirth)?.let { error ->
            errors["dateOfBirth"] = error
        }
        
        validateExpiryDate(passportData.expiryDate, passportData.dateOfBirth)?.let { error ->
            errors["expiryDate"] = error
        }
        
        validateCountryCode(passportData.issuingCountry, "issuing country")?.let { error ->
            errors["issuingCountry"] = error
        }
        
        validateCountryCode(passportData.nationality, "nationality")?.let { error ->
            errors["nationality"] = error
        }
        
        validateName(passportData.firstName, "first name")?.let { error ->
            errors["firstName"] = error
        }
        
        validateName(passportData.lastName, "last name")?.let { error ->
            errors["lastName"] = error
        }
        
        validateGender(passportData.gender)?.let { error ->
            errors["gender"] = error
        }
        
        validateCan(passportData.can)?.let { error ->
            errors["can"] = error
        }

        validatePersonalNumber(passportData)?.let { error ->
            errors["personalNumber"] = error
        }

        validateFreeText(passportData.placeOfBirth, "Place of birth")?.let { error ->
            errors["placeOfBirth"] = error
        }

        validateFreeText(passportData.issuingAuthority, "Issuing authority")?.let { error ->
            errors["issuingAuthority"] = error
        }

        validateDateOfIssue(passportData.dateOfIssue, passportData.dateOfBirth, passportData.expiryDate)?.let { error ->
            errors["dateOfIssue"] = error
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
    
    /**
     * Validates passport number format according to ICAO standards.
     */
    fun validatePassportNumber(passportNumber: String): String? {
        return when {
            passportNumber.isBlank() -> "Passport number is required"
            passportNumber.length < 6 -> "Passport number must be at least 6 characters"
            passportNumber.length > 9 -> "Passport number must be at most 9 characters"
            !PASSPORT_NUMBER_REGEX.matches(passportNumber) -> 
                "Passport number must contain only letters and numbers"
            else -> null
        }
    }
    
    /**
     * Validates date of birth.
     */
    fun validateDateOfBirth(dateOfBirth: kotlinx.datetime.LocalDate?): String? {
        return when {
            dateOfBirth == null -> "Date of birth is required"
            !DateValidationUtils.isPastDate(dateOfBirth) -> 
                "Date of birth must be in the past"
            !DateValidationUtils.isReasonableBirthDate(dateOfBirth) ->
                "Date of birth must be within reasonable range (not more than 150 years ago)"
            else -> null
        }
    }
    
    /**
     * Validates expiry date. Dates in the past are accepted so expired passports can be emulated.
     */
    fun validateExpiryDate(
        expiryDate: kotlinx.datetime.LocalDate?, 
        dateOfBirth: kotlinx.datetime.LocalDate?
    ): String? {
        return when {
            expiryDate == null -> "Expiry date is required"
            dateOfBirth != null && !DateValidationUtils.isValidExpiryDate(dateOfBirth, expiryDate) ->
                "Expiry date must be after date of birth and within reasonable validity period"
            else -> null
        }
    }
    
    /**
     * Validates country code.
     */
    fun validateCountryCode(countryCode: String, fieldName: String): String? {
        return when {
            countryCode.isBlank() -> "${fieldName.replaceFirstChar { it.uppercase() }} is required"
            countryCode.length != 3 -> "${fieldName.replaceFirstChar { it.uppercase() }} must be 3 characters"
            countryCode.uppercase() !in Countries.codes -> 
                "Invalid $fieldName code. Must be a valid ICAO country code"
            else -> null
        }
    }
    
    /**
     * Validates name fields.
     */
    fun validateName(name: String, fieldName: String): String? {
        return when {
            name.isBlank() -> "${fieldName.replaceFirstChar { it.uppercase() }} is required"
            name.length > 39 -> "${fieldName.replaceFirstChar { it.uppercase() }} must be at most 39 characters"
            !name.all { it.isLetter() || it.isWhitespace() || it == '-' || it == '\'' } ->
                "${fieldName.replaceFirstChar { it.uppercase() }} can only contain letters, spaces, hyphens, and apostrophes"
            name.trim() != name -> "${fieldName.replaceFirstChar { it.uppercase() }} cannot start or end with spaces"
            else -> null
        }
    }
    
    /**
     * Validates gender code.
     */
    fun validateGender(gender: String): String? {
        return when {
            gender.isBlank() -> "Gender is required"
            !VALID_GENDERS.contains(gender.uppercase()) -> 
                "Gender must be M (Male), F (Female), or X (Unspecified)"
            else -> null
        }
    }
    
    /**
     * Validates the Card Access Number: optional, otherwise exactly six digits as printed on the data page.
     */
    fun validateCan(can: String): String? {
        return when {
            can.isEmpty() -> null
            can.length != PassportData.CAN_LENGTH || !can.all { it in '0'..'9' } ->
                "CAN must be ${PassportData.CAN_LENGTH} digits"
            else -> null
        }
    }
    
    /**
     * Validates the personal number: optional, letters, digits and spaces, and short enough for the MRZ's optional
     * data where the issuer puts it there.
     */
    fun validatePersonalNumber(passportData: PassportData): String? {
        val number = passportData.personalNumber
        val maxLength = when {
            !passportData.chipProfile.mrz.personalNumberInMrz -> MAX_FREE_TEXT_LENGTH
            passportData.documentType.isCard -> PassportData.MAX_MRZ_PERSONAL_NUMBER_LENGTH_TD1
            else -> PassportData.MAX_MRZ_PERSONAL_NUMBER_LENGTH_TD3
        }
        return when {
            number.isEmpty() -> null
            !number.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' } ->
                "Personal number can only contain letters, digits and spaces"
            number.length > maxLength -> "Personal number must be at most $maxLength characters"
            else -> null
        }
    }

    /**
     * Validates an optional free-text detail of DG11 or DG12.
     */
    fun validateFreeText(text: String, fieldName: String): String? = when {
        text.length > MAX_FREE_TEXT_LENGTH -> "$fieldName must be at most $MAX_FREE_TEXT_LENGTH characters"
        text.trim() != text -> "$fieldName cannot start or end with spaces"
        else -> null
    }

    /**
     * Validates the optional date of issue: on or after the date of birth, and before the expiry date.
     */
    fun validateDateOfIssue(
        dateOfIssue: kotlinx.datetime.LocalDate?,
        dateOfBirth: kotlinx.datetime.LocalDate?,
        expiryDate: kotlinx.datetime.LocalDate?
    ): String? = when {
        dateOfIssue == null -> null
        dateOfBirth != null && dateOfIssue < dateOfBirth -> "Date of issue can't be before the date of birth"
        expiryDate != null && dateOfIssue >= expiryDate -> "Date of issue must be before the expiry date"
        else -> null
    }

    private const val MAX_FREE_TEXT_LENGTH = 40

    /**
     * Validates that passport data can be used for MRZ generation.
     */
    fun validateForMrzGeneration(passportData: PassportData): SimulatorResult<Unit> {
        val validationResult = validatePassportData(passportData)
        
        return if (validationResult.isValid) {
            // Additional checks specific to MRZ generation
            val mrzErrors = mutableListOf<String>()
            
            // Check if names can be properly encoded in MRZ
            val totalNameLength = passportData.firstName.length + passportData.lastName.length + 2 // +2 for separators
            if (totalNameLength > 39) {
                mrzErrors.add("Combined first and last name too long for MRZ format")
            }
            
            if (mrzErrors.isEmpty()) {
                SimulatorResultExtensions.success(Unit)
            } else {
                SimulatorResultExtensions.failure(
                    SimulatorError.ValidationError.CustomValidation(
                        "MRZ", 
                        mrzErrors.joinToString("; ")
                    )
                )
            }
        } else {
            SimulatorResultExtensions.failure(
                SimulatorError.ValidationError.MissingRequiredFields
            )
        }
    }
    
    /**
     * Quick validation check for UI feedback.
     */
    fun isValidForSimulation(passportData: PassportData): Boolean {
        return validatePassportData(passportData).isValid
    }
    
    /**
     * Gets validation errors as SimulatorError objects.
     */
    fun getValidationErrors(passportData: PassportData): List<SimulatorError.ValidationError> {
        val validationResult = validatePassportData(passportData)
        
        return if (!validationResult.isValid) {
            validationResult.errors.map { (field, message) ->
                when (field) {
                    "passportNumber" -> SimulatorError.ValidationError.InvalidPassportNumber
                    "dateOfBirth" -> SimulatorError.ValidationError.InvalidDateOfBirth
                    "expiryDate" -> SimulatorError.ValidationError.InvalidExpiryDate
                    "issuingCountry", "nationality" -> SimulatorError.ValidationError.InvalidCountryCode
                    "firstName", "lastName" -> SimulatorError.ValidationError.InvalidNames
                    else -> SimulatorError.ValidationError.CustomValidation(field, message)
                }
            }
        } else {
            emptyList()
        }
    }
}

/**
 * Result of passport data validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: Map<String, String> = emptyMap()
) {
    /**
     * Gets the first error message, if any.
     */
    fun getFirstError(): String? = errors.values.firstOrNull()
    
    /**
     * Gets error message for a specific field.
     */
    fun getFieldError(field: String): String? = errors[field]
    
    /**
     * Checks if a specific field has an error.
     */
    fun hasFieldError(field: String): Boolean = errors.containsKey(field)
}