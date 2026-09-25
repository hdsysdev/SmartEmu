package com.hddev.smartemu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.isFailedApdu
import com.hddev.smartemu.ui.theme.successColor
import com.hddev.smartemu.utils.EventLogFormatter.Format
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Title, event count, search, an overflow menu of the copy, export and clear actions, and type filters above the
 * list of events. An empty [selectedEventTypes] shows every type. Copying and exporting cover the events shown.
 */
@Composable
fun EventLogHeader(
    shownEventCount: Int,
    totalEventCount: Int,
    selectedEventTypes: Set<NfcEventType>,
    onSelectedEventTypesChange: (Set<NfcEventType>) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: (Format) -> Unit,
    onSave: (Format) -> Unit,
    onCopyJson: () -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searching by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    // Set when the user opens the search, so the field takes focus then but not when it reappears after a tab switch
    var focusSearch by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Reader activity", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        totalEventCount == 0 -> "No events"
                        shownEventCount == totalEventCount -> "$totalEventCount events, newest first"
                        else -> "$shownEventCount of $totalEventCount events, newest first"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    if (searching) onSearchQueryChange("")
                    searching = !searching
                    focusSearch = searching
                }
            ) {
                Icon(
                    imageVector = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searching) "Close search" else "Search events"
                )
            }
            EventLogMenu(
                hasShownEvents = shownEventCount > 0,
                hasEvents = totalEventCount > 0,
                onCopy = onCopy,
                onShare = onShare,
                onSave = onSave,
                onCopyJson = onCopyJson,
                onClearEvents = onClearEvents
            )
        }

        if (searching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester),
                singleLine = true,
                placeholder = { Text("Search, e.g. 6982 or SELECT") },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) }
            )
            LaunchedEffect(focusSearch) {
                if (focusSearch) {
                    searchFocusRequester.requestFocus()
                    focusSearch = false
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedEventTypes.isEmpty(),
                onClick = { onSelectedEventTypesChange(emptySet()) },
                label = { Text("All") }
            )
            NfcEventType.entries.forEach { type ->
                val selected = type in selectedEventTypes
                FilterChip(
                    selected = selected,
                    onClick = { onSelectedEventTypesChange(if (selected) selectedEventTypes - type else selectedEventTypes + type) },
                    label = { Text(type.shortLabel) },
                    leadingIcon = {
                        Icon(
                            imageVector = type.icon,
                            contentDescription = null,
                            tint = eventTypeColor(type),
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    }
}

/**
 * The log's actions in one menu, grouped as copying, sharing with another app, saving to a file, and clearing.
 * Copying, sharing and saving need [hasShownEvents]; clearing needs [hasEvents].
 */
@Composable
private fun EventLogMenu(
    hasShownEvents: Boolean,
    hasEvents: Boolean,
    onCopy: () -> Unit,
    onShare: (Format) -> Unit,
    onSave: (Format) -> Unit,
    onCopyJson: () -> Unit,
    onClearEvents: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = hasEvents) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Copy, export or clear the log")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { label, icon, enabled, onClick ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onClick()
                    }
                )
            }
            item("Copy as text", Icons.Outlined.ContentCopy, hasShownEvents, onCopy)
            item("Copy as JSON", Icons.Outlined.ContentCopy, hasShownEvents, onCopyJson)
            HorizontalDivider()
            item("Share as text", Icons.Filled.Share, hasShownEvents) { onShare(Format.TEXT) }
            item("Share as JSON", Icons.Filled.Share, hasShownEvents) { onShare(Format.JSON) }
            HorizontalDivider()
            item("Save text file…", Icons.Outlined.Save, hasShownEvents) { onSave(Format.TEXT) }
            item("Save JSON file…", Icons.Outlined.Save, hasShownEvents) { onSave(Format.JSON) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Clear log", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                enabled = hasEvents,
                onClick = {
                    expanded = false
                    onClearEvents()
                }
            )
        }
    }
}

