package com.hddev.smartemu

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.BacProtocol
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for BAC protocol implementation with PassportHceService.
 * Tests the complete BAC authentication workflow.
 */
class BacIntegrationTest {
    
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
    
    @Test
    fun `BAC protocol integration with HCE service should work correctly`() {
        // Test that BAC protocol can be initialized with passport data
        val bacProtocol = BacProtocol()
        
        // Step 1: Initialize BAC with passport data
        val initResult = bacProtocol.initialize(validPassportData)
        assertTrue(initResult.success, "BAC initialization should succeed")
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
        
        // Step 2: Generate challenge (simulates GET CHALLENGE APDU)
        val challengeResult = bacProtocol.generateChallenge()
        assertTrue(challengeResult.success, "Challenge generation should succeed")
        assertEquals(BacProtocol.BacState.CHALLENGE_GENERATED, bacProtocol.getCurrentState())
        assertNotNull(challengeResult.data, "Challenge data should not be null")
        assertEquals(8, challengeResult.data!!.size, "Challenge should be 8 bytes")
        
        // Step 3: Process external authentication (simulates EXTERNAL AUTHENTICATE APDU)
        val mockAuthData = ByteArray(32) { it.toByte() } // Mock authentication data
        val authResult = bacProtocol.processExternalAuthenticate(mockAuthData)
        assertTrue(authResult.success, "Authentication should succeed")
        assertEquals(BacProtocol.BacState.AUTHENTICATED, bacProtocol.getCurrentState())
        assertNotNull(authResult.data, "Authentication response should not be null")
        
        // Step 4: Verify state can be reset
        bacProtocol.reset()
        assertEquals(BacProtocol.BacState.INITIAL, bacProtocol.getCurrentState())
    }
    
    @Test
    fun `BAC protocol should handle MRZ generation correctly`() {
        // Test that passport data can generate valid MRZ for BAC key derivation
        assertTrue(validPassportData.isValid(), "Test passport data should be valid")
        
        val mrzData = validPassportData.toMrzData()
        assertNotNull(mrzData, "MRZ data should not be null")
        assertTrue(mrzData.isNotEmpty(), "MRZ data should not be empty")
        
        // MRZ should be 88 characters (2 lines of 44 characters each)
        assertEquals(88, mrzData.length, "MRZ should be 88 characters long")
        
        // First line should start with "P<" (passport type)
        assertTrue(mrzData.startsWith("P<"), "MRZ should start with passport type indicator")
        
        // Should contain country code
        assertTrue(mrzData.contains("NLD"), "MRZ should contain country code")
    }
    
    @Test
    fun `BAC protocol should enforce correct state transitions`() {
        val bacProtocol = BacProtocol()
        
        // Cannot generate challenge without initialization
        val challengeBeforeInit = bacProtocol.generateChallenge()
        assertTrue(!challengeBeforeInit.success, "Challenge generation should fail without initialization")
        
        // Initialize protocol
        bacProtocol.initialize(validPassportData)
        
        // Cannot authenticate without challenge
        val mockAuthData = ByteArray(32) { it.toByte() }
        val authBeforeChallenge = bacProtocol.processExternalAuthenticate(mockAuthData)
        assertTrue(!authBeforeChallenge.success, "Authentication should fail without challenge")
        
        // Generate challenge
        val challengeResult = bacProtocol.generateChallenge()
        assertTrue(challengeResult.success, "Challenge generation should succeed after initialization")
        
        // Cannot generate challenge twice
        val secondChallenge = bacProtocol.generateChallenge()
        assertTrue(!secondChallenge.success, "Second challenge generation should fail")
        
        // Now authentication should work
        val authResult = bacProtocol.processExternalAuthenticate(mockAuthData)
        assertTrue(authResult.success, "Authentication should succeed after challenge")
        
        // Cannot authenticate again
        val secondAuth = bacProtocol.processExternalAuthenticate(mockAuthData)
        assertTrue(!secondAuth.success, "Second authentication should fail")
    }
    
    @Test
    fun `BAC protocol should validate authentication data`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        // Test with invalid authentication data (too short)
        val shortAuthData = ByteArray(16) { it.toByte() }
        val shortAuthResult = bacProtocol.processExternalAuthenticate(shortAuthData)
        assertTrue(!shortAuthResult.success, "Authentication with short data should fail")
        assertEquals(BacProtocol.BacState.FAILED, shortAuthResult.newState)
        
        // Reset and try again with valid length data
        bacProtocol.reset()
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        val validAuthData = ByteArray(32) { it.toByte() }
        val validAuthResult = bacProtocol.processExternalAuthenticate(validAuthData)
        assertTrue(validAuthResult.success, "Authentication with valid data should succeed")
        assertEquals(BacProtocol.BacState.AUTHENTICATED, validAuthResult.newState)
    }
    
    @Test
    fun `BAC protocol should handle passport data validation`() {
        val bacProtocol = BacProtocol()
        
        // Test with invalid passport data
        val invalidPassportData = PassportData(
            passportNumber = "", // Invalid empty passport number
            dateOfBirth = null,
            expiryDate = null
        )
        
        val invalidInitResult = bacProtocol.initialize(invalidPassportData)
        assertTrue(!invalidInitResult.success, "Initialization with invalid data should fail")
        assertEquals(BacProtocol.BacState.FAILED, invalidInitResult.newState)
        
        // Test with valid passport data
        val validInitResult = bacProtocol.initialize(validPassportData)
        assertTrue(validInitResult.success, "Initialization with valid data should succeed")
        assertEquals(BacProtocol.BacState.INITIAL, validInitResult.newState)
    }
}