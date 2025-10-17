package com.hddev.smartemu

import com.hddev.smartemu.utils.BacProtocol
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Simple compilation test for BAC protocol to verify it compiles correctly.
 */
class BacProtocolCompilationTest {
    
    @Test
    fun `BAC protocol should compile and instantiate correctly`() {
        val bacProtocol = BacProtocol()
        assertNotNull(bacProtocol, "BAC protocol should instantiate")
        
        val initialState = bacProtocol.getCurrentState()
        assertNotNull(initialState, "BAC protocol should have initial state")
    }
}