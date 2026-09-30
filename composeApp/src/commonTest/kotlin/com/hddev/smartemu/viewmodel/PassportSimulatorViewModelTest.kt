package com.hddev.smartemu.viewmodel

import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.AppSettings
import com.hddev.smartemu.data.AppTheme
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.repository.SettingsStore
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.data.ReadOutcome
import com.hddev.smartemu.repository.ReadHistoryStore
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.repository.NfcSimulatorRepository
import com.hddev.smartemu.repository.PassportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PassportSimulatorViewModelTest {
    
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: MockNfcSimulatorRepository
    private lateinit var passportStore: FakePassportStore
    private lateinit var viewModel: PassportSimulatorViewModel
    
    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = MockNfcSimulatorRepository()
        passportStore = FakePassportStore()
        viewModel = PassportSimulatorViewModel(mockRepository, passportStore)
    }
    
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    @Test
    fun `initial state should be correct`() = runTest {
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        
        assertEquals(PassportData.empty(), state.passportData)
        assertEquals(SimulationStatus.STOPPED, state.simulationStatus)
        assertFalse(state.nfcAvailable)
        assertFalse(state.hasNfcPermission)
        assertTrue(state.validationErrors.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertTrue(state.nfcEvents.isEmpty())
    }
    
    @Test
    fun `updatePassportData should update state and validate`() = runTest {
        val passportData = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(passportData)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(passportData, state.passportData)
    }
    
    @Test
    fun `updatePassportNumber should update passport data`() = runTest {
        val passportNumber = "AB123456"
        
        viewModel.updatePassportNumber(passportNumber)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(passportNumber, state.passportData.passportNumber)
    }
    
    @Test
    fun `updateDateOfBirth should update passport data`() = runTest {
        val dateOfBirth = LocalDate(1990, 1, 1)
        
        viewModel.updateDateOfBirth(dateOfBirth)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(dateOfBirth, state.passportData.dateOfBirth)
    }
    
    @Test
    fun `updateExpiryDate should update passport data`() = runTest {
        val expiryDate = LocalDate(2030, 1, 1)
        
        viewModel.updateExpiryDate(expiryDate)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(expiryDate, state.passportData.expiryDate)
    }
    
    @Test
    fun `updateFirstName should update passport data`() = runTest {
        val firstName = "John"
        
        viewModel.updateFirstName(firstName)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(firstName, state.passportData.firstName)
    }
    
    @Test
    fun `updateLastName should update passport data`() = runTest {
        val lastName = "Doe"
        
        viewModel.updateLastName(lastName)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(lastName, state.passportData.lastName)
    }

    @Test
    fun `autofillPassportData should populate default dummy data`() = runTest {
        viewModel.autofillPassportData()
        advanceUntilIdle()

        val state = viewModel.uiState.first()
        assertEquals("123456789", state.passportData.passportNumber)
        assertEquals("GBR", state.passportData.nationality)
        assertEquals("GBR", state.passportData.issuingCountry)
        assertEquals("John", state.passportData.firstName)
        assertEquals("Doe", state.passportData.lastName)
        assertEquals("M", state.passportData.gender)
        assertEquals(LocalDate(1980, 1, 1), state.passportData.dateOfBirth)
        // We can't strictly assert ExpiryDate since it depends on current time in the implementation logic
        // but checking it is not null is good enough for simulation or strict equality if we mocked time
        // The implementation uses fixed date 2030-01-01
        assertEquals(LocalDate(2030, 1, 1), state.passportData.expiryDate)
    }
    
    @Test
    fun `updateAccessControl should update passport data`() = runTest {
        viewModel.updateAccessControl(AccessControl.PACE_ONLY)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(AccessControl.PACE_ONLY, state.passportData.accessControl)
    }
    
    @Test
    fun `autofillPassportData should keep the selected access control`() = runTest {
        viewModel.updateAccessControl(AccessControl.BAC_ONLY)
        
        viewModel.autofillPassportData()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(AccessControl.BAC_ONLY, state.passportData.accessControl)
    }
    
    @Test
    fun `updatePaceMapping should update passport data and survive autofill`() = runTest {
        viewModel.updatePaceMapping(PaceMapping.CHIP_AUTHENTICATION)
        viewModel.autofillPassportData()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, state.passportData.paceMapping)
    }
    
    @Test
    fun `updateCan should keep at most six digits`() = runTest {
        viewModel.updateCan("12a34-5678")
        advanceUntilIdle()
        
        assertEquals("123456", viewModel.uiState.first().passportData.can)
    }
    
    @Test
    fun `generateCan and autofill should set a valid CAN`() = runTest {
        viewModel.generateCan()
        advanceUntilIdle()
        assertTrue(Regex("[0-9]{6}").matches(viewModel.uiState.first().passportData.can))
        
        viewModel.autofillPassportData()
        advanceUntilIdle()
        val state = viewModel.uiState.first()
        assertTrue(Regex("[0-9]{6}").matches(state.passportData.can))
        assertTrue(state.passportData.isValid())
    }

    @Test
    fun `autofillPassportData should keep an existing CAN`() = runTest {
        viewModel.updateCan("246810")
        viewModel.autofillPassportData()
        advanceUntilIdle()

        assertEquals("246810", viewModel.uiState.first().passportData.can)
    }

    @Test
    fun `clearPassportDetails should clear the holder but keep the chip settings`() = runTest {
        viewModel.autofillPassportData()
        viewModel.updateAccessControl(AccessControl.PACE_ONLY)
        viewModel.updatePaceMapping(PaceMapping.CHIP_AUTHENTICATION)
        viewModel.updateCan("135790")

        viewModel.clearPassportDetails()
        advanceUntilIdle()

        val data = viewModel.uiState.first().passportData
        assertEquals("", data.passportNumber)
        assertEquals("", data.lastName)
        assertEquals(null, data.dateOfBirth)
        assertEquals(AccessControl.PACE_ONLY, data.accessControl)
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, data.paceMapping)
        assertEquals("135790", data.can)
    }

    @Test
    fun `startSimulation should succeed when conditions are met`() = runTest {
        // Setup valid state
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(true)
        
        val validPassportData = PassportData(
            passportNumber = "AB1234567",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(validPassportData)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        // Start simulation
        mockRepository.setStartSimulationResult(Result.success(Unit))
        viewModel.startSimulation()
        advanceUntilIdle()
        
        assertTrue(mockRepository.startSimulationCalled)
        assertEquals(validPassportData, mockRepository.lastPassportData)
    }
    
    @Test
    fun `startSimulation should fail when NFC not available`() = runTest {
        mockRepository.setNfcAvailable(false)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertFalse(mockRepository.startSimulationCalled)
    }
    
    @Test
    fun `startSimulation should fail when no permissions`() = runTest {
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(false)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertFalse(mockRepository.startSimulationCalled)
    }
    
    @Test
    fun `startSimulation should handle repository failure`() = runTest {
        // Setup valid state
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(true)
        
        val validPassportData = PassportData(
            passportNumber = "AB1234567",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe"
        )
        
        viewModel.updatePassportData(validPassportData)
        viewModel.refreshNfcStatus()
        advanceUntilIdle()
        
        // Set repository to fail
        mockRepository.setStartSimulationResult(Result.failure(Exception("Test error")))
        viewModel.startSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Failed to start simulation"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `stopSimulation should succeed when simulation is active`() = runTest {
        // Set simulation as active
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        mockRepository.setStopSimulationResult(Result.success(Unit))
        viewModel.stopSimulation()
        advanceUntilIdle()
        
        assertTrue(mockRepository.stopSimulationCalled)
    }
    
    @Test
    fun `stopSimulation should handle repository failure`() = runTest {
        // Set simulation as active
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        mockRepository.setStopSimulationResult(Result.failure(Exception("Test error")))
        viewModel.stopSimulation()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Failed to stop simulation"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `requestNfcPermissions should update permission status on success`() = runTest {
        mockRepository.setRequestPermissionsResult(Result.success(true))
        
        viewModel.requestNfcPermissions()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertTrue(state.hasNfcPermission)
        assertNull(state.errorMessage)
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `requestNfcPermissions should handle permission denial`() = runTest {
        mockRepository.setRequestPermissionsResult(Result.success(false))
        
        viewModel.requestNfcPermissions()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertFalse(state.hasNfcPermission)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("NFC permissions are required"))
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `clearEvents should clear NFC events`() = runTest {
        // Add some events first
        val event = NfcEvent.connectionEstablished(Clock.System.now())
        mockRepository.nfcEventsFlow.emit(event)
        advanceUntilIdle()
        
        viewModel.clearEvents()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertTrue(state.nfcEvents.isEmpty())
        assertTrue(mockRepository.clearEventsCalled)
    }
    
    @Test
    fun `clearError should clear error message`() = runTest {
        // Set an error first
        viewModel.updatePassportData(PassportData()) // Invalid data
        advanceUntilIdle()
        
        viewModel.clearError()
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNull(state.errorMessage)
    }
    
    @Test
    fun `simulation status changes should update UI state`() = runTest {
        mockRepository.simulationStatusFlow.value = SimulationStatus.STARTING
        advanceUntilIdle()
        
        var state = viewModel.uiState.first()
        assertEquals(SimulationStatus.STARTING, state.simulationStatus)
        assertTrue(state.isLoading)
        
        mockRepository.simulationStatusFlow.value = SimulationStatus.ACTIVE
        advanceUntilIdle()
        
        state = viewModel.uiState.first()
        assertEquals(SimulationStatus.ACTIVE, state.simulationStatus)
        assertFalse(state.isLoading)
    }
    
    @Test
    fun `NFC events should be added to UI state`() = runTest {
        val event1 = NfcEvent.connectionEstablished(Clock.System.now())
        val event2 = NfcEvent.bacAuthenticationRequest(Clock.System.now())
        
        mockRepository.nfcEventsFlow.emit(event1)
        advanceUntilIdle()
        
        var state = viewModel.uiState.first()
        assertEquals(1, state.nfcEvents.size)
        assertEquals(event1, state.nfcEvents[0])
        
        mockRepository.nfcEventsFlow.emit(event2)
        advanceUntilIdle()
        
        state = viewModel.uiState.first()
        assertEquals(2, state.nfcEvents.size)
        assertEquals(event2, state.nfcEvents[1])
    }
    
    @Test
    fun `error events should set error message`() = runTest {
        val errorEvent = NfcEvent.error(Clock.System.now(), "Test error")
        
        mockRepository.nfcEventsFlow.emit(errorEvent)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Test error"))
    }
    
    @Test
    fun `authentication failure events should set error message`() = runTest {
        val failureEvent = NfcEvent.authenticationFailure(Clock.System.now(), "BAC", "Invalid MRZ")
        
        mockRepository.nfcEventsFlow.emit(failureEvent)
        advanceUntilIdle()
        
        val state = viewModel.uiState.first()
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("BAC authentication failed"))
    }

    @Test
    fun `saved passport should be restored on creation`() = runTest {
        val saved = PassportData(
            passportNumber = "AB123456",
            dateOfBirth = LocalDate(1990, 1, 1),
            expiryDate = LocalDate(2030, 1, 1),
            firstName = "John",
            lastName = "Doe",
            accessControl = AccessControl.PACE_ONLY,
            paceMapping = PaceMapping.CHIP_AUTHENTICATION,
            can = "123456"
        )
        passportStore.saved = saved

        val restoredViewModel = PassportSimulatorViewModel(mockRepository, passportStore)
        advanceUntilIdle()

        assertEquals(saved, restoredViewModel.uiState.value.passportData)
        assertTrue(passportStore.saveCount == 0, "Restoring should not save the passport back")
    }

    @Test
    fun `untouched passport should not be saved`() = runTest {
        advanceUntilIdle()

        assertNull(passportStore.saved)
    }

    @Test
    fun `edits should be saved once they pause`() = runTest {
        advanceUntilIdle()

        viewModel.updateFirstName("J")
        viewModel.updateFirstName("Jo")
        viewModel.updateAccessControl(AccessControl.BAC_ONLY)
        advanceUntilIdle()

        assertEquals(1, passportStore.saveCount)
        assertEquals("Jo", passportStore.saved?.firstName)
        assertEquals(AccessControl.BAC_ONLY, passportStore.saved?.accessControl)
    }

    @Test
    fun `failed restore should set error message and keep the empty passport`() = runTest {
        passportStore.loadResult = Result.failure(RuntimeException("Corrupt"))

        val failedViewModel = PassportSimulatorViewModel(mockRepository, passportStore)
        advanceUntilIdle()

        val state = failedViewModel.uiState.value
        assertEquals(PassportData.empty(), state.passportData)
        assertTrue(state.errorMessage!!.contains("Failed to restore passport"))
    }

    @Test
    fun `applying a preset keeps the portrait and gives the chip a CAN`() = runTest {
        val portrait = Portrait(byteArrayOf(1, 2, 3), 1, 1)
        viewModel.updatePortrait(portrait)

        viewModel.applyPreset(PassportPresets.byId("id-card")!!)
        advanceUntilIdle()

        val data = viewModel.uiState.value.passportData
        assertEquals(DocumentType.ID_CARD, data.documentType)
        assertEquals(portrait, data.portrait)
        assertEquals(PassportData.CAN_LENGTH, data.can.length)
        assertTrue(viewModel.uiState.value.validationErrors.isEmpty())
    }

    @Test
    fun `a cloned chip turns Active Authentication on, and turning it off clears the fault`() = runTest {
        viewModel.updateChipFault(ChipFault.CLONED_CHIP)
        assertTrue(viewModel.uiState.value.passportData.activeAuthentication)

        viewModel.updateActiveAuthentication(false)

        val data = viewModel.uiState.value.passportData
        assertFalse(data.activeAuthentication)
        assertEquals(ChipFault.NONE, data.chipFault)
    }

    @Test
    fun `other faults survive turning Active Authentication off`() = runTest {
        viewModel.updateChipFault(ChipFault.SWAPPED_PHOTO)
        viewModel.updateActiveAuthentication(true)
        viewModel.updateActiveAuthentication(false)

        assertEquals(ChipFault.SWAPPED_PHOTO, viewModel.uiState.value.passportData.chipFault)
    }

    @Test
    fun `a finished reader session is added to the read history and saved`() = runTest {
        val historyStore = ReadHistoryStore.InMemory()
        val recordingViewModel = PassportSimulatorViewModel(mockRepository, passportStore, historyStore)
        advanceUntilIdle()
        val now = Clock.System.now()

        listOf(
            NfcEvent.connectionEstablished(now, NfcEvent.READER_CONNECTED),
            NfcEvent.authenticationSuccess(now, "BAC"),
            NfcEvent.connectionEstablished(now, NfcEvent.READING_PREFIX + "EF.DG1"),
            NfcEvent.connectionLost(now)
        ).forEach { mockRepository.nfcEventsFlow.emit(it) }
        advanceUntilIdle()

        val record = recordingViewModel.uiState.value.readHistory.single()
        assertEquals("BAC", record.accessProtocol)
        assertEquals(ReadOutcome.PARTIAL, record.outcome)
        assertEquals(listOf(record), historyStore.load().getOrThrow())

        recordingViewModel.clearReadHistory()
        advanceUntilIdle()
        assertTrue(recordingViewModel.uiState.value.readHistory.isEmpty())
        assertTrue(historyStore.load().getOrThrow().isEmpty())
    }

    private val readyPassport = PassportData(
        passportNumber = "AB1234567",
        dateOfBirth = LocalDate(1990, 1, 1),
        expiryDate = LocalDate(2030, 1, 1),
        firstName = "John",
        lastName = "Doe"
    )

    private suspend fun kotlinx.coroutines.test.TestScope.startWith(model: PassportSimulatorViewModel): PassportData? {
        mockRepository.setNfcAvailable(true)
        mockRepository.setHasPermissions(true)
        model.updatePassportData(readyPassport)
        model.refreshNfcStatus()
        advanceUntilIdle()
        model.startSimulation()
        advanceUntilIdle()
        return mockRepository.lastPassportData
    }

    @Test
    fun `exact cryptography reaches the chip only in developer mode`() = runTest {
        val cases = mapOf(
            AppSettings() to false,
            AppSettings(developerMode = true) to true,
            AppSettings(developerMode = true, exactCryptography = false) to false
        )
        for ((settings, exact) in cases) {
            val model = PassportSimulatorViewModel(
                mockRepository, passportStore, settingsStore = SettingsStore.InMemory(settings)
            )
            assertEquals(exact, startWith(model)!!.exactCryptography, settings.toString())
        }
    }

    @Test
    fun `settings changes are saved`() = runTest {
        val settingsStore = SettingsStore.InMemory()
        val model = PassportSimulatorViewModel(mockRepository, passportStore, settingsStore = settingsStore)

        model.updateSettings(model.settings.value.copy(developerMode = true, keepScreenOn = false))

        assertEquals(AppSettings(developerMode = true, keepScreenOn = false), settingsStore.load())
        assertEquals(settingsStore.load(), model.settings.value)
    }

    @Test
    fun `settings load from the store`() = runTest {
        val settingsStore = SettingsStore.InMemory()
        settingsStore.save(AppSettings(developerMode = true, theme = AppTheme.DARK, introSeen = true))

        val model = PassportSimulatorViewModel(mockRepository, passportStore, settingsStore = settingsStore)

        assertEquals(AppSettings(developerMode = true, theme = AppTheme.DARK, introSeen = true), model.settings.value)
    }

    @Test
    fun `a chip profile sets the chip and country, and a PACE-only one gets a CAN`() = runTest {
        viewModel.updatePassportData(readyPassport)
        viewModel.updateChipProfile(ChipProfiles.byId("de-id-card"))
        advanceUntilIdle()

        val data = viewModel.uiState.value.passportData
        assertEquals("de-id-card", data.chipProfileId)
        assertEquals(AccessControl.PACE_ONLY, data.accessControl)
        assertEquals(DocumentType.ID_CARD, data.documentType)
        assertEquals("DEU", data.issuingCountry)
        assertEquals(PassportData.CAN_LENGTH, data.can.length)
        assertEquals("John", data.firstName)
    }

    @Test
    fun `changing to a document type the profile doesn't fit returns to the generic profile`() = runTest {
        viewModel.updatePassportData(readyPassport)
        viewModel.updateChipProfile(ChipProfiles.byId("de-passport-2017"))
        viewModel.updateDocumentType(DocumentType.ID_CARD)
        advanceUntilIdle()

        assertEquals(ChipProfiles.GENERIC_ID, viewModel.uiState.value.passportData.chipProfileId)
    }

    @Test
    fun `turning off the read history clears it and stops recording`() = runTest {
        val historyStore = ReadHistoryStore.InMemory()
        val model = PassportSimulatorViewModel(mockRepository, passportStore, historyStore)
        model.updateSettings(model.settings.value.copy(keepReadHistory = false))
        advanceUntilIdle()

        assertTrue(model.uiState.value.readHistory.isEmpty())
        assertFalse(model.settings.value.keepReadHistory)
    }

}

