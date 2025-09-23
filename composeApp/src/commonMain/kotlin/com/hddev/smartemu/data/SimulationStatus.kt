package com.hddev.smartemu.data

/**
 * Represents the current status of the NFC passport simulation.
 * Used to track the simulation lifecycle and provide appropriate UI feedback.
 */
enum class SimulationStatus {
    /**
     * Simulation is not running and can be started.
     */
    STOPPED,
    
    /**
     * Simulation is in the process of starting up.
     */
    STARTING,
    
    /**
     * Simulation is active and ready to handle NFC connections.
     */
    ACTIVE,
    
    /**
     * Simulation is in the process of stopping.
     */
    STOPPING,
    
    /**
     * Simulation encountered an error and cannot continue.
     */
    ERROR;
    
    /**
     * Returns a human-readable description of the simulation status.
     */
    fun getDescription(): String {
        return when (this) {
            STOPPED -> "Stopped"
            STARTING -> "Starting..."
            ACTIVE -> "Active"
            STOPPING -> "Stopping..."
            ERROR -> "Error"
        }
    }
    
    /**
     * Returns true if the simulation can be started from this status.
     */
    fun canStart(): Boolean {
        return this == STOPPED || this == ERROR
    }
    
    /**
     * Returns true if the simulation can be stopped from this status.
     */
    fun canStop(): Boolean {
        return this == ACTIVE || this == STARTING
    }
    
    /**
     * Returns true if the simulation is in a transitional state.
     */
    fun isTransitioning(): Boolean {
        return this == STARTING || this == STOPPING
    }
    
    /**
     * Returns true if the simulation is in an error state.
     */
    fun isError(): Boolean {
        return this == ERROR
    }
    
    /**
     * Returns true if the simulation is currently running or starting.
     */
    fun isActiveOrStarting(): Boolean {
        return this == ACTIVE || this == STARTING
    }
    
    companion object {
        /**
         * Validates if a transition from one status to another is valid.
         */
        fun isValidTransition(from: SimulationStatus, to: SimulationStatus): Boolean {
            return when (from) {
                STOPPED -> to == STARTING
                STARTING -> to == ACTIVE || to == ERROR || to == STOPPED
                ACTIVE -> to == STOPPING || to == ERROR
                STOPPING -> to == STOPPED || to == ERROR
                ERROR -> to == STOPPED || to == STARTING
            }
        }
        
        /**
         * Gets the next expected status in a normal flow.
         */
        fun getNextStatus(current: SimulationStatus): SimulationStatus? {
            return when (current) {
                STOPPED -> STARTING
                STARTING -> ACTIVE
                ACTIVE -> STOPPING
                STOPPING -> STOPPED
                ERROR -> null // Error state requires manual intervention
            }
        }
    }
}