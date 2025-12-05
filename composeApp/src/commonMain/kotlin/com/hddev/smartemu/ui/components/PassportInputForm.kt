package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate

/**
 * Compose UI component for passport data input form.
 * Provides text fields for all passport details with real-time validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportInputForm(
    passportData: PassportData,
    validationErrors: Map<String, String>,
    enabled: Boolean = true,
    onPassportNumberChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onDateOfBirthChange: (LocalDate?) -> Unit,
    onExpiryDateChange: (LocalDate?) -> Unit,
    onGenderChange: (String) -> Unit,
    onIssuingCountryChange: (String) -> Unit,
    onNationalityChange: (String) -> Unit,
    onResetForm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Form Header
        Text(
            text = "Passport Details",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        
        // Passport Number Field
        OutlinedTextField(
            value = passportData.passportNumber,
            onValueChange = onPassportNumberChange,
            label = { Text("Passport Number") },
            placeholder = { Text("e.g., AB123456") },
            enabled = enabled,
            isError = validationErrors.containsKey("passportNumber"),
            supportingText = {
                validationErrors["passportNumber"]?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        // Name Fields Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // First Name
            OutlinedTextField(
                value = passportData.firstName,
                onValueChange = onFirstNameChange,
                label = { Text("First Name") },
                placeholder = { Text("John") },
                enabled = enabled,
                isError = validationErrors.containsKey("firstName"),
                supportingText = {
                    validationErrors["firstName"]?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    keyboardType = KeyboardType.Text
                ),
                modifier = Modifier.weight(1f)
            )
            
            // Last Name
            OutlinedTextField(
                value = passportData.lastName,
                onValueChange = onLastNameChange,
                label = { Text("Last Name") },
                placeholder = { Text("Doe") },
                enabled = enabled,
                isError = validationErrors.containsKey("lastName"),
                supportingText = {
                    validationErrors["lastName"]?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    keyboardType = KeyboardType.Text
                ),
                modifier = Modifier.weight(1f)
            )
        }
        
        // Date Fields
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Date of Birth
            DatePickerField(
                label = "Date of Birth",
                selectedDate = passportData.dateOfBirth,
                onDateSelected = onDateOfBirthChange,
                enabled = enabled,
                isError = validationErrors.containsKey("dateOfBirth"),
                errorMessage = validationErrors["dateOfBirth"],
                modifier = Modifier.weight(1f)
            )
            
            // Expiry Date
            DatePickerField(
                label = "Expiry Date",
                selectedDate = passportData.expiryDate,
                onDateSelected = onExpiryDateChange,
                enabled = enabled,
                isError = validationErrors.containsKey("expiryDate"),
                errorMessage = validationErrors["expiryDate"],
                modifier = Modifier.weight(1f)
            )
        }
        
        // Gender and Country Fields Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gender Dropdown
            GenderDropdown(
                selectedGender = passportData.gender,
                onGenderSelected = onGenderChange,
                enabled = enabled,
                isError = validationErrors.containsKey("gender"),
                errorMessage = validationErrors["gender"],
                modifier = Modifier.weight(1f)
            )
            
            // Issuing Country
            CountryDropdown(
                label = "Issuing Country",
                selectedCountry = passportData.issuingCountry,
                onCountrySelected = onIssuingCountryChange,
                enabled = enabled,
                isError = validationErrors.containsKey("issuingCountry"),
                errorMessage = validationErrors["issuingCountry"],
                modifier = Modifier.weight(1f)
            )
        }
        
        // Nationality Field
        CountryDropdown(
            label = "Nationality",
            selectedCountry = passportData.nationality,
            onCountrySelected = onNationalityChange,
            enabled = enabled,
            isError = validationErrors.containsKey("nationality"),
            errorMessage = validationErrors["nationality"],
            modifier = Modifier.fillMaxWidth()
        )
        
        // Form Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reset Button
            OutlinedButton(
                onClick = onResetForm,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Form")
            }
            
            // Validation Status
            if (validationErrors.isEmpty() && passportData.passportNumber.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Valid",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Valid",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (validationErrors.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Invalid",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "${validationErrors.size} error(s)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}