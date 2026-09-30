package com.hddev.smartemu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPreset
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.PresetGroup

/**
 * The ready-made documents under their group headings, for a lazy list. [summary] is the line under each title;
 * choosing one calls [onSelected].
 */
fun LazyListScope.presetItems(
    summary: (PassportPreset) -> String,
    groupTitle: (PresetGroup) -> String = { it.title },
    onSelected: (PassportPreset) -> Unit
) {
    PassportPresets.all.groupBy { it.group }.forEach { (group, presets) ->
        item(key = "group-${group.name}") {
            Text(
                text = groupTitle(group),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            )
        }
        items(presets, key = { it.id }) { preset ->
            PresetRow(preset = preset, summary = summary(preset), onClick = { onSelected(preset) })
        }
    }
}

@Composable
internal fun PresetRow(preset: PassportPreset, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = preset.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A dialog listing the ready-made documents with what each sets up, in developer mode. Choosing one calls
 * [onSelected], which replaces the passport details and chip settings; the portrait and CAN stay.
 */
@Composable
fun PresetDialog(
    today: kotlinx.datetime.LocalDate,
    onSelected: (PassportPreset) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
        ) {
            Column {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = "Load a preset", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Replaces the document details and chip settings. The portrait and CAN are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    presetItems(
                        summary = { preset -> "${preset.description} · ${preset.build(today).technicalSummary()}" },
                        onSelected = { preset ->
                            onSelected(preset)
                            onDismiss()
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** What a document sets up, in a reader tester's terms: its format, access control, and any extras or fault. */
internal fun PassportData.technicalSummary(): String = buildList {
    add("${documentType.formatName} ${documentType.documentCode}")
    add(issuingCountry)
    add(
        when (accessControl) {
            AccessControl.BAC_ONLY -> "BAC"
            AccessControl.PACE_ONLY -> "PACE-${paceMapping.abbreviation}"
            AccessControl.BAC_AND_PACE -> "BAC + PACE-${paceMapping.abbreviation}"
        }
    )
    if (activeAuthentication) add("AA")
    if (chipFault != ChipFault.NONE) add(chipFault.displayName)
}.joinToString(" · ")
