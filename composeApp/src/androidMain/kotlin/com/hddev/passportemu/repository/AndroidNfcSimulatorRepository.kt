// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.repository

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hddev.passportemu.PassportHceService
import com.hddev.passportemu.data.NfcEvent
import com.hddev.passportemu.data.PassportData
import com.hddev.passportemu.data.SimulationStatus
import com.hddev.passportemu.utils.EventLogFormatter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
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
    private var permissionController: PermissionController? = null
    
    interface PermissionController {
        fun requestPermissions(callback: (Boolean) -> Unit)
    }
    
    fun setPermissionController(controller: PermissionController) {
        this.permissionController = controller
    }
    
    // State flows for reactive updates
    private val _simulationStatus = MutableStateFlow(SimulationStatus.STOPPED)
    // Events are passed on as they happen rather than kept here; the ViewModel keeps the log
    private val _nfcEvents = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
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
                    type = com.hddev.passportemu.data.NfcEventType.CONNECTION_ESTABLISHED,
                    message = "Starting NFC passport simulation",
                    details = mapOf(
                        "passportNumber" to passportData.passportNumber,
                        "accessControl" to passportData.accessControl.displayName,
                        "paceMapping" to passportData.paceMapping.abbreviation,
                        "can" to passportData.can.ifBlank { "none" }
                    )
                ))
                
                // Store passport data for HCE service
                PassportHceService.setSharedPassportData(passportData)
                
                // Simulate successful startup (in real implementation, this would configure HCE)
                _simulationStatus.value = SimulationStatus.ACTIVE
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.passportemu.data.NfcEventType.CONNECTION_ESTABLISHED,
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
                    type = com.hddev.passportemu.data.NfcEventType.CONNECTION_LOST,
                    message = "Stopping NFC passport simulation",
                    details = emptyMap()
                ))
                
                // Clear stored passport data
                PassportHceService.setSharedPassportData(null)
                
                // Update status to stopped
                _simulationStatus.value = SimulationStatus.STOPPED
                addEventInternal(NfcEvent(
                    timestamp = Clock.System.now(),
                    type = com.hddev.passportemu.data.NfcEventType.CONNECTION_LOST,
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
    
    /**
     * The repository's own events merged with the reader's, from [PassportHceService]. Each is also written to logcat
     * as a JSON line under [EventLogFormatter.LOGCAT_TAG], so `adb logcat -s PassportEmuEvents` follows the log live.
     */
    override fun getNfcEvents(): Flow<NfcEvent> {
        return merge(_nfcEvents, PassportHceService.nfcEvents)
            .onEach { Log.i(EventLogFormatter.LOGCAT_TAG, EventLogFormatter.eventJson(it)) }
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
                    // The view model reports a refusal
                    permissionController?.requestPermissions { granted -> cont.resume(Result.success(granted)) }
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
        // Nothing to clear: past events aren't kept here
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
        _nfcEvents.tryEmit(event)
    }
}