/**
 * In-memory PassportStore for testing.
 */
private class FakePassportStore : PassportStore {

    var saved: PassportData? = null
    var saveCount = 0
    var loadResult: Result<PassportData?>? = null

    override suspend fun load(): Result<PassportData?> = loadResult ?: Result.success(saved)

    override suspend fun save(passportData: PassportData): Result<Unit> {
        saveCount++
        saved = passportData
        return Result.success(Unit)
    }
}

/**
 * Mock implementation of NfcSimulatorRepository for testing.
 */
private class MockNfcSimulatorRepository : NfcSimulatorRepository {
    
    val simulationStatusFlow = MutableStateFlow(SimulationStatus.STOPPED)
    val nfcEventsFlow = MutableSharedFlow<NfcEvent>()
    
    private var nfcAvailable = false
    private var hasPermissions = false
    private var startSimulationResult: Result<Unit> = Result.success(Unit)
    private var stopSimulationResult: Result<Unit> = Result.success(Unit)
    private var requestPermissionsResult: Result<Boolean> = Result.success(true)
    
    var startSimulationCalled = false
    var stopSimulationCalled = false
    var clearEventsCalled = false
    var lastPassportData: PassportData? = null
    
    fun setNfcAvailable(available: Boolean) {
        nfcAvailable = available
    }
    
    fun setHasPermissions(permissions: Boolean) {
        hasPermissions = permissions
    }
    
    fun setStartSimulationResult(result: Result<Unit>) {
        startSimulationResult = result
    }
    
    fun setStopSimulationResult(result: Result<Unit>) {
        stopSimulationResult = result
    }
    
    fun setRequestPermissionsResult(result: Result<Boolean>) {
        requestPermissionsResult = result
    }
    
    override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
        startSimulationCalled = true
        lastPassportData = passportData
        return startSimulationResult
    }
    
    override suspend fun stopSimulation(): Result<Unit> {
        stopSimulationCalled = true
        return stopSimulationResult
    }
    
    override fun getSimulationStatus() = simulationStatusFlow
    
    override fun getNfcEvents() = nfcEventsFlow
    
    override suspend fun isNfcAvailable(): Result<Boolean> {
        return Result.success(nfcAvailable)
    }
    
    override suspend fun hasNfcPermissions(): Result<Boolean> {
        return Result.success(hasPermissions)
    }
    
    override suspend fun requestNfcPermissions(): Result<Boolean> {
        return requestPermissionsResult
    }
    
    override suspend fun clearEvents() {
        clearEventsCalled = true
    }
}