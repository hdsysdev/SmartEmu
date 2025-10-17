package com.hddev.smartemu.utils

import com.hddev.smartemu.data.PassportData
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for PACE (Password Authenticated Connection Establishment) protocol implementation.
 */
class PaceProtocolTest {
    
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
        val paceProtocol = PaceProtocol()
        
        val result = paceProtocol.initialize(validPassportData)
        
        assertTrue(result.success)
        assertEquals("PACE initialized", result.message)
        assertEquals(PaceProtocol.PaceState.INITIAL, result.newState)
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        assertEquals(0, paceProtocol.getCurrentStep())
    }
    
    @Test
    fun `initialize with invalid passport data should fail`() {
        val paceProtocol = PaceProtocol()
        
        val result = paceProtocol.initialize(invalidPassportData)
        
        assertFalse(result.success)
        assertEquals("Invalid passport data", result.message)
        assertEquals(PaceProtocol.PaceState.FAILED, result.newState)
        assertEquals(PaceProtocol.PaceState.FAILED, paceProtocol.getCurrentState())
    }
    
    @Test
    fun `process MSE SET AT in initial state should succeed`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte(), 0x04.toByte(), 0x00.toByte(), 0x7F.toByte(), 0x00.toByte(), 0x07.toByte(), 0x02.toByte(), 0x02.toByte(), 0x04.toByte(), 0x02.toByte(), 0x02.toByte())
        val result = paceProtocol.processMseSetAt(mseData)
        
        assertTrue(result.success)
        assertEquals("MSE SET AT processed", result.message)
        assertEquals(PaceProtocol.PaceState.MSE_SET_AT_PROCESSED, result.newState)
        assertEquals(PaceProtocol.PaceState.MSE_SET_AT_PROCESSED, paceProtocol.getCurrentState())
        assertEquals(2, result.nextStep)
    }
    
    @Test
    fun `process MSE SET AT in wrong state should fail`() {
        val paceProtocol = PaceProtocol()
        // Don't initialize - should be in wrong state
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        val result = paceProtocol.processMseSetAt(mseData)
        
        assertFalse(result.success)
        assertEquals("Invalid state for MSE SET AT", result.message)
    }
    
    @Test
    fun `process MSE SET AT with empty data should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val result = paceProtocol.processMseSetAt(byteArrayOf())
        
        assertFalse(result.success)
        assertEquals("Invalid MSE SET AT data", result.message)
        assertEquals(PaceProtocol.PaceState.FAILED, result.newState)
    }
    
    @Test
    fun `generate encrypted nonce after MSE SET AT should succeed`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        
        val result = paceProtocol.generateEncryptedNonce()
        
        assertTrue(result.success)
        assertEquals("Encrypted nonce generated", result.message)
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, result.newState)
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        assertNotNull(result.data)
        assertEquals(16, result.data!!.size) // Nonce should be 16 bytes
        assertEquals(3, result.nextStep)
    }
    
    @Test
    fun `generate encrypted nonce in wrong state should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        // Don't process MSE SET AT - should be in wrong state
        
        val result = paceProtocol.generateEncryptedNonce()
        
        assertFalse(result.success)
        assertEquals("Invalid state for nonce generation", result.message)
    }
    
    @Test
    fun `process terminal public key after nonce generation should succeed`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() } // Mock EC public key
        val result = paceProtocol.processTerminalPublicKey(terminalPubKey)
        
        assertTrue(result.success)
        assertEquals("Key agreement initiated", result.message)
        assertEquals(PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS, result.newState)
        assertEquals(PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS, paceProtocol.getCurrentState())
        assertNotNull(result.data) // Should return card's public key
        assertEquals(4, result.nextStep)
    }
    
    @Test
    fun `process terminal public key in wrong state should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        // Don't generate nonce - should be in wrong state
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        val result = paceProtocol.processTerminalPublicKey(terminalPubKey)
        
        assertFalse(result.success)
        assertEquals("Invalid state for key processing", result.message)
    }
    
    @Test
    fun `perform key agreement after terminal public key should succeed`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        paceProtocol.processTerminalPublicKey(terminalPubKey)
        
        val result = paceProtocol.performKeyAgreement()
        
        assertTrue(result.success)
        assertEquals("Key agreement completed", result.message)
        assertEquals(PaceProtocol.PaceState.MUTUAL_AUTHENTICATION, result.newState)
        assertEquals(PaceProtocol.PaceState.MUTUAL_AUTHENTICATION, paceProtocol.getCurrentState())
        assertNotNull(result.data) // Should return authentication token
        assertEquals(5, result.nextStep)
    }
    
    @Test
    fun `perform key agreement in wrong state should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        // Don't process terminal public key - should be in wrong state
        
        val result = paceProtocol.performKeyAgreement()
        
        assertFalse(result.success)
        assertEquals("Invalid state for key agreement", result.message)
    }
    
    @Test
    fun `verify terminal authentication with valid token should succeed`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        paceProtocol.processTerminalPublicKey(terminalPubKey)
        paceProtocol.performKeyAgreement()
        
        val terminalToken = ByteArray(16) { (it + 5).toByte() } // Valid token
        val result = paceProtocol.verifyTerminalAuthentication(terminalToken)
        
        assertTrue(result.success)
        assertEquals("PACE authentication successful", result.message)
        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, result.newState)
        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, paceProtocol.getCurrentState())
        assertTrue(paceProtocol.isAuthenticated())
    }
    
    @Test
    fun `verify terminal authentication with invalid token should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        paceProtocol.processTerminalPublicKey(terminalPubKey)
        paceProtocol.performKeyAgreement()
        
        val invalidToken = byteArrayOf() // Empty token
        val result = paceProtocol.verifyTerminalAuthentication(invalidToken)
        
        assertFalse(result.success)
        assertEquals("Authentication token verification failed", result.message)
        assertEquals(PaceProtocol.PaceState.FAILED, result.newState)
        assertFalse(paceProtocol.isAuthenticated())
    }
    
    @Test
    fun `verify terminal authentication in wrong state should fail`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        // Don't perform key agreement - should be in wrong state
        
        val terminalToken = ByteArray(16) { it.toByte() }
        val result = paceProtocol.verifyTerminalAuthentication(terminalToken)
        
        assertFalse(result.success)
        assertEquals("Invalid state for authentication verification", result.message)
    }
    
    @Test
    fun `reset should return to initial state`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        // Verify we're in nonce generated state
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        
        paceProtocol.reset()
        
        // Should be back to initial state
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        assertEquals(0, paceProtocol.getCurrentStep())
        assertFalse(paceProtocol.isAuthenticated())
    }
    
    @Test
    fun `full PACE workflow should complete successfully`() {
        val paceProtocol = PaceProtocol()
        
        // Step 1: Initialize
        val initResult = paceProtocol.initialize(validPassportData)
        assertTrue(initResult.success)
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        
        // Step 2: Process MSE SET AT
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        val mseResult = paceProtocol.processMseSetAt(mseData)
        assertTrue(mseResult.success)
        assertEquals(PaceProtocol.PaceState.MSE_SET_AT_PROCESSED, paceProtocol.getCurrentState())
        
        // Step 3: Generate encrypted nonce
        val nonceResult = paceProtocol.generateEncryptedNonce()
        assertTrue(nonceResult.success)
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        assertNotNull(nonceResult.data)
        
        // Step 4: Process terminal public key
        val terminalPubKey = ByteArray(65) { it.toByte() }
        val keyResult = paceProtocol.processTerminalPublicKey(terminalPubKey)
        assertTrue(keyResult.success)
        assertEquals(PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS, paceProtocol.getCurrentState())
        assertNotNull(keyResult.data)
        
        // Step 5: Perform key agreement
        val agreementResult = paceProtocol.performKeyAgreement()
        assertTrue(agreementResult.success)
        assertEquals(PaceProtocol.PaceState.MUTUAL_AUTHENTICATION, paceProtocol.getCurrentState())
        assertNotNull(agreementResult.data)
        
        // Step 6: Verify terminal authentication
        val terminalToken = ByteArray(16) { (it + 5).toByte() }
        val authResult = paceProtocol.verifyTerminalAuthentication(terminalToken)
        assertTrue(authResult.success)
        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, paceProtocol.getCurrentState())
        assertTrue(paceProtocol.isAuthenticated())
        
        // Step 7: Reset
        paceProtocol.reset()
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        assertFalse(paceProtocol.isAuthenticated())
    }
    
    @Test
    fun `PACE state transitions should be enforced correctly`() {
        val paceProtocol = PaceProtocol()
        
        // Should start in INITIAL state (after initialization)
        paceProtocol.initialize(validPassportData)
        assertEquals(PaceProtocol.PaceState.INITIAL, paceProtocol.getCurrentState())
        
        // Can only process MSE SET AT from INITIAL state
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        assertTrue(paceProtocol.processMseSetAt(mseData).success)
        assertEquals(PaceProtocol.PaceState.MSE_SET_AT_PROCESSED, paceProtocol.getCurrentState())
        
        // Cannot process MSE SET AT again
        assertFalse(paceProtocol.processMseSetAt(mseData).success)
        
        // Can only generate nonce from MSE_SET_AT_PROCESSED state
        assertTrue(paceProtocol.generateEncryptedNonce().success)
        assertEquals(PaceProtocol.PaceState.NONCE_GENERATED, paceProtocol.getCurrentState())
        
        // Cannot generate nonce again
        assertFalse(paceProtocol.generateEncryptedNonce().success)
        
        // Can only process terminal public key from NONCE_GENERATED state
        val terminalPubKey = ByteArray(65) { it.toByte() }
        assertTrue(paceProtocol.processTerminalPublicKey(terminalPubKey).success)
        assertEquals(PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS, paceProtocol.getCurrentState())
        
        // Can only perform key agreement from KEY_AGREEMENT_IN_PROGRESS state
        assertTrue(paceProtocol.performKeyAgreement().success)
        assertEquals(PaceProtocol.PaceState.MUTUAL_AUTHENTICATION, paceProtocol.getCurrentState())
        
        // Can only verify authentication from MUTUAL_AUTHENTICATION state
        val terminalToken = ByteArray(16) { it.toByte() }
        assertTrue(paceProtocol.verifyTerminalAuthentication(terminalToken).success)
        assertEquals(PaceProtocol.PaceState.AUTHENTICATED, paceProtocol.getCurrentState())
        
        // Cannot verify authentication again
        assertFalse(paceProtocol.verifyTerminalAuthentication(terminalToken).success)
    }
    
    @Test
    fun `PACE protocol steps should increment correctly`() {
        val paceProtocol = PaceProtocol()
        paceProtocol.initialize(validPassportData)
        
        assertEquals(0, paceProtocol.getCurrentStep())
        
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        val mseResult = paceProtocol.processMseSetAt(mseData)
        assertEquals(1, paceProtocol.getCurrentStep())
        assertEquals(2, mseResult.nextStep)
        
        val nonceResult = paceProtocol.generateEncryptedNonce()
        assertEquals(2, paceProtocol.getCurrentStep())
        assertEquals(3, nonceResult.nextStep)
        
        val terminalPubKey = ByteArray(65) { it.toByte() }
        val keyResult = paceProtocol.processTerminalPublicKey(terminalPubKey)
        assertEquals(3, paceProtocol.getCurrentStep())
        assertEquals(4, keyResult.nextStep)
        
        val agreementResult = paceProtocol.performKeyAgreement()
        assertEquals(4, paceProtocol.getCurrentStep())
        assertEquals(5, agreementResult.nextStep)
        
        val terminalToken = ByteArray(16) { it.toByte() }
        paceProtocol.verifyTerminalAuthentication(terminalToken)
        assertEquals(5, paceProtocol.getCurrentStep())
    }
}