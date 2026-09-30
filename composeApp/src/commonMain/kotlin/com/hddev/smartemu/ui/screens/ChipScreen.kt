package com.hddev.smartemu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Fingerprint
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
import com.hddev.smartemu.data.ChipConfiguration
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.KeySpec
import com.hddev.smartemu.data.chipConfiguration
import com.hddev.smartemu.ui.components.ChipProfileDetails
import com.hddev.smartemu.ui.components.ChipProfilePicker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.ChipFault
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // What the chip will do when the emulation starts, with the cryptography the settings choose
    val configuration = remember(passportData, settings.usesExactCryptography) {
        passportData.copy(exactCryptography = settings.usesExactCryptography).chipConfiguration()
    }

    ScreenColumn(modifier = modifier) {
        if (!editable && uiState.simulationStatus.isActiveOrStarting()) {
            EditingLockedBanner(onStopEmulation = viewModel::stopSimulation, stopEnabled = uiState.canStopSimulation())
        }

        ChipProfilePicker(
            selected = configuration.profile,
            enabled = editable,
            onSelected = viewModel::updateChipProfile
        )
        ChipProfileDetails(configuration = configuration)

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
                subtitle = "How PACE agrees on a session key: ECDH on ${configuration.pace.curve.displayName} with " +
                    configuration.pace.cipher.displayName,
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

        SectionCard(
            title = "Active Authentication",
            subtitle = "Proves the chip isn't a copy: it signs the reader's challenge with a key only it holds",
            icon = Icons.Outlined.Fingerprint
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = passportData.activeAuthentication,
                        enabled = editable,
                        role = Role.Switch,
                        onValueChange = viewModel::updateActiveAuthentication
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = activeAuthenticationDescription(configuration.profile.activeAuthentication ?: KeySpec.Ec(EcCurve.NIST_P256)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = passportData.activeAuthentication, onCheckedChange = null, enabled = editable)
            }
        }

        FaultInjectionSection(
            selected = passportData.chipFault,
            enabled = editable,
            onSelected = viewModel::updateChipFault
        )

        ChipFilesSection(passportData = passportData, configuration = configuration)

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
 * The flaw the chip has on purpose, if any, to check that a reader rejects documents that aren't genuine.
 * Collapsed at first unless one is chosen, so that it's not set by accident.
 */
@Composable
private fun FaultInjectionSection(selected: ChipFault, enabled: Boolean, onSelected: (ChipFault) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(selected != ChipFault.NONE) }
    SectionCard(
        title = "Fault injection",
        subtitle = if (selected == ChipFault.NONE) {
            "Advanced: make the chip fail one check a reader should make"
        } else {
            "${selected.displayName}: a reader that checks the chip should reject it"
        },
        subtitleColor = if (selected == ChipFault.NONE) Color.Unspecified else MaterialTheme.colorScheme.error,
        icon = Icons.Outlined.BugReport,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        ChoiceGroup {
            ChipFault.entries.forEach { fault ->
                ChoiceRow(
                    title = fault.displayName,
                    description = if (fault.needsActiveAuthentication) {
                        "${fault.description}. Turns on Active Authentication."
                    } else {
                        fault.description
                    },
                    selected = selected == fault,
                    enabled = enabled,
                    onClick = { onSelected(fault) }
                )
            }
        }
    }
}

/**
 * The elementary files a reader finds on the chip with the current settings. Collapsed at first, as only those
 * testing a reader need the detail.
 */
@Composable
private fun ChipFilesSection(passportData: PassportData, configuration: ChipConfiguration) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pace = configuration.supportsPace
    val signer = documentSignerName(passportData, configuration)
    val files = buildList {
        if (pace) add("EF.CardAccess" to "PACE parameters, readable without authentication")
        if (pace && configuration.paceMapping == PaceMapping.CHIP_AUTHENTICATION) {
            add("EF.CardSecurity" to "The chip's public key for CAM, signed by $signer")
        }
        if (configuration.terminalAuthentication) {
            add("EF.CVCA" to "The test CVCA a terminal certificate would have to chain to")
        }
        add("EF.COM" to "Lists the data groups")
        configuration.dataGroups.forEach { number ->
            add("EF.DG$number" to when (number) {
                1 -> "The MRZ"
                2 -> if (passportData.portrait != null) "The holder's portrait" else "A placeholder portrait"
                3 -> "Fingerprints, locked behind Terminal Authentication"
                11 -> "Full name, personal number, place of birth"
                12 -> "Issuing authority and date of issue"
                14 -> buildList {
                    if (pace) add("PACE")
                    if (configuration.chipAuthentication != null) add("the Chip Authentication key")
                    if (configuration.terminalAuthentication) add("Terminal Authentication")
                    if (configuration.activeAuthentication is KeySpec.Ec) add("the Active Authentication algorithm")
                }.joinToString(prefix = "Security infos: ")
                15 -> "The Active Authentication public key (${configuration.activeAuthentication?.displayName})"
                else -> ""
            })
        }
        add("EF.SOD" to "Hashes of the data groups (${configuration.sod.digestAlgorithm}), signed by $signer")
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
internal fun ChoiceGroup(content: @Composable ColumnScope.() -> Unit) {
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
internal fun ChoiceRow(
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

/** The Document Signer that signs the chip's files, as test-pki/README.md names it. */
private fun documentSignerName(passportData: PassportData, configuration: ChipConfiguration): String =
    when (passportData.chipFault) {
        ChipFault.EXPIRED_SIGNER -> "the expired test Document Signer"
        ChipFault.UNTRUSTED_SIGNER -> "a Document Signer from the unknown CSCA"
        ChipFault.BROKEN_SIGNATURE -> "a key that isn't the Document Signer's"
        else -> {
            val signer = if (passportData.documentType.isCard) configuration.sod.cardSigner else configuration.sod.passportSigner
            "$signer (${configuration.sod.signerKey.displayName}) under ${configuration.sod.csca}"
        }
    }

/** What turning on Active Authentication adds, with the profile's key. */
private fun activeAuthenticationDescription(key: KeySpec): String = when (key) {
    is KeySpec.Ec -> "Adds DG14 and DG15, an ${key.displayName} key, and answers INTERNAL AUTHENTICATE with a " +
        "plain ${key.curve.digestAlgorithm} signature"
    is KeySpec.Rsa -> "Adds DG15, an ${key.displayName} key, and answers INTERNAL AUTHENTICATE with an ISO 9796-2 " +
        "SHA-1 signature"
}
