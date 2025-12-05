package com.hddev.smartemu.repository

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.os.Build
import androidx.core.content.ContextCompat
import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Android-specific implementation of NfcSimulatorRepository.
 * Handles NFC hardware availability, permissions, and HCE (Host Card Emulation) operations.
 */
class AndroidNfcSimulatorRepository(
    private val context: Context
) : NfcSimulatorRepository {
    
    private val mutex = Mutex()
    private var currentPassportData: PassportData? = null
    private var permissionController: PermissionController? = null
    
    interface PermissionController {
        fun requestPermissions(callback: (Boolean) -> Unit)
    }
    
    fun setPermissionController(controller: PermissionController) {
        this.permissionController = controller
    }
    
    // State flows for reactive updates
    private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
    private val _nfcEvents = MutableStateFlow<List<NfcEvent>>(emptyList())
    
    // NFC adapter for hardware access
    private val nfcAdapter: NfcAdapter? by lazy {
        NfcAdapter.getDefaultAdapter(context)
    }
    
    override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
        return mutex.withLock {
            try {
                // Check prerequisites
                val nfcAvailable = isNfcAvailable().getOrElse { false }
                if (!nfcAvailable) {
                    addEventInternal(NfcEvent.error(Clock.System.now(), "NFC hardware not available"))
                    return@withLock Result.failure(Exception("NFC hardware not available"))
                }
                
                val hasPermissions = hasNfcPermissions().getOrElse { false }
                if (!hasPermissions) {
                    addEventInternal(NfcEvent.error(Clock.System.now(), "NFC permissions not granted"))
                    return@withLock Result.failure(Exception("NFC permissions not granted"))
                }
                
                // Update status to starting
                _simulationStatus.value = SimulationStatus.STARTING
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.smartemu.data.NfcEventType.CONNECTION_ESTABLISHED,
                    message = "Starting NFC passport simulation",
                    details = mapOf("passportNumber" to passportData.passportNumber)
                ))
                
                // Store passport data for HCE service
                currentPassportData = passportData
                PassportHceService.setSharedPassportData(passportData)
                
                // Simulate successful startup (in real implementation, this would configure HCE)
                _simulationStatus.value = SimulationStatus.ACTIVE
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.smartemu.data.NfcEventType.CONNECTION_ESTABLISHED,
                    message = "NFC passport simulation active and ready",
                    details = emptyMap()
                ))
                
                Result.success(Unit)
            } catch (e: Exception) {
                _simulationStatus.value = SimulationStatus.ERROR
                addEventInternal(NfcEvent.error(Clock.System.now(), "Failed to start simulation: ${e.message}"))
                Result.failure(e)
            }
        }
    }
    
    override suspend fun stopSimulation(): Result<Unit> {
        return mutex.withLock {
            try {
                if (_simulationStatus.value == SimulationStatus.STOPPED) {
                    return@withLock Result.success(Unit)
                }
                
                _simulationStatus.value = SimulationStatus.STOPPING
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.smartemu.data.NfcEventType.CONNECTION_LOST,
                    message = "Stopping NFC passport simulation",
                    details = emptyMap()
                ))
                
                // Clear stored passport data
                currentPassportData = null
                PassportHceService.setSharedPassportData(null)
                
                // Update status to stopped
                _simulationStatus.value = SimulationStatus.STOPPED
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.smartemu.data.NfcEventType.CONNECTION_LOST,
                    message = "NFC passport simulation stopped",
                    details = emptyMap()
                ))
                
                Result.success(Unit)
            } catch (e: Exception) {
                _simulationStatus.value = SimulationStatus.ERROR
                addEventInternal(NfcEvent.error(Clock.System.now(), "Failed to stop simulation: ${e.message}"))
                Result.failure(e)
            }
        }
    }
    
    override fun getSimulationStatus(): Flow<SimulationStatus> {
        return _simulationStatus.asStateFlow()
    }
    
    override fun getNfcEvents(): Flow<NfcEvent> {
        return kotlinx.coroutines.flow.flow {
            _nfcEvents.collect { events ->
                events.forEach { event ->
                    emit(event)
                }
            }
        }
    }
    
    override suspend fun isNfcAvailable(): Result<Boolean> {
        return try {
            val hasNfcFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
            val hasHceFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
            val adapterAvailable = nfcAdapter != null
            
            val isAvailable = hasNfcFeature && hasHceFeature && adapterAvailable
            Result.success(isAvailable)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun hasNfcPermissions(): Result<Boolean> {
        return try {
            val hasNfcPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.NFC
            ) == PackageManager.PERMISSION_GRANTED
            
            // Check for additional permissions if needed based on Android version
            val hasAdditionalPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.NFC_TRANSACTION_EVENT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            
            Result.success(hasNfcPermission && hasAdditionalPermissions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun requestNfcPermissions(): Result<Boolean> {
        return try {
            if (permissionController != null) {
                return suspendCoroutine { cont ->
                    permissionController?.requestPermissions { granted ->
                        if (!granted) {
                            // If denied, we can't easily add an event here because we are in a suspend function
                            // and addEvent is suspend. But we can launch a coroutine if we had a scope.
                            // However, the ViewModel handles the failure/success.
                            // Let's just return the result.
                        }
                        cont.resume(Result.success(granted))
                    }
                }
            }

            // Fallback if no controller is set (e.g. during tests)
            val hasPermissions = hasNfcPermissions().getOrElse { false }
            
            if (!hasPermissions) {
                addEvent(NfcEvent.error(
                    Clock.System.now(), 
                    "NFC permissions required. Please enable NFC permissions in app settings."
                ))
            }
            
            Result.success(hasPermissions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun clearEvents() {
        mutex.withLock {
            _nfcEvents.value = emptyList()
        }
    }
    
    /**
     * Adds an event to the event list and notifies observers.
     */
    private suspend fun addEvent(event: NfcEvent) {
        mutex.withLock {
            addEventInternal(event)
        }
    }

    private fun addEventInternal(event: NfcEvent) {
        val currentEvents = _nfcEvents.value.toMutableList()
        currentEvents.add(event)
        
        // Keep only the last 100 events to prevent memory issues
        if (currentEvents.size > 100) {
            currentEvents.removeAt(0)
        }
        
        _nfcEvents.value = currentEvents
    }
    
    /**
     * Gets the currently stored passport data for HCE operations.
     */
    fun getCurrentPassportData(): PassportData? {
        return currentPassportData
    }
    
    /**
     * Simulates receiving an NFC connection from a reader.
     * This would be called by the HCE service when a reader connects.
     */
    suspend fun simulateNfcConnection(readerInfo: String = "") {
        addEvent(NfcEvent.connectionEstablished(Clock.System.now(), readerInfo))
    }
    
    /**
     * Simulates receiving a BAC authentication request.
     * This would be called by the HCE service when processing APDU commands.
     */
    suspend fun simulateBacAuthenticationRequest(challengeData: String = "") {
        addEvent(NfcEvent.bacAuthenticationRequest(Clock.System.now(), challengeData))
    }
    
    /**
     * Simulates receiving a PACE authentication request.
     * This would be called by the HCE service when processing APDU commands.
     */
    suspend fun simulatePaceAuthenticationRequest(protocolInfo: String = "") {
        addEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), protocolInfo))
    }
    
    /**
     * Simulates successful authentication.
     */
    suspend fun simulateAuthenticationSuccess(protocol: String) {
        addEvent(NfcEvent.authenticationSuccess(Clock.System.now(), protocol))
    }
    
    /**
     * Simulates authentication failure.
     */
    suspend fun simulateAuthenticationFailure(protocol: String, reason: String) {
        addEvent(NfcEvent.authenticationFailure(Clock.System.now(), protocol, reason))
    }
    
    /**
     * Simulates losing NFC connection.
     */
    suspend fun simulateConnectionLost(reason: String = "") {
        addEvent(NfcEvent.connectionLost(Clock.System.now(), reason))
    }
}