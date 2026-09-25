package com.hddev.smartemu.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.EmptyEventLog
import com.hddev.smartemu.ui.components.EventItem
import com.hddev.smartemu.ui.components.EventLogHeader
import com.hddev.smartemu.ui.components.SectionCard
import com.hddev.smartemu.ui.components.rememberLogExporter
import com.hddev.smartemu.ui.components.rememberPlatformDiagnostics
import com.hddev.smartemu.ui.navigation.AppDestination
import com.hddev.smartemu.ui.theme.successColor
import com.hddev.smartemu.utils.EventLogFormatter
import com.hddev.smartemu.utils.EventLogFormatter.Format
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * Starts and stops the emulation, lists what still stops it from starting, and shows the reader's activity,
 * which can be searched, copied and exported. The filter is hoisted so that it survives switching tabs;
 * [onShowMessage] reports the outcome of copying.
 */
@Composable
fun EmulatorScreen(
    uiState: PassportSimulatorUiState,
    viewModel: PassportSimulatorViewModel,
    selectedEventTypes: Set<NfcEventType>,
    onSelectedEventTypesChange: (Set<NfcEventType>) -> Unit,
    onNavigate: (AppDestination) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Oldest first, as exported; the list shows them reversed
    val matchingEvents = remember(uiState.nfcEvents, selectedEventTypes, searchQuery) {
        uiState.nfcEvents.filter { event ->
            (selectedEventTypes.isEmpty() || event.type in selectedEventTypes) && event.matches(searchQuery)
        }
    }
    val shownEvents = matchingEvents.asReversed()
    // Keyed by the event itself rather than its position, because new events push the others down
    var expandedEvents by remember { mutableStateOf(setOf<NfcEvent>()) }

    val exporter = rememberLogExporter()
    val platform = rememberPlatformDiagnostics()
    val reportContext = { EventLogFormatter.ReportContext(uiState, platform, selectedEventTypes, searchQuery) }
    val copy: (String, String) -> Unit = { label, text ->
        onShowMessage(if (exporter.copy(label, text)) "$label copied" else "The log is too long to copy; export it instead")
    }
    val copyReport: (Format) -> Unit = { format ->
        copy("Event log", EventLogFormatter.format(format, matchingEvents, reportContext()))
    }
    val exportReport: (Format, Boolean) -> Unit = { format, share ->
        val context = reportContext()
        val fileName = EventLogFormatter.fileName(context.generatedAt, format.extension)
        val text = EventLogFormatter.format(format, matchingEvents, context)
        if (share) exporter.share(fileName, format.mimeType, text) else exporter.save(fileName, format.mimeType, text)
    }

    // Centred and no wider than the form screens, so that the log stays readable on tablets and in landscape
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                EmulationStatusCard(
                    uiState = uiState,
                    onStart = viewModel::startSimulation,
                    onStop = viewModel::stopSimulation
                )
            }

            if (!uiState.simulationStatus.isActiveOrStarting()) {
                item {
                    ReadinessSection(
                        uiState = uiState,
                        onRequestPermissions = viewModel::requestNfcPermissions,
                        onNavigate = onNavigate
                    )
                }
            }

            item {
                EventLogHeader(
                    shownEventCount = shownEvents.size,
                    totalEventCount = uiState.nfcEvents.size,
                    selectedEventTypes = selectedEventTypes,
                    onSelectedEventTypesChange = onSelectedEventTypesChange,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onCopy = { copyReport(Format.TEXT) },
                    onShare = { exportReport(it, true) },
                    onSave = { exportReport(it, false) },
                    onCopyJson = { copyReport(Format.JSON) },
                    onClearEvents = {
                        expandedEvents = emptySet()
                        viewModel.clearEvents()
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (shownEvents.isEmpty()) {
                item { EmptyEventLog(filtered = uiState.nfcEvents.isNotEmpty()) }
            } else {
                items(shownEvents) { event ->
                    EventItem(
                        event = event,
                        expanded = event in expandedEvents,
                        onToggleExpanded = {
                            expandedEvents = if (event in expandedEvents) expandedEvents - event else expandedEvents + event
                        },
                        onCopy = { copy("Event", EventLogFormatter.eventText(event)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmulationStatusCard(
    uiState: PassportSimulatorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val status = uiState.simulationStatus
    val canStart = uiState.canStartSimulation()
    val (containerColor, contentColor) = when (status) {
        SimulationStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SimulationStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    val (title, body) = when (status) {
        SimulationStatus.ACTIVE -> "Emulating the passport" to
            "Hold the reader against the back of this phone, near the NFC antenna, and keep the screen on."
        SimulationStatus.STARTING -> "Starting" to "Preparing the chip"
        SimulationStatus.STOPPING -> "Stopping" to "Readers will no longer find the chip"
        SimulationStatus.ERROR -> "The emulation stopped" to "See the reader activity below, then start again."
        SimulationStatus.STOPPED -> if (canStart) {
            "Ready to emulate" to "Start, then hold a passport reader against the back of this phone."
        } else {
            "Not ready yet" to "Complete the checklist below, then start."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusBadge(status = status, ready = canStart)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (uiState.passportData.isValid()) {
                PassportSummary(passportData = uiState.passportData)
            }

            if (status.isActiveOrStarting() || status == SimulationStatus.STOPPING) {
                AppButton(
                    text = "Stop emulation",
                    onClick = onStop,
                    icon = Icons.Filled.Stop,
                    emphasis = ButtonEmphasis.High,
                    destructive = true,
                    enabled = uiState.canStopSimulation(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                AppButton(
                    text = "Start emulation",
                    onClick = onStart,
                    icon = Icons.Filled.PlayArrow,
                    emphasis = ButtonEmphasis.High,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * The document number, holder and chip settings being emulated, as a single line of chips.
 */
@Composable
private fun PassportSummary(passportData: PassportData) {
    val pace = passportData.accessControl.supportsPace
    val labels = buildList {
        add(passportData.passportNumber.uppercase())
        add("${passportData.lastName.uppercase()}, ${passportData.firstName.uppercase()}")
        add(passportData.accessControl.displayName)
        if (pace) add("PACE-${passportData.paceMapping.abbreviation}")
        if (pace && passportData.hasCan()) add("CAN ${passportData.can}")
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .background(LocalContentColor.current.copy(alpha = 0.08f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun StatusBadge(status: SimulationStatus, ready: Boolean) {
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        when {
            status.isTransitioning() -> CircularProgressIndicator(modifier = Modifier.size(40.dp))
            status == SimulationStatus.ERROR -> Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            status == SimulationStatus.ACTIVE -> {
                val transition = rememberInfiniteTransition(label = "emulating")
                val pulse by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
                    label = "pulse"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(pulse)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                )
                ContactlessSymbol(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            else -> ContactlessSymbol(
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Three widening arcs, the usual symbol for contactless communication.
 */
@Composable
private fun ContactlessSymbol(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val origin = Offset(size.width * 0.15f, size.height / 2)
        listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
            val radius = size.minDimension * fraction
            drawArc(
                color = color,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(origin.x - radius, origin.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Everything the emulation needs, each with a way to fix it if it's missing, with a count of what's missing in its
 * title. Open at first while something is missing, so that nobody has to hunt for why Start is disabled.
 */
@Composable
private fun ReadinessSection(
    uiState: PassportSimulatorUiState,
    onRequestPermissions: () -> Unit,
    onNavigate: (AppDestination) -> Unit
) {
    // Computed here rather than read from the UI state, which has no errors until the first edit
    val errors = uiState.passportData.getValidationErrors()
    val passportErrorCount = errors.keys.count { it != "can" }
    val canError = errors["can"]
    val done = listOf(uiState.nfcAvailable, uiState.hasNfcPermission, passportErrorCount == 0, canError == null)
    val missing = done.count { !it }
    var expanded by rememberSaveable { mutableStateOf(missing > 0) }

    SectionCard(
        title = "Checklist",
        subtitle = when (missing) {
            0 -> "All ${done.size} items done"
            1 -> "1 of ${done.size} items needs attention"
            else -> "$missing of ${done.size} items need attention"
        },
        subtitleColor = if (missing > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
        icon = Icons.Outlined.Checklist,
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        ReadinessRow(
            label = "NFC",
            ok = uiState.nfcAvailable,
            detail = if (uiState.nfcAvailable) "Available" else "This device has no NFC, or it's turned off"
        )
        ReadinessRow(
            label = "NFC permission",
            ok = uiState.hasNfcPermission,
            detail = if (uiState.hasNfcPermission) "Granted" else "Needed to answer readers",
            action = if (uiState.nfcAvailable && !uiState.hasNfcPermission) {
                { AppButton(text = "Grant", onClick = onRequestPermissions, emphasis = ButtonEmphasis.Low, enabled = !uiState.isLoading) }
            } else {
                null
            }
        )
        ReadinessRow(
            label = "Passport details",
            ok = passportErrorCount == 0,
            detail = when (passportErrorCount) {
                0 -> "Complete"
                1 -> "1 field needs attention"
                else -> "$passportErrorCount fields need attention"
            },
            action = if (passportErrorCount > 0) {
                { AppButton(text = "Edit", onClick = { onNavigate(AppDestination.PASSPORT) }, emphasis = ButtonEmphasis.Low) }
            } else {
                null
            }
        )
        ReadinessRow(
            label = "Chip security",
            ok = canError == null,
            detail = canError ?: uiState.passportData.accessControl.displayName,
            action = if (canError != null) {
                { AppButton(text = "Edit", onClick = { onNavigate(AppDestination.CHIP) }, emphasis = ButtonEmphasis.Low) }
            } else {
                null
            }
        )
    }
}

@Composable
private fun ReadinessRow(
    label: String,
    ok: Boolean,
    detail: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.successColor else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.invoke()
    }
}

/**
 * Whether the event's type, message or details contain [query], ignoring case; everything matches a blank query.
 */
private fun NfcEvent.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return message.contains(needle, ignoreCase = true) ||
        type.getDescription().contains(needle, ignoreCase = true) ||
        details.any { (key, value) -> key.contains(needle, ignoreCase = true) || value.toString().contains(needle, ignoreCase = true) }
}
