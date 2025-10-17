package com.hddev.smartemu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.ui.components.EventLogDisplay
import com.hddev.smartemu.ui.components.PassportInputForm
import com.hddev.smartemu.ui.components.SimulationControlPanel
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * Main application composable that combines all UI components.
 * Provides proper state management, data flow, and error boundary handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    viewModel: PassportSimulatorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedEventTypes by remember { mutableStateOf(setOf<NfcEventType>()) }
    
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // App Header
                AppHeader()
                
                // Error Display (if any)
                ErrorDisplay(
                    errorMessage = uiState.errorMessage,
                    onDismiss = viewModel::clearError
                )
                
                // Simulation Control Panel
                SimulationControlPanel(
                    simulationStatus = uiState.simulationStatus,
                    nfcAvailable = uiState.nfcAvailable,
                    hasNfcPermission = uiState.hasNfcPermission,
                    canStartSimulation = uiState.canStartSimulation(),
                    canStopSimulation = uiState.canStopSimulation(),
                    isLoading = uiState.isLoading,
                    errorMessage = uiState.errorMessage,
                    onStartSimulation = viewModel::startSimulation,
                    onStopSimulation = viewModel::stopSimulation,
                    onRequestPermissions = viewModel::requestNfcPermissions,
                    onClearError = viewModel::clearError
                )
                
                // Passport Input Form
                PassportInputForm(
                    passportData = uiState.passportData,
                    validationErrors = uiState.validationErrors,
                    enabled = uiState.isPassportFormEnabled(),
                    onPassportNumberChange = viewModel::updatePassportNumber,
                    onFirstNameChange = viewModel::updateFirstName,
                    onLastNameChange = viewModel::updateLastName,
                    onDateOfBirthChange = viewModel::updateDateOfBirth,
                    onExpiryDateChange = viewModel::updateExpiryDate,
                    onGenderChange = viewModel::updateGender,
                    onIssuingCountryChange = viewModel::updateIssuingCountry,
                    onNationalityChange = viewModel::updateNationality,
                    onResetForm = { viewModel.updatePassportData(com.hddev.smartemu.data.PassportData.empty()) }
                )
                
                // Event Log Display
                EventLogDisplay(
                    events = uiState.nfcEvents,
                    simulationStatus = uiState.simulationStatus,
                    isConnected = uiState.isSimulationActive(),
                    selectedEventTypes = selectedEventTypes,
                    onEventTypeFilterChanged = { eventType, isSelected ->
                        selectedEventTypes = if (isSelected) {
                            selectedEventTypes + eventType
                        } else {
                            selectedEventTypes - eventType
                        }
                    },
                    onClearEvents = viewModel::clearEvents
                )
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Passport NFC Simulator",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Emulate a passport NFC chip for testing BAC and PACE protocols",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorDisplay(
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    if (errorMessage != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(
                        text = "Dismiss",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}