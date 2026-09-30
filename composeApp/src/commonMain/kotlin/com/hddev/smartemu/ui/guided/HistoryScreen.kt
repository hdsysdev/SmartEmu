package com.hddev.smartemu.ui.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.ReadRecord
import com.hddev.smartemu.ui.components.rememberLogExporter
import com.hddev.smartemu.ui.theme.successColor
import com.hddev.smartemu.utils.ReadHistoryFormatter
import kotlin.time.Clock

/**
 * Every time another phone read this one, newest first: when, how it went, and what it read, in plain words.
 * The list can be shared, say with whoever looks after the app being tried, or cleared.
 */
@Composable
fun HistoryScreen(records: List<ReadRecord>, onClear: () -> Unit, onBack: () -> Unit) {
    val exporter = rememberLogExporter()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    GuidedScaffold(
        title = "Past reads",
        onBack = onBack,
        actions = {
            if (records.isNotEmpty()) {
                IconButton(onClick = {
                    exporter.share(
                        ReadHistoryFormatter.fileName(Clock.System.now()),
                        "text/plain",
                        ReadHistoryFormatter.text(records)
                    )
                }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear all")
                }
            }
        }
    ) {
        if (records.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Headline(
                    title = "No reads yet",
                    text = "Each time another phone reads this one, you'll see here how it went."
                )
            }
        } else {
            GuidedCard {
                records.forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HistoryRow(record)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear past reads?") },
            text = { Text("This removes all ${records.size} from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HistoryRow(record: ReadRecord) {
    val succeeded = record.outcome.succeeded
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (succeeded) MaterialTheme.successColor else MaterialTheme.colorScheme.error
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.outcome.friendlyName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = ReadHistoryFormatter.dateTime(record.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${record.holder}'s ${record.documentType.noun}" +
                    if (record.chipFault != ChipFault.NONE) ", set as a fake: ${record.chipFault.friendlyName.lowercase()}" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = whatWasRead(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** What the other phone got, in the words of the reading steps. */
private fun whatWasRead(record: ReadRecord): String {
    val parts = buildList {
        if ("EF.DG1" in record.filesRead) add("details")
        if ("EF.DG2" in record.filesRead) add("photo")
        if ("EF.SOD" in record.filesRead) add("signature")
        if (record.activeAuthentication) add("anti-copy check")
    }
    return if (parts.isEmpty()) "Nothing was read." else "Read the ${parts.joinToString()}."
}
