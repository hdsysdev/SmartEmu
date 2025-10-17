package com.hddev.smartemu.data

/**
 * Represents the result of a validation operation.
 * Contains information about whether the validation passed and any error messages.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        /**
         * Creates a successful validation result.
         */
        fun success(): ValidationResult {
            return ValidationResult(isValid = true)
        }
        
        /**
         * Creates a failed validation result with an error message.
         */
        fun failure(errorMessage: String): ValidationResult {
            return ValidationResult(isValid = false, errorMessage = errorMessage)
        }
    }
}