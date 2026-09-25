package com.hddev.smartemu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel for managing the passport simulator UI state and coordinating with the repository.
 * Handles passport data validation, simulation control, and NFC event management.
 * The passport is restored from [passportStore] on creation and saved back to it as it changes.
 */
class PassportSimulatorViewModel(
    private val repository: NfcSimulatorRepository,
    private val passportStore: PassportStore
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PassportSimulatorUiState.initial())
    val uiState: StateFlow<PassportSimulatorUiState> = _uiState.asStateFlow()
    
    init {
        initializeNfcStatus()
        observeSimulationStatus()
        observeNfcEvents()
        restoreAndSavePassportData()
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
    
    fun updateAccessControl(accessControl: AccessControl) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(accessControl = accessControl))
    }
    
    fun updatePaceMapping(paceMapping: PaceMapping) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(paceMapping = paceMapping))
    }
    
    /**
     * Updates the Card Access Number, keeping only the digits a CAN can have.
     */
    fun updateCan(can: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(can = can.filter { it in '0'..'9' }.take(PassportData.CAN_LENGTH)))
    }
    
    /**
     * Sets a random Card Access Number.
     */
    fun generateCan() {
        updateCan(randomCan())
    }
    
    private fun randomCan(): String = List(PassportData.CAN_LENGTH) { Random.nextInt(10) }.joinToString("")

    /**
     * Sets the holder's portrait, or goes back to the placeholder given null.
     */
    fun updatePortrait(portrait: Portrait?) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(portrait = portrait))
    }

    /**
     * autofills the passport data with dummy values for testing.
     */
    fun autofillPassportData() {
        val current = _uiState.value.passportData
        // Let's use fixed dates for stability or simple construction
        val dummyData = PassportData(
            passportNumber = "123456789",
            dateOfBirth = kotlinx.datetime.LocalDate(1980, 1, 1),
            expiryDate = kotlinx.datetime.LocalDate(2030, 1, 1), // Using a safe far future date
            issuingCountry = "GBR",
            nationality = "GBR",
            firstName = "John",
            lastName = "Doe",
            gender = "M",
            // The chip settings are edited separately, so keep them, adding a CAN only if there is none
            accessControl = current.accessControl,
            paceMapping = current.paceMapping,
            can = current.can.ifBlank { randomCan() },
            portrait = current.portrait
        )
        updatePassportData(dummyData)
    }

    /**
     * Clears the document and holder details, keeping the chip settings.
     */
    fun clearPassportDetails() {
        val current = _uiState.value.passportData
        updatePassportData(
            PassportData.empty().copy(
                accessControl = current.accessControl,
                paceMapping = current.paceMapping,
                can = current.can
            )
        )
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
     * Restores the passport saved by a previous run, then saves each change once edits pause for [SAVE_DELAY_MS].
     * Saving starts only after the restore, so the empty initial passport never overwrites the saved one.
     */
    private fun restoreAndSavePassportData() {
        viewModelScope.launch {
            passportStore.load()
                .onSuccess { saved ->
                    // An edit made while loading wins over the saved passport
                    if (saved != null && _uiState.value.passportData == PassportData.empty()) {
                        updatePassportData(saved)
                    }
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.withError("Failed to restore passport: ${error.message}")
                }

            _uiState
                .map { it.passportData }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { passportData ->
                    delay(SAVE_DELAY_MS)
                    passportStore.save(passportData).onFailure { error ->
                        _uiState.value = _uiState.value.withError("Failed to save passport: ${error.message}")
                    }
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

    private companion object {
        /** Long enough to save once per burst of typing, short enough that closing the app rarely loses an edit. */
        const val SAVE_DELAY_MS = 300L
    }
}
