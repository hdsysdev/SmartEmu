package com.hddev.smartemu.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.AppTheme
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PaceSuite
import com.hddev.smartemu.data.SessionCipher
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.capture.LocalCaptureEntry
import com.hddev.smartemu.ui.components.SectionCard
import com.hddev.smartemu.ui.glossary.GlossaryText
import com.hddev.smartemu.ui.glossary.LocalGlossary

/**
 * The app's settings, the same in developer mode and out of it: how it looks, whether the screen stays on while
 * the chip is on, the read history, and developer mode with the chip's cryptography, the PACE suite every
 * document's chip runs, whatever its profile, and more suites for it to offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        ScreenColumn(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            // Developer mode always offers capture mode; out of it, seven taps on this text unlock its card below
            val captureEntry = LocalCaptureEntry.current
            val captureShown = captureEntry != null && (captureEntry.unlocked || settings.developerMode)
            SectionCard(title = "About PassportEmu") {
                Text(
                    text = "PassportEmu turns this phone into a virtual passport or ID card for another app to read. " +
                        "The documents use test certificates and can't be used as real ID.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (captureEntry != null && !captureShown) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = captureEntry::registerUnlockTap
                        )
                    } else {
                        Modifier
                    }
                )
                if (settings.developerMode) GlossaryText(
                    text = "The test certificates keep their legacy SmartEmu names. Readers must trust the matching " +
                        "test CSCA certificate to verify their signatures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LocalGlossary.current?.takeIf { settings.developerMode }?.let { glossary ->
                SectionCard(
                    title = "Terms and abbreviations",
                    subtitle = "What PACE, MRZ, CSCA and the app's other abbreviations stand for and mean. Tap any " +
                        "dotted term in the app to explain it on the spot.",
                    icon = Icons.AutoMirrored.Outlined.MenuBook
                ) {
                    AppButton(
                        text = "Open the glossary",
                        onClick = glossary::showAll,
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        emphasis = ButtonEmphasis.Low
                    )
                }
            }

            if (captureEntry != null && captureShown) {
                SectionCard(
                    title = "Capture a real document",
                    subtitle = "Experimental. Reads how a passport or ID card's chip behaves, with no key, and saves " +
                        "a redacted record you can share. Own or consenting colleagues' documents only.",
                    icon = Icons.Outlined.Nfc
                ) {
                    AppButton(text = "Open capture mode", onClick = captureEntry::open, icon = Icons.Outlined.Nfc)
                    // Developer mode shows it regardless, so hiding only means something out of it
                    if (!settings.developerMode) TextButton(onClick = captureEntry::lock) { Text("Hide capture mode") }
                }
            }

            SectionCard(title = "Appearance", icon = Icons.Outlined.LightMode) {
                ChoiceGroup {
                    AppTheme.entries.forEach { theme ->
                        ChoiceRow(
                            title = theme.displayName,
                            description = when (theme) {
                                AppTheme.SYSTEM -> "Light or dark, as the phone is set"
                                AppTheme.LIGHT -> "Always light"
                                AppTheme.DARK -> "Always dark"
                            },
                            selected = settings.theme == theme,
                            enabled = true,
                            onClick = { onSettingsChange(settings.copy(theme = theme)) }
                        )
                    }
                }
            }

            SectionCard(title = "While in use", icon = Icons.Outlined.Smartphone) {
                SettingSwitch(
                    title = "Keep the screen on",
                    description = "So the phone doesn't lock in the middle of a read",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
                )
            }

            SectionCard(title = "Past reads", icon = Icons.Outlined.History) {
                SettingSwitch(
                    title = "Keep a history of reads",
                    description = if (settings.keepReadHistory) {
                        "Each read is listed with what the reader checked, on this phone only"
                    } else {
                        "Reads aren't recorded. Turning this off cleared the history."
                    },
                    checked = settings.keepReadHistory,
                    onCheckedChange = { onSettingsChange(settings.copy(keepReadHistory = it)) }
                )
            }

            SectionCard(
                title = "Developer mode",
                subtitle = "For developers testing apps that read passports and ID cards",
                icon = Icons.Outlined.Code
            ) {
                SettingSwitch(
                    title = "Developer mode",
                    description = "Shows every chip setting, the chip profiles by country with where their facts " +
                        "come from, and each command the reader sends",
                    checked = settings.developerMode,
                    onCheckedChange = { onSettingsChange(settings.copy(developerMode = it)) }
                )
                if (settings.developerMode) {
                    GlossaryText(
                        text = "PACE suite for every document, to test a reader against each one ICAO 9303 " +
                            "defines. Domain parameters chosen here replace the profile's.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OverrideDropdown<Any>(
                        label = "PACE domain parameters",
                        selected = settings.paceDhGroup ?: settings.paceCurve,
                        options = PACE_DOMAINS,
                        optionText = ::domainText,
                        onSelected = {
                            onSettingsChange(settings.copy(paceCurve = it as? EcCurve, paceDhGroup = it as? DhGroup))
                        }
                    )
                    OverrideDropdown(
                        label = "PACE cipher",
                        selected = settings.paceCipher,
                        options = SessionCipher.entries,
                        optionText = { it.displayName },
                        onSelected = { onSettingsChange(settings.copy(paceCipher = it)) }
                    )
                    ExtraPaceSuites(settings, onSettingsChange)
                } else {
                    Text(
                        text = "Profiles use compatible settings by default. Turn on developer mode for detailed " +
                            "chip controls and the sources behind each profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A setting that's on or off: the whole row toggles it. */
