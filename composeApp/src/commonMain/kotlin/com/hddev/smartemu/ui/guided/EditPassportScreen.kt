package com.hddev.smartemu.ui.guided

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.CountryDropdown
import com.hddev.smartemu.ui.components.DatePickerField
import com.hddev.smartemu.ui.components.GenderSelector
import com.hddev.smartemu.ui.components.PortraitImage
import com.hddev.smartemu.ui.components.rememberPortraitPicker
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * The passport's details in plain words, its photo, and, tucked away under "More options", the kind of
 * passport it pretends to be. Changes save as they're made; [onDone] goes back.
 */
@Composable
fun EditPassportScreen(
    uiState: PassportSimulatorUiState,
    viewModel: PassportSimulatorViewModel,
    onShowMessage: (String) -> Unit,
    onDone: () -> Unit
) {
    val passportData = uiState.passportData
    val errors = uiState.validationErrors
    val enabled = uiState.isPassportFormEnabled()
    val portraitPicker = rememberPortraitPicker(onPicked = viewModel::updatePortrait, onFailed = onShowMessage)
    // Open while the document is set as a fake, so that it's plain to see why
    var moreOptions by rememberSaveable { mutableStateOf(passportData.chipFault != ChipFault.NONE) }

    val documentType = passportData.documentType
    GuidedScaffold(
        title = "${documentType.displayName} details",
        onBack = onDone,
        bottomBar = {
            BottomActions {
                AppButton(
                    text = "Done",
                    onClick = onDone,
                    icon = Icons.Filled.Check,
                    emphasis = ButtonEmphasis.High,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                )
            }
        }
    ) {
        NoticeCard(
            title = "Any details will do",
            text = "Use any details you like. The app that reads the ${documentType.noun} should show exactly what you " +
                "enter here.",
            icon = Icons.Outlined.Info,
            tone = NoticeTone.INFO,
            action = {
                AppButton(
                    text = "Fill in sample details",
                    onClick = viewModel::autofillPassportData,
                    icon = Icons.Filled.AutoFixHigh,
                    emphasis = ButtonEmphasis.Low,
                    enabled = enabled
                )
            }
        )

        GuidedCard(title = "Type of document") {
            Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocumentType.entries.forEach { type ->
                    ChoiceRow(
                        title = type.displayName,
                        description = type.friendlyDescription,
                        selected = documentType == type,
                        enabled = enabled,
                        onClick = { viewModel.updateDocumentType(type) }
                    )
                }
            }
        }

        GuidedCard(title = "Person") {
            DetailField(
                value = passportData.firstName,
                onValueChange = viewModel::updateFirstName,
                label = "First names",
                error = errors["firstName"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Words
            )
            DetailField(
                value = passportData.lastName,
                onValueChange = viewModel::updateLastName,
                label = "Surname",
                error = errors["lastName"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Words
            )
            DatePickerField(
                label = "Date of birth",
                selectedDate = passportData.dateOfBirth,
                onDateSelected = viewModel::updateDateOfBirth,
                enabled = enabled,
                isError = errors.containsKey("dateOfBirth"),
                errorMessage = errors["dateOfBirth"],
                modifier = Modifier.fillMaxWidth()
            )
            GenderSelector(
                selectedGender = passportData.gender,
                onGenderSelected = viewModel::updateGender,
                enabled = enabled,
                errorMessage = errors["gender"],
                modifier = Modifier.fillMaxWidth()
            )
            CountryDropdown(
                label = "Nationality",
                selectedCountry = passportData.nationality,
                onCountrySelected = viewModel::updateNationality,
                enabled = enabled,
                isError = errors.containsKey("nationality"),
                errorMessage = errors["nationality"],
                modifier = Modifier.fillMaxWidth()
            )
        }

        GuidedCard(title = documentType.displayName) {
            DetailField(
                value = passportData.passportNumber,
                onValueChange = viewModel::updatePassportNumber,
                label = if (documentType.isCard) "Document number" else "Passport number",
                helper = "6 to 9 letters or numbers",
                error = errors["passportNumber"],
                enabled = enabled,
                capitalization = KeyboardCapitalization.Characters
            )
            CountryDropdown(
                label = "Issued by",
                selectedCountry = passportData.issuingCountry,
                onCountrySelected = viewModel::updateIssuingCountry,
                enabled = enabled,
                isError = errors.containsKey("issuingCountry"),
                errorMessage = errors["issuingCountry"],
                modifier = Modifier.fillMaxWidth()
            )
            DatePickerField(
                label = "Expiry date",
                selectedDate = passportData.expiryDate,
                onDateSelected = viewModel::updateExpiryDate,
                enabled = enabled,
                isError = errors.containsKey("expiryDate"),
                errorMessage = errors["expiryDate"],
                modifier = Modifier.fillMaxWidth()
            )
        }

        GuidedCard(title = "Photo") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PortraitImage(portrait = passportData.portrait, modifier = Modifier.width(72.dp).height(96.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (passportData.portrait != null) {
                            "The other app should show this photo."
                        } else {
                            "Optional. Without one, the ${documentType.noun} has a grey silhouette."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (portraitPicker.canTakeSelfie) {
                            AppButton(text = "Take selfie", onClick = portraitPicker::takeSelfie, icon = Icons.Filled.PhotoCamera, enabled = enabled)
                        }
                        AppButton(
                            text = "Choose photo",
                            onClick = portraitPicker::chooseFromLibrary,
                            icon = Icons.Filled.PhotoLibrary,
                            emphasis = if (portraitPicker.canTakeSelfie) ButtonEmphasis.Low else ButtonEmphasis.Medium,
                            enabled = enabled
                        )
                        if (passportData.portrait != null) {
                            AppButton(
                                text = "Remove",
                                onClick = { viewModel.updatePortrait(null) },
                                icon = Icons.Filled.Delete,
                                emphasis = ButtonEmphasis.Low,
                                destructive = true,
                                enabled = enabled
                            )
                        }
                    }
                }
            }
        }

        ExpandableItem(
            title = "More options",
            expanded = moreOptions,
            onToggle = { moreOptions = !moreOptions },
            icon = Icons.Outlined.Tune
        ) {
            MoreOptions(passportData = passportData, canError = errors["can"], enabled = enabled, viewModel = viewModel)
        }
    }
}

