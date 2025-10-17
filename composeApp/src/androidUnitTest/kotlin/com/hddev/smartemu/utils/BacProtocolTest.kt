package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for BAC (Basic Access Control) protocol implementation.
 */
class BacProtocolTest {
    
    private val validPassportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2025, 4, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "ANNA",
        lastName = "ERIKSSON",
        gender = "F"
    )
    
    private val invalidPassportData = PassportData(
        passportNumber = "", // Invalid empty passport number
        dateOfBirth = null,
        expiryDate = null
    )
    
    @Test
    fun `initialize with valid passport data should succeed`() {
        val bacProtocol = BacProtocol()
        
        val result = bacProtocol.initialize(validPassportData)
        
        assertTrue(result.success)
        assertEquals("BAC initialized", result.message)
        assertEquals(BacProtocol.BacState.INITIAL, result.newState)
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
    }
    
    @Test
    fun `initialize with invalid passport data should fail`() {
        val bacProtocol = BacProtocol()
        
        val result = bacProtocol.initialize(invalidPassportData)
        
        assertFalse(result.success)
        assertEquals("Invalid passport data", result.message)
        assertEquals(BacProtocol.BacState.FAILED, result.newState)
        assertEquals(BacProtocol.BacState.FAILED, bacProtocol.getCurrentState())
    }
    
    @Test
    fun `generate challenge in initial state should succeed`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        
        val result = bacProtocol.generateChallenge()
        
        assertTrue(result.success)
        assertEquals("Challenge generated", result.message)
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, result.newState)
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
        assertNotNull(result.data)
        assertEquals(8, result.data!!.size) // Challenge should be 8 bytes
    }
    
    @Test
    fun `generate challenge in wrong state should fail`() {
        val bacProtocol = BacProtocol()
        // Don't initialize - should be in wrong state
        
        val result = bacProtocol.generateChallenge()
        
        assertFalse(result.success)
        assertEquals("Invalid state for challenge generation", result.message)
    }
    
    @Test
    fun `generate challenge twice should fail on second attempt`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        
        // First challenge should succeed
        val firstResult = bacProtocol.generateChallenge()
        assertTrue(firstResult.success)
        
        // Second challenge should fail (wrong state)
        val secondResult = bacProtocol.generateChallenge()
        assertFalse(secondResult.success)
        assertEquals("Invalid state for challenge generation", secondResult.message)
    }
    
    @Test
    fun `process external authenticate with valid data should succeed`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        // Create mock authentication data (RND.IFD + K.IFD + additional data)
        val mockAuthData = ByteArray(32) { it.toByte() }
        
        val result = bacProtocol.processExternalAuthenticate(mockAuthData)
        
        assertTrue(result.success)
        assertEquals("BAC authentication successful", result.message)
        assertEquals(BacProtocol.BacState.AUTHENTICATED, result.newState)
        assertEquals(BacProtocol.BacState.AUTHENTICATED, bacProtocol.getCurrentState())
        assertNotNull(result.data)
    }
    
    @Test
    fun `process external authenticate in wrong state should fail`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        // Don't generate challenge - should be in wrong state
        
        val mockAuthData = ByteArray(32) { it.toByte() }
        val result = bacProtocol.processExternalAuthenticate(mockAuthData)
        
        assertFalse(result.success)
        assertEquals("Invalid state for authentication", result.message)
    }
    
    @Test
    fun `process external authenticate with invalid data should fail`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        // Create invalid authentication data (too short)
        val invalidAuthData = ByteArray(16) { it.toByte() }
        
        val result = bacProtocol.processExternalAuthenticate(invalidAuthData)
        
        assertFalse(result.success)
        assertEquals("Invalid authentication data", result.message)
        assertEquals(BacProtocol.BacState.FAILED, result.newState)
    }
    
    @Test
    fun `reset should return to initial state`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        // Verify we're in challenge generated state
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
        
        bacProtocol.reset()
        
        // Should be back to initial state
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
    }
    
    @Test
    fun `full BAC workflow should complete successfully`() {
        val bacProtocol = BacProtocol()
        
        // Step 1: Initialize
        val initResult = bacProtocol.initialize(validPassportData)
        assertTrue(initResult.success)
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
        
        // Step 2: Generate challenge
        val challengeResult = bacProtocol.generateChallenge()
        assertTrue(challengeResult.success)
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
        assertNotNull(challengeResult.data)
        assertEquals(8, challengeResult.data!!.size)
        
        // Step 3: Process authentication
        val mockAuthData = ByteArray(32) { it.toByte() }
        val authResult = bacProtocol.processExternalAuthenticate(mockAuthData)
        assertTrue(authResult.success)
        assertEquals(BacProtocol.BacState.AUTHENTICATED, bacProtocol.getCurrentState())
        assertNotNull(authResult.data)
        
        // Step 4: Reset
        bacProtocol.reset()
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
    }
    
    @Test
    fun `BAC state transitions should be enforced correctly`() {
        val bacProtocol = BacProtocol()
        
        // Should start in INITIAL state (after initialization)
        bacProtocol.initialize(validPassportData)
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
        
        // Can only generate challenge from INITIAL state
        assertTrue(bacProtocol.generateChallenge().success)
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
        
        // Cannot generate challenge again
        assertFalse(bacProtocol.generateChallenge().success)
        
        // Can only authenticate from CHALLENGE_GENERATED state
        val mockAuthData = ByteArray(32) { it.toByte() }
        assertTrue(bacProtocol.processExternalAuthenticate(mockAuthData).success)
        assertEquals(BacProtocol.BacState.AUTHENTICATED, bacProtocol.getCurrentState())
        
        // Cannot authenticate again
        assertFalse(bacProtocol.processExternalAuthenticate(mockAuthData).success)
    }
}