@Composable
internal fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GlossaryText(text = title, style = MaterialTheme.typography.bodyLarge)
            GlossaryText(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The row handles the click, so the switch only shows the state
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The PACE suites the chip offers besides the document's own: the list, each with a button to remove it, and a suite
 * to put together and add.
 */
@Composable
private fun ExtraPaceSuites(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    var mapping by remember { mutableStateOf(PaceMapping.GENERIC) }
    var domain by remember { mutableStateOf<Any>(EcCurve.NIST_P256) }
    var cipher by remember { mutableStateOf(SessionCipher.AES_128) }
    val suites = settings.extraPaceSuites

    GlossaryText(text = "More PACE suites", style = MaterialTheme.typography.titleSmall)
    GlossaryText(
        text = "EF.CardAccess offers these besides the document's own, to test which one a reader picks and that " +
            "it names the domain parameters (DO'84) when there are several.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    suites.forEachIndexed { index, suite ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlossaryText(text = suite.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                onSettingsChange(settings.copy(extraPaceSuites = suites.filterIndexed { i, _ -> i != index }))
            }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${suite.displayName}")
            }
        }
    }
    SettingDropdown(
        label = "Mapping",
        selected = mapping,
        options = PaceMapping.entries,
        optionText = { "${it.displayName} (${it.abbreviation})" },
        onSelected = { mapping = it }
    )
    SettingDropdown(
        label = "Domain parameters",
        selected = domain,
        options = PACE_DOMAINS,
        optionText = ::domainText,
        onSelected = { domain = it }
    )
    SettingDropdown(
        label = "Cipher",
        selected = cipher,
        options = SessionCipher.entries,
        optionText = { it.displayName },
        onSelected = { cipher = it }
    )
    val spec = when (val chosen = domain) {
        is DhGroup -> PaceSpec(cipher = cipher, dhGroup = chosen)
        else -> PaceSpec(curve = chosen as EcCurve, cipher = cipher)
    }
    val suite = PaceSuite(mapping, spec)
    TextButton(
        onClick = { onSettingsChange(settings.copy(extraPaceSuites = suites + suite)) },
        enabled = suites.none { it.key == suite.key }
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null)
        Text(text = "Add ${suite.displayName}", modifier = Modifier.padding(start = 8.dp))
    }
    if (suites.isNotEmpty()) {
        SettingSwitch(
            title = "Offer them first",
            description = "Lists them in EF.CardAccess before the document's own suite, rather than after it",
            checked = settings.extraPaceSuitesFirst,
            onCheckedChange = { onSettingsChange(settings.copy(extraPaceSuitesFirst = it)) }
        )
    }
}

/** The PACE domain parameters ICAO 9303 standardises, in parameter ID order: the DH groups, then the curves. */
private val PACE_DOMAINS: List<Any> = DhGroup.entries + EcCurve.entries

private fun domainText(domain: Any): String = when (domain) {
    is DhGroup -> "${domain.displayName}, RFC 5114 (parameter ID ${domain.paceParameterId})"
    is EcCurve -> "${domain.displayName} (parameter ID ${domain.paceParameterId})"
    else -> domain.toString()
}

/** Picks one of [options] in place of the profile's choice, or the profile's choice again with null. */
@Composable
private fun <T : Any> OverrideDropdown(
    label: String,
    selected: T?,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T?) -> Unit
) {
    SettingDropdown(
        label = label,
        selected = selected,
        options = listOf(null) + options,
        optionText = { it?.let(optionText) ?: PROFILE_CHOICE },
        onSelected = onSelected
    )
}

/** Picks one of [options]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionText: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = optionText(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

private const val PROFILE_CHOICE = "As the profile has it"
