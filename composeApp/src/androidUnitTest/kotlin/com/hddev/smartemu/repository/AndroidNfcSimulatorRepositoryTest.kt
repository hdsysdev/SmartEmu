package com.hddev.smartemu.repository

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidNfcSimulatorRepositoryTest {
    
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var repository: AndroidNfcSimulatorRepository
    
    private val testPassportData = PassportData(
        passportNumber = "AB1234567",
        nationality = "USA",
        firstName = "John",
        lastName = "Doe",
        dateOfBirth = LocalDate(1990, 1, 1),
        gender = "M",
        expiryDate = LocalDate(2030, 1, 1),
        personalNumber = "123456789"
    )
    
    @BeforeTest
    fun setup() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        nfcAdapter = mockk(relaxed = true)
        
        every { context.packageManager } returns packageManager
        
        // Mock static methods
        mockkStatic(NfcAdapter::class)
        mockkStatic(ContextCompat::class)
        
        every { NfcAdapter.getDefaultAdapter(context) } returns nfcAdapter
        
        repository = AndroidNfcSimulatorRepository(context)
    }
    
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `initial simulation status should be STOPPED`() = runTest {
        val status = repository.getSimulationStatus().first()
        assertEquals(SimulationStatus.STOPPED, status)
    }
    
    @Test
    fun `isNfcAvailable returns true when all NFC features are available`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        
        // When
        val result = repository.isNfcAvailable()
        
        // Then
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }
    
    @Test
    fun `isNfcAvailable returns false when NFC feature is missing`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns false
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        
        // When
        val result = repository.isNfcAvailable()
        
        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }
    
    @Test
    fun `isNfcAvailable returns false when HCE feature is missing`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns false
        
        // When
        val result = repository.isNfcAvailable()
        
        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }
    
    @Test
    fun `hasNfcPermissions returns true when NFC permission is granted`() = runTest {
        // Given
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_GRANTED
        
        // When
        val result = repository.hasNfcPermissions()
        
        // Then
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }
    
    @Test
    fun `hasNfcPermissions returns false when NFC permission is denied`() = runTest {
        // Given
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_DENIED
        
        // When
        val result = repository.hasNfcPermissions()
        
        // Then
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }
    
    @Test
    fun `startSimulation fails when NFC is not available`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns false
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        
        // When
        val result = repository.startSimulation(testPassportData)
        
        // Then
        assertTrue(result.isFailure)
        assertEquals(SimulationStatus.STOPPED, repository.getSimulationStatus().first())
    }
    
    @Test
    fun `startSimulation fails when permissions are not granted`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_DENIED
        
        // When
        val result = repository.startSimulation(testPassportData)
        
        // Then
        assertTrue(result.isFailure)
        assertEquals(SimulationStatus.STOPPED, repository.getSimulationStatus().first())
    }
    
    @Test
    fun `startSimulation succeeds when prerequisites are met`() = runTest {
        // Given
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_GRANTED
        
        // When
        val result = repository.startSimulation(testPassportData)
        
        // Then
        assertTrue(result.isSuccess)
        assertEquals(SimulationStatus.ACTIVE, repository.getSimulationStatus().first())
        assertEquals(testPassportData, repository.getCurrentPassportData())
    }
    
    @Test
    fun `stopSimulation changes status to STOPPED`() = runTest {
        // Given - start simulation first
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) } returns true
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) } returns true
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_GRANTED
        
        repository.startSimulation(testPassportData)
        assertEquals(SimulationStatus.ACTIVE, repository.getSimulationStatus().first())
        
        // When
        val result = repository.stopSimulation()
        
        // Then
        assertTrue(result.isSuccess)
        assertEquals(SimulationStatus.STOPPED, repository.getSimulationStatus().first())
        assertNull(repository.getCurrentPassportData())
    }
    
    @Test
    fun `stopSimulation succeeds when already stopped`() = runTest {
        // Given - simulation is already stopped
        assertEquals(SimulationStatus.STOPPED, repository.getSimulationStatus().first())
        
        // When
        val result = repository.stopSimulation()
        
        // Then
        assertTrue(result.isSuccess)
        assertEquals(SimulationStatus.STOPPED, repository.getSimulationStatus().first())
    }
    
    @Test
    fun `simulateNfcConnection adds connection established event`() = runTest {
        // Given
        val readerInfo = "Test Reader"
        
        // When
        repository.simulateNfcConnection(readerInfo)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.CONNECTION_ESTABLISHED, events[0].type)
        assertTrue(events[0].message.contains("NFC connection established"))
    }
    
    @Test
    fun `simulateBacAuthenticationRequest adds BAC request event`() = runTest {
        // Given
        val challengeData = "test-challenge"
        
        // When
        repository.simulateBacAuthenticationRequest(challengeData)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.BAC_AUTHENTICATION_REQUEST, events[0].type)
        assertTrue(events[0].message.contains("BAC authentication requested"))
    }
    
    @Test
    fun `simulatePaceAuthenticationRequest adds PACE request event`() = runTest {
        // Given
        val protocolInfo = "PACE-v2"
        
        // When
        repository.simulatePaceAuthenticationRequest(protocolInfo)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.PACE_AUTHENTICATION_REQUEST, events[0].type)
        assertTrue(events[0].message.contains("PACE authentication requested"))
    }
    
    @Test
    fun `simulateAuthenticationSuccess adds success event`() = runTest {
        // Given
        val protocol = "BAC"
        
        // When
        repository.simulateAuthenticationSuccess(protocol)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.AUTHENTICATION_SUCCESS, events[0].type)
        assertTrue(events[0].message.contains("BAC authentication successful"))
    }
    
    @Test
    fun `simulateAuthenticationFailure adds failure event`() = runTest {
        // Given
        val protocol = "PACE"
        val reason = "Invalid key"
        
        // When
        repository.simulateAuthenticationFailure(protocol, reason)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.AUTHENTICATION_FAILURE, events[0].type)
        assertTrue(events[0].message.contains("PACE authentication failed"))
        assertTrue(events[0].message.contains(reason))
    }
    
    @Test
    fun `simulateConnectionLost adds connection lost event`() = runTest {
        // Given
        val reason = "Reader disconnected"
        
        // When
        repository.simulateConnectionLost(reason)
        
        // Then
        val events = repository.getNfcEvents().take(1).toList()
        assertEquals(1, events.size)
        assertEquals(NfcEventType.CONNECTION_LOST, events[0].type)
        assertTrue(events[0].message.contains("NFC connection lost"))
    }
    
    @Test
    fun `clearEvents removes all events`() = runTest {
        // Given - add some events
        repository.simulateNfcConnection()
        repository.simulateBacAuthenticationRequest()
        
        // Verify events exist
        val eventsBefore = repository.getNfcEvents().take(2).toList()
        assertEquals(2, eventsBefore.size)
        
        // When
        repository.clearEvents()
        
        // Then
        val eventsAfter = repository.getNfcEvents().take(1).toList()
        assertEquals(0, eventsAfter.size)
    }
    
    @Test
    fun `requestNfcPermissions returns current permission status`() = runTest {
        // Given
        every { 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.NFC) 
        } returns PackageManager.PERMISSION_GRANTED
        
        // When
        val result = repository.requestNfcPermissions()
        
        // Then
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }
    
    @Test
    fun `events are limited to 100 items`() = runTest {
        // Given - add more than 100 events
        repeat(105) { index ->
            repository.simulateNfcConnection("Reader $index")
        }
        
        // When - collect all events
        val events = mutableListOf<com.hddev.smartemu.data.NfcEvent>()
        repository.getNfcEvents().take(100).toList(events)
        
        // Then - should have exactly 100 events (oldest ones removed)
        assertEquals(100, events.size)
    }
}