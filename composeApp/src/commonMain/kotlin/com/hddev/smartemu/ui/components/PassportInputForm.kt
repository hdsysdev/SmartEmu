package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate

/**
 * The document and holder details printed on the data page and stored in DG1, grouped as on the passport.
 * Chip settings live on their own screen.
 */
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Document",
            subtitle = "The number and dates also form the key a reader uses to open the chip",
            icon = Icons.Outlined.Description
        ) {
            PassportTextField(
                value = passportData.passportNumber,
                onValueChange = onPassportNumberChange,
                label = "Passport number",
                placeholder = "e.g. 123456789",
                helper = "6 to 9 letters or digits",
                error = validationErrors["passportNumber"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Characters
            )
            CountryDropdown(
                label = "Issuing country",
                selectedCountry = passportData.issuingCountry,
                onCountrySelected = onIssuingCountryChange,
                enabled = enabled,
                isError = validationErrors.containsKey("issuingCountry"),
                errorMessage = validationErrors["issuingCountry"],
                modifier = Modifier.fillMaxWidth()
            )
            DatePickerField(
                label = "Date of expiry",
                selectedDate = passportData.expiryDate,
                onDateSelected = onExpiryDateChange,
                enabled = enabled,
                isError = validationErrors.containsKey("expiryDate"),
                errorMessage = validationErrors["expiryDate"],
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionCard(title = "Holder", subtitle = "As printed on the data page", icon = Icons.Outlined.Person) {
            PassportTextField(
                value = passportData.lastName,
                onValueChange = onLastNameChange,
                label = "Surname",
                placeholder = "e.g. Doe",
                error = validationErrors["lastName"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Words
            )
            PassportTextField(
                value = passportData.firstName,
                onValueChange = onFirstNameChange,
                label = "Given names",
                placeholder = "e.g. John",
                error = validationErrors["firstName"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Words
            )
            DatePickerField(
                label = "Date of birth",
                selectedDate = passportData.dateOfBirth,
                onDateSelected = onDateOfBirthChange,
                enabled = enabled,
                isError = validationErrors.containsKey("dateOfBirth"),
                errorMessage = validationErrors["dateOfBirth"],
                modifier = Modifier.fillMaxWidth()
            )
            GenderSelector(
                selectedGender = passportData.gender,
                onGenderSelected = onGenderChange,
                enabled = enabled,
                errorMessage = validationErrors["gender"],
                modifier = Modifier.fillMaxWidth()
            )
            CountryDropdown(
                label = "Nationality",
                selectedCountry = passportData.nationality,
                onCountrySelected = onNationalityChange,
                enabled = enabled,
                isError = validationErrors.containsKey("nationality"),
                errorMessage = validationErrors["nationality"],
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * A single-line text field showing [error] below it, or [helper] while there is no error.
 */
@Composable
private fun PassportTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    error: String?,
    enabled: Boolean,
    capitalization: KeyboardCapitalization,
    helper: String? = null
) {
    val supporting = error ?: helper
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText = supporting?.let { { Text(text = it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
