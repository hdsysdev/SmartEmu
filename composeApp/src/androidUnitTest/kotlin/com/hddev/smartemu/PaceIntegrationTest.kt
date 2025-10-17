package com.hddev.smartemu

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.PaceProtocol
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for PACE protocol implementation.
 * Tests the complete PACE workflow and integration with passport data.
 */
class PaceIntegrationTest {
    
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
    fun `complete PACE authentication workflow should succeed`() {
        val paceProtocol = PaceProtocol()
        
        // Step 1: Initialize PACE protocol
        val initResult = paceProtocol.initialize(validPassportData)
        assertTrue(initResult.success, "PACE initialization should succeed")
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        assertFalse(paceProtocol.isAuthenticated())
        
        // Step 2: Process MSE SET AT command
        val mseData = byteArrayOf(
            0x80.toByte(), 0x0A.toByte(), 0x04.toByte(), 0x00.toByte(), 
            0x7F.toByte(), 0x00.toByte(), 0x07.toByte(), 0x02.toByte(), 
            0x02.toByte(), 0x04.toByte(), 0x02.toByte(), 0x02.toByte()
        )
        val mseResult = paceProtocol.processMseSetAt(mseData)
        assertTrue(mseResult.success, "MSE SET AT should succeed")
        assertEquals(PaceProtocol.PaceState.MSE_SET_AT_PROCESSED, paceProtocol.getCurrentState())
        
        // Step 3: Generate encrypted nonce (GENERAL AUTHENTICATE step 1)
        val nonceResult = paceProtocol.generateEncryptedNonce()
        assertTrue(nonceResult.success, "Nonce generation should succeed")
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        assertNotNull(nonceResult.data, "Nonce data should be present")
        assertEquals(16, nonceResult.data!!.size, "Nonce should be 16 bytes")
        
        // Step 4: Process terminal public key (GENERAL AUTHENTICATE step 2)
        val terminalPubKey = ByteArray(65) { (it + 1).toByte() } // Mock EC public key
        val keyResult = paceProtocol.processTerminalPublicKey(terminalPubKey)
        assertTrue(keyResult.success, "Terminal public key processing should succeed")
        assertEquals(PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS, paceProtocol.getCurrentState())
        assertNotNull(keyResult.data, "Card public key should be returned")
        
        // Step 5: Perform key agreement (GENERAL AUTHENTICATE step 3)
        val agreementResult = paceProtocol.performKeyAgreement()
        assertTrue(agreementResult.success, "Key agreement should succeed")
        assertEquals(PaceProtocol.PaceState.MUTUAL_AUTHENTICATION, paceProtocol.getCurrentState())
        assertNotNull(agreementResult.data, "Authentication token should be returned")
        
        // Step 6: Verify terminal authentication (GENERAL AUTHENTICATE step 4)
        val terminalToken = ByteArray(16) { (it + 10).toByte() } // Valid authentication token
        val authResult = paceProtocol.verifyTerminalAuthentication(terminalToken)
        assertTrue(authResult.success, "Terminal authentication should succeed")
        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, paceProtocol.getCurrentState())
        assertTrue(paceProtocol.isAuthenticated(), "Protocol should be authenticated")
        
        // Verify final state
        assertEquals(5, paceProtocol.getCurrentStep(), "Should be at final step")
    }
    
    @Test
    fun `PACE protocol should handle authentication failure correctly`() {
        val paceProtocol = PaceProtocol()
        
        // Initialize and go through steps until authentication
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        paceProtocol.processTerminalPublicKey(terminalPubKey)
        paceProtocol.performKeyAgreement()
        
        // Try to authenticate with invalid token (empty)
        val invalidToken = byteArrayOf()
        val authResult = paceProtocol.verifyTerminalAuthentication(invalidToken)
        
        assertFalse(authResult.success, "Authentication with invalid token should fail")
        assertEquals(PaceProtocol.PaceState.FAILED, paceProtocol.getCurrentState())
        assertFalse(paceProtocol.isAuthenticated(), "Protocol should not be authenticated")
    }
    
    @Test
    fun `PACE protocol should enforce correct state transitions`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        // Cannot generate nonce without MSE SET AT
        val nonceResult = paceProtocol.generateEncryptedNonce()
        assertFalse(nonceResult.success, "Should not generate nonce without MSE SET AT")
        
        // Process MSE SET AT first
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        val mseResult = paceProtocol.processMseSetAt(mseData)
        assertTrue(mseResult.success, "MSE SET AT should succeed")
        
        // Cannot process terminal key without nonce
        val terminalPubKey = ByteArray(65) { it.toByte() }
        val keyResult1 = paceProtocol.processTerminalPublicKey(terminalPubKey)
        assertFalse(keyResult1.success, "Should not process terminal key without nonce")
        
        // Generate nonce first
        val nonceResult2 = paceProtocol.generateEncryptedNonce()
        assertTrue(nonceResult2.success, "Nonce generation should succeed")
        
        // Now terminal key processing should work
        val keyResult2 = paceProtocol.processTerminalPublicKey(terminalPubKey)
        assertTrue(keyResult2.success, "Terminal key processing should succeed after nonce")
    }
    
    @Test
    fun `PACE protocol reset should clear all state`() {
        val paceProtocol = PaceProtocol()
        
        // Go through several steps
        paceProtocol.initialize(validPassportData)
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        // Verify we're in progress
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        assertEquals(2, paceProtocol.getCurrentStep())
        
        // Reset protocol
        paceProtocol.reset()
        
        // Verify everything is cleared
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        assertEquals(0, paceProtocol.getCurrentStep())
        assertFalse(paceProtocol.isAuthenticated())
    }
    
    @Test
    fun `PACE protocol should handle multiple authentication attempts`() {
        val paceProtocol = PaceProtocol()
        
        // Complete first authentication
        paceProtocol.initialize(validPassportData)
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        paceProtocol.processTerminalPublicKey(terminalPubKey)
        paceProtocol.performKeyAgreement()
        
        val terminalToken = ByteArray(16) { (it + 5).toByte() }
        val authResult1 = paceProtocol.verifyTerminalAuthentication(terminalToken)
        assertTrue(authResult1.success, "First authentication should succeed")
        assertTrue(paceProtocol.isAuthenticated())
        
        // Try to authenticate again (should fail - already authenticated)
        val authResult2 = paceProtocol.verifyTerminalAuthentication(terminalToken)
        assertFalse(authResult2.success, "Second authentication should fail")
        
        // Reset and try again
        paceProtocol.reset()
        assertFalse(paceProtocol.isAuthenticated())
        
        // Should be able to start fresh authentication
        val initResult = paceProtocol.initialize(validPassportData)
        assertTrue(initResult.success, "Re-initialization should succeed")
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
    }
}