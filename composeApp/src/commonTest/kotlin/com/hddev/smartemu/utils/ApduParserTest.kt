package com.hddev.smartemu.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApduParserTest {
    
    companion object {
        // Test APDU commands
        private val PASSPORT_AID = ApduParser.PASSPORT_AID
        
        private val SELECT_PASSPORT_APDU = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x08.toByte()
        ) + PASSPORT_AID
        
        private val SELECT_UNKNOWN_APDU = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x04.toByte(),
            0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
        )
        
        private val READ_BINARY_APDU = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte()
        )
        
        private val GET_CHALLENGE_APDU = byteArrayOf(
            0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte()
        )
        
        private val EXTERNAL_AUTH_APDU = byteArrayOf(
            0x00.toByte(), 0x82.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte()
        ) + ByteArray(16) { 0x00.toByte() }
        
        private val INTERNAL_AUTH_APDU = byteArrayOf(
            0x00.toByte(), 0x88.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte()
        ) + ByteArray(8) { 0x00.toByte() }
        
        private val UNSUPPORTED_APDU = byteArrayOf(
            0x00.toByte(), 0xFF.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        
        private val INVALID_CLASS_APDU = byteArrayOf(
            0xFF.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte()
        )
    }
    
    @Test
    fun `parseApduCommand handles null command`() {
        val result = ApduParser.parseApduCommand(null)
        
        assertEquals(ApduParser.ApduCommandType.INVALID, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles empty command`() {
        val result = ApduParser.parseApduCommand(byteArrayOf())
        
        assertEquals(ApduParser.ApduCommandType.INVALID, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles too short command`() {
        val shortApdu = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte())
        val result = ApduParser.parseApduCommand(shortApdu)
        
        assertEquals(ApduParser.ApduCommandType.INVALID, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles invalid class`() {
        val result = ApduParser.parseApduCommand(INVALID_CLASS_APDU)
        
        assertEquals(ApduParser.ApduCommandType.INVALID, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_CLASS_NOT_SUPPORTED))
    }
    
    @Test
    fun `parseApduCommand handles unsupported instruction`() {
        val result = ApduParser.parseApduCommand(UNSUPPORTED_APDU)
        
        assertEquals(ApduParser.ApduCommandType.UNSUPPORTED, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_INSTRUCTION_NOT_SUPPORTED))
    }
    
    @Test
    fun `parseApduCommand handles valid SELECT passport AID`() {
        val result = ApduParser.parseApduCommand(SELECT_PASSPORT_APDU)
        
        assertEquals(ApduParser.ApduCommandType.SELECT, result.commandType)
        assertTrue(result.isValid)
        assertNull(result.errorResponse)
        assertNotNull(result.data)
        assertTrue(result.data!!.contentEquals(PASSPORT_AID))
    }
    
    @Test
    fun `parseApduCommand handles SELECT unknown AID`() {
        val result = ApduParser.parseApduCommand(SELECT_UNKNOWN_APDU)
        
        assertEquals(ApduParser.ApduCommandType.SELECT, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_FILE_NOT_FOUND))
        assertNotNull(result.data)
        assertTrue(result.data!!.contentEquals(byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte())))
    }
    
    @Test
    fun `parseApduCommand handles SELECT with wrong length`() {
        val shortSelectApdu = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte())
        val result = ApduParser.parseApduCommand(shortSelectApdu)
        
        assertEquals(ApduParser.ApduCommandType.SELECT, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles SELECT with data length mismatch`() {
        val mismatchSelectApdu = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), 0x08.toByte(),
            0x12.toByte(), 0x34.toByte() // Only 2 bytes instead of 8
        )
        val result = ApduParser.parseApduCommand(mismatchSelectApdu)
        
        assertEquals(ApduParser.ApduCommandType.SELECT, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles valid READ BINARY`() {
        val result = ApduParser.parseApduCommand(READ_BINARY_APDU)
        
        assertEquals(ApduParser.ApduCommandType.READ_BINARY, result.commandType)
        assertTrue(result.isValid)
        assertNull(result.errorResponse)
    }
    
    @Test
    fun `parseApduCommand handles valid GET CHALLENGE`() {
        val result = ApduParser.parseApduCommand(GET_CHALLENGE_APDU)
        
        assertEquals(ApduParser.ApduCommandType.GET_CHALLENGE, result.commandType)
        assertTrue(result.isValid)
        assertNull(result.errorResponse)
    }
    
    @Test
    fun `parseApduCommand handles GET CHALLENGE with wrong length`() {
        val wrongLengthChallenge = byteArrayOf(
            0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte(), 0x10.toByte()
        )
        val result = ApduParser.parseApduCommand(wrongLengthChallenge)
        
        assertEquals(ApduParser.ApduCommandType.GET_CHALLENGE, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles GET CHALLENGE without Le field`() {
        val noLeChallenge = byteArrayOf(
            0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        val result = ApduParser.parseApduCommand(noLeChallenge)
        
        assertEquals(ApduParser.ApduCommandType.GET_CHALLENGE, result.commandType)
        assertFalse(result.isValid)
        assertNotNull(result.errorResponse)
        assertTrue(result.errorResponse!!.contentEquals(ApduParser.SW_WRONG_LENGTH))
    }
    
    @Test
    fun `parseApduCommand handles valid EXTERNAL AUTHENTICATE`() {
        val result = ApduParser.parseApduCommand(EXTERNAL_AUTH_APDU)
        
        assertEquals(ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(result.isValid)
        assertNull(result.errorResponse)
    }
    
    @Test
    fun `parseApduCommand handles valid INTERNAL AUTHENTICATE`() {
        val result = ApduParser.parseApduCommand(INTERNAL_AUTH_APDU)
        
        assertEquals(ApduParser.ApduCommandType.INTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(result.isValid)
        assertNull(result.errorResponse)
    }
    
    @Test
    fun `generateChallengeResponse returns correct format`() {
        val response = ApduParser.generateChallengeResponse()
        
        // Should be 8 bytes challenge + 2 bytes SW_SUCCESS = 10 bytes total
        assertEquals(10, response.size)
        
        // Last 2 bytes should be SW_SUCCESS
        val statusWord = response.takeLast(2).toByteArray()
        assertTrue(statusWord.contentEquals(ApduParser.SW_SUCCESS))
        
        // First 8 bytes should be the challenge (can be any value)
        val challenge = response.sliceArray(0 until 8)
        assertEquals(8, challenge.size)
    }
    
    @Test
    fun `generateChallengeResponse generates different challenges`() {
        val response1 = ApduParser.generateChallengeResponse()
        val response2 = ApduParser.generateChallengeResponse()
        
        // Challenges should be different (very unlikely to be the same)
        val challenge1 = response1.sliceArray(0 until 8)
        val challenge2 = response2.sliceArray(0 until 8)
        
        // This test might occasionally fail due to randomness, but it's extremely unlikely
        assertFalse(challenge1.contentEquals(challenge2))
    }
    
    @Test
    fun `toHexString converts bytes correctly`() {
        val testBytes = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0xFF.toByte(), 0x12.toByte())
        val hexString = ApduParser.run { testBytes.toHexString() }
        
        assertEquals("00A4FF12", hexString)
    }
    
    @Test
    fun `toHexString handles empty array`() {
        val emptyBytes = byteArrayOf()
        val hexString = ApduParser.run { emptyBytes.toHexString() }
        
        assertEquals("", hexString)
    }
    
    @Test
    fun `toHexString handles single byte`() {
        val singleByte = byteArrayOf(0xAB.toByte())
        val hexString = ApduParser.run { singleByte.toHexString() }
        
        assertEquals("AB", hexString)
    }
    
    @Test
    fun `ApduParseResult equals works correctly`() {
        val result1 = ApduParser.ApduParseResult(
            commandType = ApduParser.ApduCommandType.SELECT,
            isValid = true,
            data = byteArrayOf(0x01.toByte(), 0x02.toByte())
        )
        
        val result2 = ApduParser.ApduParseResult(
            commandType = ApduParser.ApduCommandType.SELECT,
            isValid = true,
            data = byteArrayOf(0x01.toByte(), 0x02.toByte())
        )
        
        val result3 = ApduParser.ApduParseResult(
            commandType = ApduParser.ApduCommandType.READ_BINARY,
            isValid = true,
            data = byteArrayOf(0x01.toByte(), 0x02.toByte())
        )
        
        assertEquals(result1, result2)
        assertFalse(result1.equals(result3))
    }
    
    @Test
    fun `ApduParseResult hashCode works correctly`() {
        val result1 = ApduParser.ApduParseResult(
            commandType = ApduParser.ApduCommandType.SELECT,
            isValid = true,
            data = byteArrayOf(0x01.toByte(), 0x02.toByte())
        )
        
        val result2 = ApduParser.ApduParseResult(
            commandType = ApduParser.ApduCommandType.SELECT,
            isValid = true,
            data = byteArrayOf(0x01.toByte(), 0x02.toByte())
        )
        
        assertEquals(result1.hashCode(), result2.hashCode())
    }
}