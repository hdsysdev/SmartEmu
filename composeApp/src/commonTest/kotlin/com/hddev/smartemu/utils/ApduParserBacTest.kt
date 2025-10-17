package com.hddev.smartemu.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for APDU parser BAC-related functionality.
 */
class ApduParserBacTest {
    
    @Test
    fun `parseExternalAuthenticateCommand should handle authentication data correctly`() {
        // Test EXTERNAL AUTHENTICATE command with authentication data
        val authData = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val apdu = byteArrayOf(
            0x00, // CLA
            0x82.toByte(), // INS (EXTERNAL AUTHENTICATE)
            0x00, // P1
            0x00, // P2
            authData.size.toByte() // LC
        ) + authData
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(result.isValid)
        assertNotNull(result.data)
        assertTrue(result.data!!.contentEquals(authData))
    }
    
    @Test
    fun `parseExternalAuthenticateCommand should handle empty authentication data`() {
        // Test EXTERNAL AUTHENTICATE command without authentication data
        val apdu = byteArrayOf(
            0x00, // CLA
            0x82.toByte(), // INS (EXTERNAL AUTHENTICATE)
            0x00, // P1
            0x00, // P2
            0x00 // LC (no data)
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(result.isValid)
        // Data should be null for empty authentication data
    }
    
    @Test
    fun `parseExternalAuthenticateCommand should handle invalid length`() {
        // Test EXTERNAL AUTHENTICATE command with invalid length
        val apdu = byteArrayOf(
            0x00, // CLA
            0x82.toByte(), // INS (EXTERNAL AUTHENTICATE)
            0x00, // P1
            0x00, // P2
            0x08, // LC (claims 8 bytes but no data follows)
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(!result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseGetChallengeCommand should validate challenge length`() {
        // Test GET CHALLENGE command with correct length (8 bytes)
        val apdu = byteArrayOf(
            0x00, // CLA
            0x84.toByte(), // INS (GET CHALLENGE)
            0x00, // P1
            0x00, // P2
            0x08 // LE (expected 8 bytes)
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduParser.ApduCommandType.GET_CHALLENGE, result.commandType)
        assertTrue(result.isValid)
    }
    
    @Test
    fun `parseGetChallengeCommand should reject invalid challenge length`() {
        // Test GET CHALLENGE command with incorrect length (16 bytes)
        val apdu = byteArrayOf(
            0x00, // CLA
            0x84.toByte(), // INS (GET CHALLENGE)
            0x00, // P1
            0x00, // P2
            0x10 // LE (16 bytes - invalid for BAC)
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduParser.ApduCommandType.GET_CHALLENGE, result.commandType)
        assertTrue(!result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
}