package com.hddev.smartemu.repository

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.SimulationStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for NFC passport simulation operations.
 * Abstracts platform-specific NFC implementation from common business logic.
 */
interface NfcSimulatorRepository {
    
    /**
     * Starts the NFC passport simulation with the provided passport data.
     * 
     * @param passportData The passport data to use for simulation
     * @return Result indicating success or failure with error details
     */
    suspend fun startSimulation(passportData: PassportData): Result<Unit>
    
    /**
     * Stops the currently running NFC passport simulation.
     * 
     * @return Result indicating success or failure with error details
     */
    suspend fun stopSimulation(): Result<Unit>
    
    /**
     * Gets the current simulation status as a Flow for reactive updates.
     * 
     * @return Flow of SimulationStatus updates
     */
    fun getSimulationStatus(): Flow<SimulationStatus>
    
    /**
     * Gets NFC events as a Flow for real-time monitoring.
     * 
     * @return Flow of NfcEvent objects representing NFC interactions
     */
    fun getNfcEvents(): Flow<NfcEvent>
    
    /**
     * Checks if NFC hardware is available on the device.
     * 
     * @return Result containing boolean indicating NFC availability
     */
    suspend fun isNfcAvailable(): Result<Boolean>
    
    /**
     * Checks if the app has necessary NFC permissions.
     * 
     * @return Result containing boolean indicating permission status
     */
    suspend fun hasNfcPermissions(): Result<Boolean>
    
    /**
     * Requests NFC permissions from the user.
     * 
     * @return Result indicating if permissions were granted
     */
    suspend fun requestNfcPermissions(): Result<Boolean>
    
    /**
     * Clears all accumulated NFC events.
     */
    suspend fun clearEvents()
}