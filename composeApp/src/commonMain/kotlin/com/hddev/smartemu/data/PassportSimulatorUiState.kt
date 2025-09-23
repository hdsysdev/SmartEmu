package com.hddev.smartemu.data

/**
 * Represents the complete UI state for the passport simulator application.
 * Contains all data needed to render the UI and handle user interactions.
 */
data class PassportSimulatorUiState(
    val passportData: PassportData = PassportData.empty(),
    val simulationStatus: SimulationStatus = SimulationStatus.STOPPED,
    val nfcAvailable: Boolean = false,
    val hasNfcPermission: Boolean = false,
    val validationErrors: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val nfcEvents: List<NfcEvent> = emptyList(),
    val maxEventCount: Int = 100
) {
    
    /**
     * Returns true if the simulation can be started based on current state.
     */
    fun canStartSimulation(): Boolean {
        return simulationStatus.canStart() &&
               nfcAvailable &&
               hasNfcPermission &&
               passportData.isValid() &&
               !isLoading
    }
    
    /**
     * Returns true if the simulation can be stopped based on current state.
     */
    fun canStopSimulation(): Boolean {
        return simulationStatus.canStop() && !isLoading
    }
    
    /**
     * Returns true if the passport form should be enabled for editing.
     */
    fun isPassportFormEnabled(): Boolean {
        return simulationStatus == SimulationStatus.STOPPED && !isLoading
    }
    
    /**
     * Returns true if NFC permissions need to be requested.
     */
    fun needsNfcPermission(): Boolean {
        return nfcAvailable && !hasNfcPermission
    }
    
    /**
     * Returns true if there are any validation errors.
     */
    fun hasValidationErrors(): Boolean {
        return validationErrors.isNotEmpty()
    }
    
    /**
     * Returns true if the simulation is currently active.
     */
    fun isSimulationActive(): Boolean {
        return simulationStatus == SimulationStatus.ACTIVE
    }
    
    /**
     * Returns true if there are recent error events.
     */
    fun hasRecentErrors(): Boolean {
        return nfcEvents.any { it.type.isError() }
    }
    
    /**
     * Gets the most recent NFC events up to the specified limit.
     */
    fun getRecentEvents(limit: Int = 10): List<NfcEvent> {
        return nfcEvents.takeLast(limit)
    }
    
    /**
     * Gets events of a specific type.
     */
    fun getEventsByType(type: NfcEventType): List<NfcEvent> {
        return nfcEvents.filter { it.type == type }
    }
    
    /**
     * Returns a copy with updated passport data and validation errors.
     */
    fun withPassportData(newPassportData: PassportData): PassportSimulatorUiState {
        return copy(
            passportData = newPassportData,
            validationErrors = newPassportData.getValidationErrors()
        )
    }
    
    /**
     * Returns a copy with updated simulation status.
     */
    fun withSimulationStatus(newStatus: SimulationStatus): PassportSimulatorUiState {
        return copy(simulationStatus = newStatus)
    }
    
    /**
     * Returns a copy with updated NFC availability and permission status.
     */
    fun withNfcStatus(available: Boolean, hasPermission: Boolean): PassportSimulatorUiState {
        return copy(
            nfcAvailable = available,
            hasNfcPermission = hasPermission
        )
    }
    
    /**
     * Returns a copy with a new NFC event added.
     */
    fun withNewEvent(event: NfcEvent): PassportSimulatorUiState {
        val updatedEvents = (nfcEvents + event).takeLast(maxEventCount)
        return copy(nfcEvents = updatedEvents)
    }
    
    /**
     * Returns a copy with all NFC events cleared.
     */
    fun withClearedEvents(): PassportSimulatorUiState {
        return copy(nfcEvents = emptyList())
    }
    
    /**
     * Returns a copy with updated loading state.
     */
    fun withLoading(loading: Boolean): PassportSimulatorUiState {
        return copy(isLoading = loading)
    }
    
    /**
     * Returns a copy with updated error message.
     */
    fun withError(message: String?): PassportSimulatorUiState {
        return copy(errorMessage = message)
    }
    
    companion object {
        /**
         * Creates an initial UI state with default values.
         */
        fun initial(): PassportSimulatorUiState {
            return PassportSimulatorUiState()
        }
        
        /**
         * Creates a UI state for when NFC is not available.
         */
        fun nfcNotAvailable(): PassportSimulatorUiState {
            return PassportSimulatorUiState(
                nfcAvailable = false,
                errorMessage = "NFC is not available on this device"
            )
        }
        
        /**
         * Creates a UI state for when NFC permissions are denied.
         */
        fun nfcPermissionDenied(): PassportSimulatorUiState {
            return PassportSimulatorUiState(
                nfcAvailable = true,
                hasNfcPermission = false,
                errorMessage = "NFC permissions are required for simulation"
            )
        }
    }
}