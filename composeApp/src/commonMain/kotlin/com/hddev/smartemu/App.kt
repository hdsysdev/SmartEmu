package com.hddev.smartemu

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.ui.components.PassportFullScreenDialog
import com.hddev.smartemu.ui.navigation.AppDestination
import com.hddev.smartemu.ui.navigation.PlatformBackHandler
import com.hddev.smartemu.ui.screens.ChipScreen
import com.hddev.smartemu.ui.screens.EmulatorScreen
import com.hddev.smartemu.ui.screens.PassportScreen
import com.hddev.smartemu.ui.theme.SmartEmuTheme
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.launch

/** Windows at least this wide show a navigation rail instead of a bottom navigation bar. */
private val RailMinWidth = 600.dp

/**
 * Root of the app: one screen per [AppDestination], switched with a navigation bar, or a rail on wide windows.
 * Errors from the ViewModel appear as snackbars.
 */
@Composable
fun App(
    viewModel: PassportSimulatorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(AppDestination.start) }
    var showDataPage by rememberSaveable { mutableStateOf(false) }
    var selectedEventTypes by remember { mutableStateOf(setOf<NfcEventType>()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }
    // Keeps each screen's scroll position while another is shown
    val screenStateHolder = rememberSaveableStateHolder()

    // Back returns to the start destination before it leaves the app
    PlatformBackHandler(enabled = destination != AppDestination.start) {
        destination = AppDestination.start
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = message, actionLabel = "Dismiss", duration = SnackbarDuration.Long)
        viewModel.clearError()
    }

    SmartEmuTheme {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val useRail = maxWidth >= RailMinWidth

            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail) {
                    NavigationRail {
                        Spacer(modifier = Modifier.weight(1f))
                        AppDestination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { DestinationIcon(item, selected = destination == item, uiState = uiState) },
                                label = { Text(item.label) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Scaffold(
                    modifier = Modifier.weight(1f),
                    topBar = {
                        AppTopBar(
                            destination = destination,
                            uiState = uiState,
                            onFillSampleData = viewModel::autofillPassportData,
                            onClearPassportDetails = viewModel::clearPassportDetails,
                            onOpenEmulator = { destination = AppDestination.EMULATOR }
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                AppDestination.entries.forEach { item ->
                                    NavigationBarItem(
                                        selected = destination == item,
                                        onClick = { destination = item },
                                        icon = { DestinationIcon(item, selected = destination == item, uiState = uiState) },
                                        label = { Text(item.label) }
                                    )
                                }
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    val screenModifier = Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)

                    Crossfade(targetState = destination, label = "destination") { shown ->
                        screenStateHolder.SaveableStateProvider(shown.name) {
                            when (shown) {
                                AppDestination.PASSPORT -> PassportScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onOpenDataPage = { showDataPage = true },
                                    onNext = { destination = AppDestination.CHIP },
                                    onShowMessage = showMessage,
                                    modifier = screenModifier
                                )
                                AppDestination.CHIP -> ChipScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onNext = { destination = AppDestination.EMULATOR },
                                    modifier = screenModifier
                                )
                                AppDestination.EMULATOR -> EmulatorScreen(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    selectedEventTypes = selectedEventTypes,
                                    onSelectedEventTypesChange = { selectedEventTypes = it },
                                    onNavigate = { destination = it },
                                    onShowMessage = showMessage,
                                    modifier = screenModifier
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDataPage) {
            PassportFullScreenDialog(
                passportData = uiState.passportData,
                onDismiss = { showDataPage = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    destination: AppDestination,
    uiState: PassportSimulatorUiState,
    onFillSampleData: () -> Unit,
    onClearPassportDetails: () -> Unit,
    onOpenEmulator: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(text = destination.title, maxLines = 1)
                // Tells a newcomer that the screens are steps, taken in the order of the navigation bar
                Text(
                    text = "Step ${destination.ordinal + 1} of ${AppDestination.entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            // Where the emulation is running, or failed, from the screens that don't show it
            if (destination != AppDestination.EMULATOR && uiState.simulationStatus != SimulationStatus.STOPPED) {
                EmulationStatusChip(status = uiState.simulationStatus, onClick = onOpenEmulator)
            }
            if (destination == AppDestination.PASSPORT) {
                PassportMenu(
                    enabled = uiState.isPassportFormEnabled(),
                    onFillSampleData = onFillSampleData,
                    onClearPassportDetails = onClearPassportDetails
                )
            }
        }
    )
}

@Composable
private fun PassportMenu(
    enabled: Boolean,
    onFillSampleData: () -> Unit,
    onClearPassportDetails: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Fill with sample data") },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onFillSampleData()
                }
            )
            DropdownMenuItem(
                text = { Text("Clear passport details") },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onClearPassportDetails()
                }
            )
        }
    }
}

@Composable
private fun EmulationStatusChip(
    status: SimulationStatus,
    onClick: () -> Unit
) {
    val failed = status == SimulationStatus.ERROR
    val label = when (status) {
        SimulationStatus.ACTIVE -> "Emulating"
        SimulationStatus.STARTING -> "Starting"
        SimulationStatus.STOPPING -> "Stopping"
        SimulationStatus.ERROR -> "Emulation failed"
        SimulationStatus.STOPPED -> "Stopped"
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Badge(containerColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.padding(end = 4.dp)
    )
}

/**
 * The destination's icon, filled while [selected], with a badge when it needs attention: validation errors on the
 * form screens, or the emulation's state on the emulator.
 */
@Composable
private fun DestinationIcon(
    destination: AppDestination,
    selected: Boolean,
    uiState: PassportSimulatorUiState
) {
    val icon = if (selected) destination.selectedIcon else destination.icon
    // The UI state has no errors until the first edit, so a fresh form has no badge
    val errors = uiState.validationErrors
    val badge: (@Composable BoxScope.() -> Unit)? = when (destination) {
        AppDestination.PASSPORT -> errors.keys.count { it != "can" }.takeIf { it > 0 }?.let { count ->
            { Badge { Text(count.toString()) } }
        }
        AppDestination.CHIP -> if ("can" in errors) ({ Badge() }) else null
        AppDestination.EMULATOR -> when (uiState.simulationStatus) {
            SimulationStatus.ACTIVE -> ({ Badge(containerColor = MaterialTheme.colorScheme.primary) })
            SimulationStatus.ERROR -> ({ Badge() })
            else -> null
        }
    }

    if (badge != null) {
        BadgedBox(badge = badge) {
            Icon(imageVector = icon, contentDescription = null)
        }
    } else {
        Icon(imageVector = icon, contentDescription = null)
    }
}
