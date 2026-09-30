package com.hddev.smartemu.ui.screens

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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.AppTheme
import com.hddev.smartemu.ui.components.SectionCard

/**
 * The app's settings, the same in developer mode and out of it: how it looks, whether the screen stays on while
 * the chip is on, the read history, and developer mode with the chip's cryptography.
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
                    SettingSwitch(
                        title = "Exact cryptography",
                        description = if (settings.exactCryptography) {
                            "The chip uses its profile's cryptography as it is, such as Brainpool curves for PACE on " +
                                "German documents. Apps built on the r2w nfc-library can't read those chips."
                        } else {
                            "The chip swaps Brainpool PACE curves for the NIST curve of the same strength, so that " +
                                "apps built on the r2w nfc-library can read every profile. Signatures, Active " +
                                "Authentication and Chip Authentication stay as the profile has them."
                        },
                        checked = settings.exactCryptography,
                        onCheckedChange = { onSettingsChange(settings.copy(exactCryptography = it)) }
                    )
                } else {
                    Text(
                        text = "Out of developer mode, the chip adapts its cryptography to what the r2w " +
                            "nfc-library can read.",
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
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The row handles the click, so the switch only shows the state
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