/**
 * One event of the log. Events with details show them when [expanded]; tapping the event calls [onToggleExpanded].
 * Long-pressing it, or its Copy button when expanded, calls [onCopy].
 */
@Composable
fun EventItem(
    event: NfcEvent,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    // An APDU the chip refused stands out from the successful ones around it
    val color = if (event.isFailedApdu()) MaterialTheme.colorScheme.error else eventTypeColor(event.type)
    val hasDetails = event.details.isNotEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (hasDetails) (if (expanded) "Hide details" else "Show details") else null,
                    onLongClickLabel = "Copy event",
                    onClick = { if (hasDetails) onToggleExpanded() },
                    onLongClick = onCopy
                )
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = event.type.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.type.getDescription(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatTimestamp(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AnimatedVisibility(visible = expanded && hasDetails) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                event.details.forEach { (key, value) ->
                                    Text(
                                        text = "$key: $value",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        AppButton(
                            text = "Copy event",
                            onClick = onCopy,
                            icon = Icons.Outlined.ContentCopy,
                            emphasis = ButtonEmphasis.Low
                        )
                    }
                }
            }

            if (hasDetails) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Placeholder for the list while it has no events.
 */
@Composable
fun EmptyEventLog(
    filtered: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = if (filtered) "No events match the filters or search" else "No reader activity yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!filtered) {
            Text(
                text = "Start the emulation and hold a reader to this phone. Every command the reader sends appears here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val NfcEventType.shortLabel: String
    get() = when (this) {
        NfcEventType.CONNECTION_ESTABLISHED -> "Connected"
        NfcEventType.BAC_AUTHENTICATION_REQUEST -> "BAC"
        NfcEventType.PACE_AUTHENTICATION_REQUEST -> "PACE"
        NfcEventType.AUTHENTICATION_SUCCESS -> "Success"
        NfcEventType.AUTHENTICATION_FAILURE -> "Failure"
        NfcEventType.CONNECTION_LOST -> "Disconnected"
        NfcEventType.ERROR -> "Error"
        NfcEventType.APDU -> "APDU"
    }

private val NfcEventType.icon: ImageVector
    get() = when (this) {
        NfcEventType.CONNECTION_ESTABLISHED -> Icons.Filled.Phone
        NfcEventType.BAC_AUTHENTICATION_REQUEST -> Icons.Filled.Lock
        NfcEventType.PACE_AUTHENTICATION_REQUEST -> Icons.Filled.Lock
        NfcEventType.AUTHENTICATION_SUCCESS -> Icons.Filled.CheckCircle
        NfcEventType.AUTHENTICATION_FAILURE -> Icons.Filled.Close
        NfcEventType.CONNECTION_LOST -> Icons.Filled.Clear
        NfcEventType.ERROR -> Icons.Filled.Warning
        NfcEventType.APDU -> Icons.AutoMirrored.Filled.Send
    }

@Composable
private fun eventTypeColor(eventType: NfcEventType): Color {
    return when (eventType) {
        NfcEventType.CONNECTION_ESTABLISHED -> MaterialTheme.colorScheme.primary
        NfcEventType.BAC_AUTHENTICATION_REQUEST -> MaterialTheme.colorScheme.secondary
        NfcEventType.PACE_AUTHENTICATION_REQUEST -> MaterialTheme.colorScheme.secondary
        NfcEventType.AUTHENTICATION_SUCCESS -> MaterialTheme.successColor
        NfcEventType.AUTHENTICATION_FAILURE -> MaterialTheme.colorScheme.error
        NfcEventType.CONNECTION_LOST -> MaterialTheme.colorScheme.outline
        NfcEventType.ERROR -> MaterialTheme.colorScheme.tertiary
        NfcEventType.APDU -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatTimestamp(timestamp: Instant): String {
    val time = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.hour.toString().padStart(2, '0')}:" +
           "${time.minute.toString().padStart(2, '0')}:" +
           "${time.second.toString().padStart(2, '0')}." +
           (time.nanosecond / 1_000_000).toString().padStart(3, '0')
}