/**
 * The kinds of passport the app can pretend to be, in everyday words, for seeing how an app that reads
 * passports copes with each.
 */
private enum class PassportKind(val title: String, val description: String, val accessControl: AccessControl) {
    STANDARD(
        "Standard (recommended)",
        "Like most passports issued today. Works with nearly every app.",
        AccessControl.BAC_AND_PACE
    ),
    NEWEST_ONLY(
        "Newest security only",
        "Turns away apps that only know the older way of opening a passport.",
        AccessControl.PACE_ONLY
    ),
    OLDER(
        "Older passport",
        "Like passports from before about 2014.",
        AccessControl.BAC_ONLY
    )
}

@Composable
private fun ColumnScope.MoreOptions(
    passportData: PassportData,
    canError: String?,
    enabled: Boolean,
    viewModel: PassportSimulatorViewModel
) {
    Text(
        "You won't usually need these. They make this passport behave like different kinds of real ones.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text("Type of passport", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PassportKind.entries.forEach { kind ->
            ChoiceRow(
                title = kind.title,
                description = kind.description,
                selected = passportData.accessControl == kind.accessControl,
                enabled = enabled,
                onClick = { viewModel.updateAccessControl(kind.accessControl) }
            )
        }
    }

    // Active Authentication works with any chip; the check built into the newer way of opening it needs that way
    val usesPace = passportData.accessControl.supportsPace
    SwitchRow(
        title = "Anti-copy check",
        description = "The chip proves it isn't a copy, as most modern passports can.",
        checked = passportData.activeAuthentication ||
            (usesPace && passportData.paceMapping == PaceMapping.CHIP_AUTHENTICATION),
        enabled = enabled,
        onCheckedChange = { on ->
            viewModel.updateActiveAuthentication(on)
            if (usesPace) viewModel.updatePaceMapping(if (on) PaceMapping.CHIP_AUTHENTICATION else PaceMapping.GENERIC)
        }
    )

    // The access number is only for the newer way of opening the passport, which older passports lack
    if (usesPace) {
        SwitchRow(
            title = "Access number",
            description = "A ${PassportData.CAN_LENGTH}-digit number printed on the photo page. Some apps let you " +
                "type it instead of scanning the page.",
            checked = passportData.hasCan(),
            enabled = enabled,
            onCheckedChange = { on -> if (on) viewModel.generateCan() else viewModel.updateCan("") }
        )
        if (passportData.hasCan()) {
            OutlinedTextField(
                value = passportData.can,
                onValueChange = viewModel::updateCan,
                label = { Text("Access number") },
                enabled = enabled,
                singleLine = true,
                isError = canError != null,
                supportingText = canError?.let { { Text("Enter ${PassportData.CAN_LENGTH} digits") } },
                trailingIcon = {
                    IconButton(onClick = viewModel::generateCan, enabled = enabled) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New random number")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Text("Make it a fake", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    Text(
        "To see whether an app notices a forged ${passportData.documentType.noun}. Each fake fails one security " +
            "check; an app that checks properly should refuse it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipFault.entries.forEach { fault ->
            ChoiceRow(
                title = fault.friendlyName,
                description = fault.friendlyDescription,
                selected = passportData.chipFault == fault,
                enabled = enabled,
                onClick = { viewModel.updateChipFault(fault) }
            )
        }
    }
}

@Composable
private fun DetailField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
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
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ChoiceRow(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
