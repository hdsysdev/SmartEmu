package com.hddev.smartemu.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPreset
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.data.ReadRecord
import com.hddev.smartemu.data.ReadSessionRecorder
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.data.withChipProfile
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore
import com.hddev.smartemu.repository.ReadHistoryStore
import com.hddev.smartemu.repository.SettingsStore
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.random.Random
import kotlin.time.Clock

/**
 * ViewModel for managing the passport simulator UI state and coordinating with the repository.
 * Handles passport data validation, simulation control, and NFC event management.
 * The passport is restored from [passportStore] on creation and saved back to it as it changes; each reader
 * session is recorded in the read history, which [readHistoryStore] keeps, unless the settings say not to. The
 * settings come from [settingsStore], synchronously, so the first screen already follows them.
 */
class PassportSimulatorViewModel(
    private val repository: NfcSimulatorRepository,
    private val passportStore: PassportStore,
    private val readHistoryStore: ReadHistoryStore = ReadHistoryStore.InMemory(),
    private val settingsStore: SettingsStore = SettingsStore.InMemory()
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(PassportSimulatorUiState.initial())
    val uiState: StateFlow<PassportSimulatorUiState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val readSessionRecorder = ReadSessionRecorder()
    
    init {
        initializeNfcStatus()
        observeSimulationStatus()
        observeNfcEvents()
        restoreAndSavePassportData()
        restoreReadHistory()
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
    
    /**
     * Sets the kind of document, which sets the MRZ format. A chip profile for another kind of document gives way to
     * the generic one.
     */
    fun updateDocumentType(documentType: DocumentType) {
        val currentData = _uiState.value.passportData
        val updated = currentData.copy(documentType = documentType)
        updatePassportData(
            if (currentData.chipProfile.fits(documentType)) updated else updated.withChipProfile(ChipProfiles.default)
        )
    }

    /**
     * Makes the chip behave like [profile]'s, with its access control, PACE mapping and Active Authentication, and its
     * document type and country. A chip that accepts PACE only gets a CAN if it has none.
     */
    fun updateChipProfile(profile: ChipProfile) {
        val currentData = _uiState.value.passportData
        val updated = currentData.withChipProfile(profile)
        val needsCan = !updated.accessControl.supportsBac && !updated.hasCan()
        updatePassportData(if (needsCan) updated.copy(can = randomCan()) else updated)
    }

    fun updatePersonalNumber(personalNumber: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(personalNumber = personalNumber))
    }

    fun updatePlaceOfBirth(placeOfBirth: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(placeOfBirth = placeOfBirth))
    }

    fun updateIssuingAuthority(issuingAuthority: String) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(issuingAuthority = issuingAuthority))
    }

    fun updateDateOfIssue(dateOfIssue: kotlinx.datetime.LocalDate?) {
        val currentData = _uiState.value.passportData
        updatePassportData(currentData.copy(dateOfIssue = dateOfIssue))
    }

    /**
     * Replaces the settings and saves them. Turning the read history off forgets what it held.
     */
    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
        settingsStore.save(settings)
        if (!settings.keepReadHistory && _uiState.value.readHistory.isNotEmpty()) {
            updateReadHistory(emptyList())
        }
    }

    /**
     * Turns Active Authentication on or off. A cloned chip only shows with it, so turning it off makes the chip
     * genuine again.
     */
    fun updateActiveAuthentication(enabled: Boolean) {
        val currentData = _uiState.value.passportData
        val fault = if (!enabled && currentData.chipFault.needsActiveAuthentication) ChipFault.NONE else currentData.chipFault
        updatePassportData(currentData.copy(activeAuthentication = enabled, chipFault = fault))
    }

    /**
     * Sets the flaw the chip has on purpose, turning on Active Authentication if the flaw needs it.
     */
    fun updateChipFault(chipFault: ChipFault) {
        val currentData = _uiState.value.passportData
        updatePassportData(
            currentData.copy(
                chipFault = chipFault,
                activeAuthentication = currentData.activeAuthentication || chipFault.needsActiveAuthentication
            )
        )
    }

    /**
     * Replaces the document with a ready-made one, made for today. The portrait stays, as it's the holder's own,
     * and so does the CAN, or a new one if there was none.
     */
    fun applyPreset(preset: PassportPreset) {
        val current = _uiState.value.passportData
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        updatePassportData(
            preset.build(today).copy(portrait = current.portrait, can = current.can.ifBlank { randomCan() })
        )
    }

    /**
     * Forgets every recorded reader session.
     */
    fun clearReadHistory() {
        updateReadHistory(emptyList())
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
            portrait = current.portrait,
            documentType = current.documentType,
            activeAuthentication = current.activeAuthentication,
            chipFault = current.chipFault,
            chipProfileId = current.chipProfileId
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
                can = current.can,
                documentType = current.documentType,
                activeAuthentication = current.activeAuthentication,
                chipFault = current.chipFault,
                chipProfileId = current.chipProfileId
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
            
            // How exactly the chip follows its profile is a setting of the app's, fixed for the run
            val passportData = currentState.passportData.copy(
                exactCryptography = _settings.value.usesExactCryptography
            )
            repository.startSimulation(passportData)
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
                // A reader still there when the emulation stops has had all it's going to get
                if (status == SimulationStatus.STOPPED || status == SimulationStatus.ERROR) {
                    readSessionRecorder.finish(_uiState.value.passportData)?.let(::addReadRecord)
                }
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
                readSessionRecorder.onEvent(event, _uiState.value.passportData)?.let(::addReadRecord)
                
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

    /**
     * Restores the read history saved by a previous run, ahead of any session recorded since.
     */
    private fun restoreReadHistory() {
        viewModelScope.launch {
            readHistoryStore.load().onSuccess { saved ->
                _uiState.value = _uiState.value.withReadHistory((_uiState.value.readHistory + saved).take(MAX_READ_RECORDS))
            }
        }
    }

    private fun addReadRecord(record: ReadRecord) {
        if (!_settings.value.keepReadHistory) return
        updateReadHistory((listOf(record) + _uiState.value.readHistory).take(MAX_READ_RECORDS))
    }

    private fun updateReadHistory(records: List<ReadRecord>) {
        _uiState.value = _uiState.value.withReadHistory(records)
        viewModelScope.launch {
            // Losing the history is no reason to interrupt anyone, so a failed save goes unreported
            readHistoryStore.save(records)
        }
    }

    private companion object {
        /** Enough to look back over a day of trying a reader, few enough to show in a list. */
        const val MAX_READ_RECORDS = 50

        /** Long enough to save once per burst of typing, short enough that closing the app rarely loses an edit. */
        const val SAVE_DELAY_MS = 300L
    }
}
