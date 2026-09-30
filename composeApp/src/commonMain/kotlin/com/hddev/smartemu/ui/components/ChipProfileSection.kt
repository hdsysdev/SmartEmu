package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.ChipConfiguration
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.Provenance
import com.hddev.smartemu.ui.screens.ChoiceGroup
import com.hddev.smartemu.ui.screens.ChoiceRow

/**
 * The chip profile to emulate, collapsed to the chosen one at first, as there are many.
 */
@Composable
fun ChipProfilePicker(
    selected: ChipProfile,
    enabled: Boolean,
    onSelected: (ChipProfile) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "Chip profile",
        subtitle = selected.title,
        icon = Icons.Outlined.Flag,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        Text(
            text = "How a real kind of document's chip behaves, as far as it's known. Choosing one sets the access " +
                "control, PACE mapping and Active Authentication below, which you can still change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChoiceGroup {
            ChipProfiles.all.forEach { profile ->
                ChoiceRow(
                    title = profile.title,
                    description = profile.summary,
                    selected = profile.id == selected.id,
                    enabled = enabled,
                    onClick = {
                        onSelected(profile)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * What the chosen profile makes the chip do, each part with where the facts come from, and how the chip differs from
 * the profile, if it does.
 */
@Composable
fun ChipProfileDetails(configuration: ChipConfiguration) {
    val profile = configuration.profile
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "What the profile does",
        subtitle = if (configuration.adaptations.isEmpty()) {
            configuration.summary
        } else {
            "Adapted: ${configuration.summary}"
        },
        subtitleColor = if (configuration.adaptations.isEmpty()) Color.Unspecified else MaterialTheme.colorScheme.tertiary,
        icon = Icons.Outlined.Info,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        configuration.adaptations.forEach { adaptation ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = adaptation,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Text(
            text = "ASSUMED means an emulator default, including disabled features. A source may establish one " +
                "part of a row while its remaining choices are unknown. Evidence below applies to the preset generation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ProfileAspect.entries.forEach { aspect ->
            AspectRow(
                aspect = aspect,
                value = aspectValue(configuration, aspect),
                provenance = if (when (aspect) {
                    ProfileAspect.ACCESS_CONTROL -> configuration.accessControl != profile.accessControl
                    ProfileAspect.PACE_CRYPTOGRAPHY -> configuration.pace != profile.pace || configuration.paceMapping != profile.paceMapping
                    ProfileAspect.ACTIVE_AUTHENTICATION -> configuration.activeAuthentication != profile.activeAuthentication
                    else -> false
                }) Provenance.ASSUMED else profile.provenanceOf(aspect)
            )
        }

        if (profile.evidence.isNotEmpty()) {
            Text(text = "Primary evidence for this generation", style = MaterialTheme.typography.titleSmall)
            profile.evidence.forEach { evidence ->
                Text(
                    text = "${evidence.provenance.displayName}: ${evidence.claim}",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { uriHandler.openUri(evidence.url) }) {
                    Text(
                        text = "${evidence.sourceTitle} · ${evidence.locator}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (profile.notes.isNotEmpty()) {
            Text(text = "Accuracy limits and emulator choices", style = MaterialTheme.typography.titleSmall)
            profile.notes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (profile.sources.isNotEmpty()) {
            Text(text = "Sources", style = MaterialTheme.typography.titleSmall)
            profile.sources.forEach { source ->
                Text(
                    text = "• $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = Provenance.entries.joinToString("\n") { "${it.displayName}: ${it.description}." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AspectRow(aspect: ProfileAspect, value: String, provenance: Provenance) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = aspect.displayName, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ProvenanceBadge(provenance)
    }
}

@Composable
private fun ProvenanceBadge(provenance: Provenance) {
    val (container, content) = when (provenance) {
        Provenance.SPECIFICATION -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Provenance.REPORTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Provenance.ASSUMED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.width(104.dp)) {
        Text(
            text = provenance.displayName,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** The part of the chip [aspect] covers, as the chip has it now. */
private fun aspectValue(configuration: ChipConfiguration, aspect: ProfileAspect): String {
    val profile = configuration.profile
    return when (aspect) {
        ProfileAspect.ACCESS_CONTROL -> configuration.accessControl.displayName
        ProfileAspect.PACE_CRYPTOGRAPHY -> if (configuration.supportsPace) {
            val pace = configuration.pace
            val real = profile.pace.curve.takeIf { it != pace.curve }?.let { " (preset curve: ${it.displayName})" } ?: ""
            "PACE-${configuration.paceMapping.abbreviation} on ${pace.curve.displayName}$real, ${pace.cipher.displayName}"
        } else {
            "PACE disabled in this configuration"
        }
        ProfileAspect.ACTIVE_AUTHENTICATION -> configuration.activeAuthentication?.displayName ?: "Disabled in this configuration"
        ProfileAspect.EXTENDED_ACCESS_CONTROL -> buildList {
            configuration.chipAuthentication?.let { add("Chip Authentication on ${it.curve.displayName}, ${it.cipher.displayName}") }
            if (configuration.terminalAuthentication) add("Terminal Authentication guards the fingerprints, which stay locked")
        }.ifEmpty { listOf("Disabled in this configuration") }.joinToString(". ")
        ProfileAspect.DATA_GROUPS -> configuration.dataGroups.joinToString { "DG$it" }
        ProfileAspect.SIGNATURE ->
            "${configuration.sod.signerKey.displayName} with ${configuration.sod.digestAlgorithm}, under ${configuration.sod.csca}"
        ProfileAspect.MRZ -> buildList {
            profile.mrz.documentCode?.let { add("Document code $it") }
            profile.mrz.documentNumberFormat?.let { add("Document numbers: $it") }
            if (profile.mrz.personalNumberInMrz) add("Personal number in the MRZ")
            add("Valid ${profile.validity.displayText}")
        }.joinToString(". ")
        ProfileAspect.ERROR_RESPONSES ->
            "EXTERNAL AUTHENTICATE with no challenge: " +
                configuration.errorResponses.outOfSequence.toString(16).uppercase().padStart(4, '0')
    }
}
