package com.hddev.smartemu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.ui.components.EditingLockedBanner
import com.hddev.smartemu.ui.components.NextStepButton
import com.hddev.smartemu.ui.components.SectionCard
import com.hddev.smartemu.ui.navigation.AppDestination
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * How the emulated chip protects its data: the access control protocols, the PACE mapping and the CAN,
 * and, for those who want the detail, the files the chip serves as a result. [onNext] leads to the next setup
 * screen.
 */
@Composable
fun ChipScreen(
    uiState: PassportSimulatorUiState,
    viewModel: PassportSimulatorViewModel,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val passportData = uiState.passportData
    val editable = uiState.isPassportFormEnabled()

    ScreenColumn(modifier = modifier) {
        if (!editable && uiState.simulationStatus.isActiveOrStarting()) {
            EditingLockedBanner(onStopEmulation = viewModel::stopSimulation, stopEnabled = uiState.canStopSimulation())
        }

        SectionCard(
            title = "Access control",
            subtitle = "How a reader proves it may read the chip",
            icon = Icons.Outlined.VerifiedUser
        ) {
            ChoiceGroup {
                AccessControl.entries.forEach { accessControl ->
                    ChoiceRow(
                        title = accessControl.displayName,
                        description = accessControl.description,
                        selected = passportData.accessControl == accessControl,
                        enabled = editable,
                        onClick = { viewModel.updateAccessControl(accessControl) }
                    )
                }
            }
        }

        if (passportData.accessControl.supportsPace) {
            SectionCard(
                title = "PACE mapping",
                subtitle = "How PACE agrees on a session key: ECDH on NIST P-256 with AES-128",
                icon = Icons.Outlined.Key
            ) {
                ChoiceGroup {
                    PaceMapping.entries.forEach { mapping ->
                        ChoiceRow(
                            title = "${mapping.displayName} (${mapping.abbreviation})",
                            description = mapping.description,
                            selected = passportData.paceMapping == mapping,
                            enabled = editable,
                            onClick = { viewModel.updatePaceMapping(mapping) }
                        )
                    }
                }
            }

            CanSection(
                passportData = passportData,
                error = uiState.validationErrors["can"],
                enabled = editable,
                onCanChange = viewModel::updateCan,
                onGenerateCan = viewModel::generateCan
            )
        }

        ChipFilesSection(passportData = passportData)

        NextStepButton(
            label = "Next: ${AppDestination.EMULATOR.title}",
            onClick = onNext,
            icon = AppDestination.EMULATOR.selectedIcon
        )
    }
}

@Composable
private fun CanSection(
    passportData: PassportData,
    error: String?,
    enabled: Boolean,
    onCanChange: (String) -> Unit,
    onGenerateCan: () -> Unit
) {
    SectionCard(
        title = "Card Access Number",
        subtitle = "A ${PassportData.CAN_LENGTH}-digit number printed on the data page. " +
            "Readers can type it instead of scanning the MRZ.",
        icon = Icons.Outlined.Pin
    ) {
        OutlinedTextField(
            value = passportData.can,
            onValueChange = onCanChange,
            label = { Text("CAN") },
            placeholder = { Text("${PassportData.CAN_LENGTH} digits") },
            enabled = enabled,
            singleLine = true,
            isError = error != null,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            supportingText = {
                Text(
                    text = error ?: if (passportData.hasCan()) {
                        "PACE accepts the MRZ or this CAN"
                    } else {
                        "Leave empty for a chip that accepts only the MRZ"
                    }
                )
            },
            trailingIcon = {
                IconButton(onClick = onGenerateCan, enabled = enabled) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Generate a random CAN")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The elementary files a reader finds on the chip with the current settings. Collapsed at first, as only those
 * testing a reader need the detail.
 */
@Composable
private fun ChipFilesSection(passportData: PassportData) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pace = passportData.accessControl.supportsPace
    val files = buildList {
        if (pace) add("EF.CardAccess" to "PACE parameters, readable without authentication")
        if (pace && passportData.paceMapping == PaceMapping.CHIP_AUTHENTICATION) {
            add("EF.CardSecurity" to "The chip's public key for CAM, signed by the test Document Signer")
        }
        add("EF.COM" to "Lists the data groups")
        add("EF.DG1" to "The MRZ")
        add("EF.DG2" to if (passportData.portrait != null) "The holder's portrait" else "A placeholder portrait")
        add("EF.SOD" to "Hashes of the data groups, signed by the test Document Signer")
    }

    SectionCard(
        title = "Chip contents",
        subtitle = "Advanced: the ${files.size} files a reader finds, signed with the repository's test PKI",
        icon = Icons.Outlined.FolderOpen,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        files.forEach { (name, description) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(128.dp)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/**
 * One option of a single choice, with a line explaining what choosing it does, outlined in the primary colour
 * while selected.
 */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // The row handles the click, so the button only shows the state
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
