package com.hddev.smartemu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.NfcSimulatorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the passport simulator UI state and coordinating with the repository.
 * Handles passport data validation, simulation control, and NFC event management.
 */
class PassportSimulatorViewModel(
    private val repository: NfcSimulatorRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PassportSimulatorUiState.initial())
    val uiState: StateFlow<PassportSimulatorUiState> = _uiState.asStateFlow()
    
    init {
        initializeNfcStatus()
        observeSimulationStatus()
        observeNfcEvents()
    }
    
    /**
     * Updates the passport data and validates it.
     */
    fun updatePassportData(passportData: PassportData) {
        _uiState.value = _uiState.value.withPassportData(passportData)
    }
    
    /**
     * Updates individual passport fields.
     */
    fun updatePassportNumber(passportNumber: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(passportNumber = passportNumber))
    }
    
    fun updateDateOfBirth(dateOfBirth: kotlinx.datetime.LocalDate?) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(dateOfBirth = dateOfBirth))
    }
    
    fun updateExpiryDate(expiryDate: kotlinx.datetime.LocalDate?) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(expiryDate = expiryDate))
    }
    
    fun updateFirstName(firstName: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(firstName = firstName))
    }
    
    fun updateLastName(lastName: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(lastName = lastName))
    }
    
    fun updateGender(gender: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(gender = gender))
    }
    
    fun updateIssuingCountry(issuingCountry: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(issuingCountry = issuingCountry))
    }
    
    fun updateNationality(nationality: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(nationality = nationality))
    }
    
    /**
     * Starts the NFC passport simulation.
     */
    fun startSimulation() {
        val currentState = _uiState.value
        
        if (!currentState.canStartSimulation()) {
            _uiState.value = currentState.withError("Cannot start simulation in current state")
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.withLoading(true).withError(null)
            
            repository.startSimulation(currentState.passportData)
                .onSuccess {
                    // Status will be updated through the flow observer
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value
                        .withLoading(false)
                        .withError("Failed to start simulation: ${error.message}")
                }
        }
    }
    
    /**
     * Stops the NFC passport simulation.
     */
    fun stopSimulation() {
        val currentState = _uiState.value
        
        if (!currentState.canStopSimulation()) {
            _uiState.value = currentState.withError("Cannot stop simulation in current state")
            return
        }
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.withLoading(true).withError(null)
            
            repository.stopSimulation()
                .onSuccess {
                    // Status will be updated through the flow observer
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value
                        .withLoading(false)
                        .withError("Failed to stop simulation: ${error.message}")
                }
        }
    }
    
    /**
     * Requests NFC permissions from the user.
     */
    fun requestNfcPermissions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.withLoading(true).withError(null)
            
            repository.requestNfcPermissions()
                .onSuccess { granted ->
                    val currentState = _uiState.value
                    _uiState.value = currentState
                        .withNfcStatus(currentState.nfcAvailable, granted)
                        .withLoading(false)
                        .withError(if (!granted) "NFC permissions are required for simulation" else null)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value
                        .withLoading(false)
                        .withError("Failed to request permissions: ${error.message}")
                }
        }
    }
    
    /**
     * Clears all NFC events from the log.
     */
    fun clearEvents() {
        viewModelScope.launch {
            repository.clearEvents()
            _uiState.value = _uiState.value.withClearedEvents()
        }
    }
    
    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.withError(null)
    }
    
    /**
     * Refreshes the NFC status (availability and permissions).
     */
    fun refreshNfcStatus() {
        initializeNfcStatus()
    }
    
    /**
     * Initializes NFC availability and permission status.
     */
    private fun initializeNfcStatus() {
        viewModelScope.launch {
            val nfcAvailable = repository.isNfcAvailable().getOrElse { false }
            val hasPermissions = repository.hasNfcPermissions().getOrElse { false }
            
            _uiState.value = _uiState.value.withNfcStatus(nfcAvailable, hasPermissions)
            
            if (!nfcAvailable) {
                _uiState.value = _uiState.value.withError("NFC is not available on this device")
            } else if (!hasPermissions) {
                _uiState.value = _uiState.value.withError("NFC permissions are required for simulation")
            }
        }
    }
    
    /**
     * Observes simulation status changes from the repository.
     */
    private fun observeSimulationStatus() {
        repository.getSimulationStatus()
            .onEach { status ->
                _uiState.value = _uiState.value
                    .withSimulationStatus(status)
                    .withLoading(status == SimulationStatus.STARTING || status == SimulationStatus.STOPPING)
            }
            .catch { error ->
                _uiState.value = _uiState.value
                    .withError("Simulation status error: ${error.message}")
                    .withLoading(false)
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * Observes NFC events from the repository.
     */
    private fun observeNfcEvents() {
        repository.getNfcEvents()
            .onEach { event ->
                _uiState.value = _uiState.value.withNewEvent(event)
                
                // Handle error events
                if (event.type.isError()) {
                    _uiState.value = _uiState.value.withError(event.message)
                }
            }
            .catch { error ->
                _uiState.value = _uiState.value.withError("Event monitoring error: ${error.message}")
            }
            .launchIn(viewModelScope)
    }
}