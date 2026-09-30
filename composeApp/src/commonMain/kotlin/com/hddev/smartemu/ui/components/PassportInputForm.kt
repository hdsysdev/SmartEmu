package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.DocumentType
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
    modifier: Modifier = Modifier,
    onDocumentTypeChange: ((DocumentType) -> Unit)? = null,
    /** Given, the form also has the details for DG11 and DG12 and the personal number. */
    additionalDetails: AdditionalDetailsCallbacks? = null
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
            onDocumentTypeChange?.let {
                DocumentTypeSelector(selected = passportData.documentType, onSelected = it, enabled = enabled)
            }
            PassportTextField(
                value = passportData.passportNumber,
                onValueChange = onPassportNumberChange,
                label = if (passportData.documentType.isCard) "Document number" else "Passport number",
                placeholder = "e.g. 123456789",
                helper = passportData.chipProfile.mrz.documentNumberFormat
                    ?.let { "Real ones: ${it.replaceFirstChar(Char::lowercase)}. Any 6 to 9 letters or digits work." }
                    ?: "6 to 9 letters or digits",
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

        additionalDetails?.let { callbacks ->
            AdditionalDetailsSection(
                passportData = passportData,
                validationErrors = validationErrors,
                enabled = enabled,
                callbacks = callbacks
            )
        }
    }
}

/** Where the details the MRZ doesn't have go when they change. */
class AdditionalDetailsCallbacks(
    val onPersonalNumberChange: (String) -> Unit,
    val onPlaceOfBirthChange: (String) -> Unit,
    val onIssuingAuthorityChange: (String) -> Unit,
    val onDateOfIssueChange: (LocalDate?) -> Unit
)

/**
 * The details in DG11 and DG12, on chips whose profile has them, and the personal number, which some states also
 * put in the MRZ. Collapsed at first unless one is filled in, as most readers ignore them.
 */
@Composable
private fun AdditionalDetailsSection(
    passportData: PassportData,
    validationErrors: Map<String, String>,
    enabled: Boolean,
    callbacks: AdditionalDetailsCallbacks
) {
    val profile = passportData.chipProfile
    val keys = listOf("personalNumber", "placeOfBirth", "issuingAuthority", "dateOfIssue")
    val hasDetails = passportData.personalNumber.isNotEmpty() || passportData.placeOfBirth.isNotEmpty() ||
        passportData.issuingAuthority.isNotEmpty() || passportData.dateOfIssue != null
    var expanded by rememberSaveable { mutableStateOf(hasDetails) }
    val hasErrors = keys.any(validationErrors::containsKey)
    val inMrz = profile.mrz.personalNumberInMrz

    SectionCard(
        title = "More details",
        subtitle = when {
            hasErrors -> "Some of these details need fixing"
            11 in profile.dataGroups || 12 in profile.dataGroups -> "Stored in DG11 and DG12, as on ${profile.shortTitle}"
            else -> "Stored only on chips whose profile has DG11 and DG12"
        },
        subtitleColor = if (hasErrors) MaterialTheme.colorScheme.error else Color.Unspecified,
        icon = Icons.Outlined.Badge,
        expanded = expanded || hasErrors,
        onToggleExpanded = { expanded = !expanded }
    ) {
        PassportTextField(
            value = passportData.personalNumber,
            onValueChange = callbacks.onPersonalNumberChange,
            label = "Personal number",
            placeholder = "e.g. 999999990",
            helper = if (inMrz) "Also in the MRZ, as on ${profile.shortTitle}" else "Optional",
            error = validationErrors["personalNumber"],
            enabled = enabled,
            capitalization = KeyboardCapitalization.Characters
        )
        PassportTextField(
            value = passportData.placeOfBirth,
            onValueChange = callbacks.onPlaceOfBirthChange,
            label = "Place of birth",
            placeholder = "e.g. Berlin",
            error = validationErrors["placeOfBirth"],
            enabled = enabled,
            capitalization = KeyboardCapitalization.Words
        )
        PassportTextField(
            value = passportData.issuingAuthority,
            onValueChange = callbacks.onIssuingAuthorityChange,
            label = "Issuing authority",
            placeholder = "e.g. Stadt Köln",
            error = validationErrors["issuingAuthority"],
            enabled = enabled,
            capitalization = KeyboardCapitalization.Words
        )
        DatePickerField(
            label = "Date of issue",
            selectedDate = passportData.dateOfIssue,
            onDateSelected = callbacks.onDateOfIssueChange,
            enabled = enabled,
            isError = validationErrors.containsKey("dateOfIssue"),
            errorMessage = validationErrors["dateOfIssue"],
            modifier = Modifier.fillMaxWidth()
        )
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
