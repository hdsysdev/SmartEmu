package com.hddev.smartemu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.SimulationStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Compose UI component for displaying real-time NFC events with filtering and clearing functionality.
 * Shows scrollable event list with timestamps and connection status indicators.
 */
@Composable
fun EventLogDisplay(
    events: List<NfcEvent>,
    simulationStatus: SimulationStatus,
    isConnected: Boolean,
    selectedEventTypes: Set<NfcEventType>,
    onEventTypeFilterChanged: (NfcEventType, Boolean) -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredEvents = remember(events, selectedEventTypes) {
        if (selectedEventTypes.isEmpty()) {
            events
        } else {
            events.filter { it.type in selectedEventTypes }
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with connection status
            EventLogHeader(
                simulationStatus = simulationStatus,
                isConnected = isConnected,
                eventCount = filteredEvents.size,
                totalEventCount = events.size,
                onClearEvents = onClearEvents
            )
            
            // Event type filters
            EventTypeFilters(
                selectedEventTypes = selectedEventTypes,
                onEventTypeFilterChanged = onEventTypeFilterChanged
            )
            
            // Event list
            EventList(
                events = filteredEvents,
                modifier = Modifier.height(300.dp)
            )
        }
    }
}

@Composable
private fun EventLogHeader(
    simulationStatus: SimulationStatus,
    isConnected: Boolean,
    eventCount: Int,
    totalEventCount: Int,
    onClearEvents: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Event Log",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection status indicator
                ConnectionStatusIndicator(
                    isConnected = isConnected,
                    simulationStatus = simulationStatus
                )
                
                // Event count
                Text(
                    text = if (eventCount == totalEventCount) {
                        "$eventCount events"
                    } else {
                        "$eventCount of $totalEventCount events"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Clear button
        OutlinedButton(
            onClick = onClearEvents,
            enabled = totalEventCount > 0,
            modifier = Modifier.size(width = 80.dp, height = 36.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Clear",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    isConnected: Boolean,
    simulationStatus: SimulationStatus
) {
    val (icon, color, text) = when {
        simulationStatus != SimulationStatus.ACTIVE -> Triple(
            Icons.Filled.Clear,
            MaterialTheme.colorScheme.outline,
            "Inactive"
        )
        isConnected -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Connected"
        )
        else -> Triple(
            Icons.Filled.Clear,
            MaterialTheme.colorScheme.secondary,
            "Listening"
        )
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EventTypeFilters(
    selectedEventTypes: Set<NfcEventType>,
    onEventTypeFilterChanged: (NfcEventType, Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Filters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            TextButton(
                onClick = { isExpanded = !isExpanded },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isExpanded) "Hide" else "Show",
                    style = MaterialTheme.typography.bodySmall
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Select All / None buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            NfcEventType.values().forEach { eventType ->
                                if (eventType !in selectedEventTypes) {
                                    onEventTypeFilterChanged(eventType, true)
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "All",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    TextButton(
                        onClick = {
                            selectedEventTypes.forEach { eventType ->
                                onEventTypeFilterChanged(eventType, false)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "None",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                // Event type checkboxes
                NfcEventType.values().forEach { eventType ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = eventType in selectedEventTypes,
                            onCheckedChange = { isChecked ->
                                onEventTypeFilterChanged(eventType, isChecked)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        
                        Icon(
                            imageVector = getEventTypeIcon(eventType),
                            contentDescription = null,
                            tint = getEventTypeColor(eventType),
                            modifier = Modifier.size(16.dp)
                        )
                        
                        Text(
                            text = eventType.getDescription(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventList(
    events: List<NfcEvent>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new events are added
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No events yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Start simulation to see NFC events",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(events) { event ->
                    EventItem(event = event)
                }
            }
        }
    }
}

@Composable
private fun EventItem(
    event: NfcEvent
) {
    val backgroundColor = getEventTypeColor(event.type).copy(alpha = 0.1f)
    val borderColor = getEventTypeColor(event.type).copy(alpha = 0.3f)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Event type icon
        Icon(
            imageVector = getEventTypeIcon(event.type),
            contentDescription = null,
            tint = getEventTypeColor(event.type),
            modifier = Modifier.size(16.dp)
        )
        
        // Event content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Timestamp and event type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = event.type.getDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = getEventTypeColor(event.type)
                )
                
                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Event message
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Event details (if any)
            if (event.details.isNotEmpty()) {
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
    }
}

@Composable
private fun getEventTypeIcon(eventType: NfcEventType): ImageVector {
    return when (eventType) {
        NfcEventType.CONNECTION_ESTABLISHED -> Icons.Filled.Phone
        NfcEventType.BAC_AUTHENTICATION_REQUEST -> Icons.Filled.Lock
        NfcEventType.PACE_AUTHENTICATION_REQUEST -> Icons.Filled.Lock
        NfcEventType.AUTHENTICATION_SUCCESS -> Icons.Filled.CheckCircle
        NfcEventType.AUTHENTICATION_FAILURE -> Icons.Filled.Close
        NfcEventType.CONNECTION_LOST -> Icons.Filled.Clear
        NfcEventType.ERROR -> Icons.Filled.Warning
    }
}

@Composable
private fun getEventTypeColor(eventType: NfcEventType): Color {
    return when (eventType) {
        NfcEventType.CONNECTION_ESTABLISHED -> MaterialTheme.colorScheme.primary
        NfcEventType.BAC_AUTHENTICATION_REQUEST -> MaterialTheme.colorScheme.secondary
        NfcEventType.PACE_AUTHENTICATION_REQUEST -> MaterialTheme.colorScheme.tertiary
        NfcEventType.AUTHENTICATION_SUCCESS -> Color(0xFF4CAF50) // Green
        NfcEventType.AUTHENTICATION_FAILURE -> MaterialTheme.colorScheme.error
        NfcEventType.CONNECTION_LOST -> MaterialTheme.colorScheme.outline
        NfcEventType.ERROR -> Color(0xFFFF9800) // Orange
    }
}

private fun formatTimestamp(timestamp: Instant): String {
    val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.hour.toString().padStart(2, '0')}:" +
           "${localDateTime.minute.toString().padStart(2, '0')}:" +
           "${localDateTime.second.toString().padStart(2, '0')}"
}