package com.hddev.smartemu.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationStatusTest {
    
    @Test
    fun testGetDescription() {
        assertEquals("Stopped", SimulationStatus.STOPPED.getDescription())
        assertEquals("Starting...", SimulationStatus.STARTING.getDescription())
        assertEquals("Active", SimulationStatus.ACTIVE.getDescription())
        assertEquals("Stopping...", SimulationStatus.STOPPING.getDescription())
        assertEquals("Error", SimulationStatus.ERROR.getDescription())
    }
    
    @Test
    fun testCanStart() {
        assertTrue(SimulationStatus.STOPPED.canStart())
        assertTrue(SimulationStatus.ERROR.canStart())
        
        assertFalse(SimulationStatus.STARTING.canStart())
        assertFalse(SimulationStatus.ACTIVE.canStart())
        assertFalse(SimulationStatus.STOPPING.canStart())
    }
    
    @Test
    fun testCanStop() {
        assertTrue(SimulationStatus.ACTIVE.canStop())
        assertTrue(SimulationStatus.STARTING.canStop())
        
        assertFalse(SimulationStatus.STOPPED.canStop())
        assertFalse(SimulationStatus.STOPPING.canStop())
        assertFalse(SimulationStatus.ERROR.canStop())
    }
    
    @Test
    fun testIsTransitioning() {
        assertTrue(SimulationStatus.STARTING.isTransitioning())
        assertTrue(SimulationStatus.STOPPING.isTransitioning())
        
        assertFalse(SimulationStatus.STOPPED.isTransitioning())
        assertFalse(SimulationStatus.ACTIVE.isTransitioning())
        assertFalse(SimulationStatus.ERROR.isTransitioning())
    }
    
    @Test
    fun testIsError() {
        assertTrue(SimulationStatus.ERROR.isError())
        
        assertFalse(SimulationStatus.STOPPED.isError())
        assertFalse(SimulationStatus.STARTING.isError())
        assertFalse(SimulationStatus.ACTIVE.isError())
        assertFalse(SimulationStatus.STOPPING.isError())
    }
    
    @Test
    fun testIsActiveOrStarting() {
        assertTrue(SimulationStatus.ACTIVE.isActiveOrStarting())
        assertTrue(SimulationStatus.STARTING.isActiveOrStarting())
        
        assertFalse(SimulationStatus.STOPPED.isActiveOrStarting())
        assertFalse(SimulationStatus.STOPPING.isActiveOrStarting())
        assertFalse(SimulationStatus.ERROR.isActiveOrStarting())
    }
    
    @Test
    fun testValidTransitions() {
        // From STOPPED
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STOPPED, SimulationStatus.STARTING))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STOPPED, SimulationStatus.ACTIVE))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STOPPED, SimulationStatus.STOPPING))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STOPPED, SimulationStatus.ERROR))
        
        // From STARTING
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STARTING, SimulationStatus.ACTIVE))
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STARTING, SimulationStatus.ERROR))
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STARTING, SimulationStatus.STOPPED))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STARTING, SimulationStatus.STOPPING))
        
        // From ACTIVE
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.ACTIVE, SimulationStatus.STOPPING))
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.ACTIVE, SimulationStatus.ERROR))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.ACTIVE, SimulationStatus.STARTING))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.ACTIVE, SimulationStatus.STOPPED))
        
        // From STOPPING
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STOPPING, SimulationStatus.STOPPED))
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.STOPPING, SimulationStatus.ERROR))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STOPPING, SimulationStatus.STARTING))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.STOPPING, SimulationStatus.ACTIVE))
        
        // From ERROR
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.ERROR, SimulationStatus.STOPPED))
        assertTrue(SimulationStatus.isValidTransition(SimulationStatus.ERROR, SimulationStatus.STARTING))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.ERROR, SimulationStatus.ACTIVE))
        assertFalse(SimulationStatus.isValidTransition(SimulationStatus.ERROR, SimulationStatus.STOPPING))
    }
    
    @Test
    fun testGetNextStatus() {
        assertEquals(SimulationStatus.STARTING, SimulationStatus.getNextStatus(SimulationStatus.STOPPED))
        assertEquals(SimulationStatus.ACTIVE, SimulationStatus.getNextStatus(SimulationStatus.STARTING))
        assertEquals(SimulationStatus.STOPPING, SimulationStatus.getNextStatus(SimulationStatus.ACTIVE))
        assertEquals(SimulationStatus.STOPPED, SimulationStatus.getNextStatus(SimulationStatus.STOPPING))
        assertNull(SimulationStatus.getNextStatus(SimulationStatus.ERROR))
    }
}