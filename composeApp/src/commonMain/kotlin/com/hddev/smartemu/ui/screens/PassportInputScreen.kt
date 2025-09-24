package com.hddev.smartemu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.ui.components.PassportInputForm
import com.hddev.smartemu.ui.components.SimulationControlPanel
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/**
 * Main screen for passport input and simulation control.
 * Integrates the PassportInputForm with the ViewModel for complete functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportInputScreen(
    viewModel: PassportSimulatorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Text(
            text = "Passport NFC Simulator",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
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
            onResetForm = { viewModel.updatePassportData(PassportData.empty()) }
        )
    }
}