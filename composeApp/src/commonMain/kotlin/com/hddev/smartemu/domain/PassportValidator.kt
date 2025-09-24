package com.hddev.smartemu.domain

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.DateValidationUtils

/**
 * Validator for passport data with comprehensive validation rules.
 * Provides detailed validation results and error messages.
 */
object PassportValidator {
    
    // ICAO passport number validation regex
    private val PASSPORT_NUMBER_REGEX = Regex("^[A-Z0-9]{6,9}$")
    
    // Valid country codes (ISO 3166-1 alpha-3)
    private val VALID_COUNTRIES = setOf(
        "NLD", "USA", "GBR", "DEU", "FRA", "ESP", "ITA", "CAN", "AUS", "JPN",
        "BEL", "CHE", "AUT", "DNK", "FIN", "NOR", "SWE", "IRL", "PRT", "GRC"
    )
    
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
     * Validates expiry date.
     */
    fun validateExpiryDate(
        expiryDate: kotlinx.datetime.LocalDate?, 
        dateOfBirth: kotlinx.datetime.LocalDate?
    ): String? {
        return when {
            expiryDate == null -> "Expiry date is required"
            !DateValidationUtils.isFutureDate(expiryDate) -> 
                "Expiry date must be in the future"
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
            !VALID_COUNTRIES.contains(countryCode.uppercase()) -> 
                "Invalid $fieldName code. Must be a valid ISO 3166-1 alpha-3 code"
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