package com.hddev.smartemu

import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.smartcards.ISO7816
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for SCUBA library usage in PassportHceService.
 */
class ScubaIntegrationTest {

    @Test
    fun `test SCUBA CommandAPDU creation and validation`() {
        // Test valid SELECT command APDU
        val selectApdu = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, // SELECT command header
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01 // Passport AID
        )
        
        val command = CommandAPDU(selectApdu)
        
        assertEquals(0x00, command.cla)
        assertEquals(0xA4, command.ins)
        assertEquals(0x04, command.p1)
        assertEquals(0x00, command.p2)
        assertEquals(7, command.lc)
        assertNotNull(command.data)
        assertEquals(7, command.data.size)
    }

    @Test
    fun `test SCUBA ResponseAPDU creation`() {
        // Test success response
        val successResponse = ResponseAPDU(ISO7816.SW_NO_ERROR)
        assertEquals(ISO7816.SW_NO_ERROR.toInt(), successResponse.sw.toInt())
        assertEquals(2, successResponse.bytes.size) // Only status word
        
        // Test response with data
        val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val dataResponse = ResponseAPDU(testData + byteArrayOf(0x90.toByte(), 0x00))
        assertEquals(ISO7816.SW_NO_ERROR.toInt(), dataResponse.sw.toInt())
        assertEquals(6, dataResponse.bytes.size) // Data + status word
        assertTrue(dataResponse.data.contentEquals(testData))
    }

    @Test
    fun `test SCUBA error response creation`() {
        val errorCodes = listOf(
            ISO7816.SW_FILE_NOT_FOUND,
            ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED,
            ISO7816.SW_WRONG_LENGTH,
            ISO7816.SW_CLA_NOT_SUPPORTED,
            ISO7816.SW_INS_NOT_SUPPORTED
        )
        
        errorCodes.forEach { errorCode ->
            val response = ResponseAPDU(errorCode)
            assertEquals(errorCode.toInt(), response.sw.toInt())
            assertEquals(2, response.bytes.size)
        }
    }

    @Test
    fun `test passport AID validation`() {
        val passportAid = byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
        )
        
        // Create SELECT command for passport AID
        val selectCommand = CommandAPDU(
            0x00, 0xA4, 0x04, 0x00, 
            passportAid
        )
        
        assertEquals(0xA4, selectCommand.ins) // SELECT instruction
        assertEquals(0x04, selectCommand.p1) // Select by DF name
        assertTrue(selectCommand.data.contentEquals(passportAid))
    }

    @Test
    fun `test GET CHALLENGE command structure`() {
        val getChallengeApdu = CommandAPDU(0x00, 0x84, 0x00, 0x00, 8)
        
        assertEquals(0x00, getChallengeApdu.cla)
        assertEquals(0x84, getChallengeApdu.ins)
        assertEquals(0x00, getChallengeApdu.p1)
        assertEquals(0x00, getChallengeApdu.p2)
        assertEquals(8, getChallengeApdu.le) // Expected response length
    }

    @Test
    fun `test EXTERNAL AUTHENTICATE command structure`() {
        val authData = ByteArray(16) { it.toByte() } // Mock authentication data
        val externalAuthApdu = CommandAPDU(0x00, 0x82, 0x00, 0x00, authData)
        
        assertEquals(0x00, externalAuthApdu.cla)
        assertEquals(0x82, externalAuthApdu.ins)
        assertEquals(0x00, externalAuthApdu.p1)
        assertEquals(0x00, externalAuthApdu.p2)
        assertEquals(16, externalAuthApdu.lc)
        assertTrue(externalAuthApdu.data.contentEquals(authData))
    }

    @Test
    fun `test INTERNAL AUTHENTICATE command structure`() {
        val challengeData = ByteArray(8) { it.toByte() } // Mock challenge data
        val internalAuthApdu = CommandAPDU(0x00, 0x88, 0x00, 0x00, challengeData, 256)
        
        assertEquals(0x00, internalAuthApdu.cla)
        assertEquals(0x88, internalAuthApdu.ins)
        assertEquals(0x00, internalAuthApdu.p1)
        assertEquals(0x00, internalAuthApdu.p2)
        assertEquals(8, internalAuthApdu.lc)
        assertEquals(256, internalAuthApdu.le)
        assertTrue(internalAuthApdu.data.contentEquals(challengeData))
    }

    @Test
    fun `test READ BINARY command structure`() {
        val readBinaryApdu = CommandAPDU(0x00, 0xB0, 0x00, 0x00, 256)
        
        assertEquals(0x00, readBinaryApdu.cla)
        assertEquals(0xB0, readBinaryApdu.ins)
        assertEquals(0x00, readBinaryApdu.p1) // Offset high byte
        assertEquals(0x00, readBinaryApdu.p2) // Offset low byte
        assertEquals(256, readBinaryApdu.le) // Expected response length
    }

    @Test
    fun `test FCI response structure for passport application`() {
        val passportAid = byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
        )
        
        val fciData = byteArrayOf(
            0x6F, 0x10, // FCI template
            0x84, 0x07, // DF name
            *passportAid,
            0xA5, 0x05, // Proprietary information
            0x9F, 0x6E, 0x02, 0x00, 0x00 // Application production life cycle data
        )
        
        val response = ResponseAPDU(fciData + byteArrayOf(0x90.toByte(), 0x00))
        
        assertEquals(ISO7816.SW_NO_ERROR.toInt(), response.sw.toInt())
        assertTrue(response.data.contentEquals(fciData))
        
        // Verify FCI structure
        assertEquals(0x6F.toByte(), response.data[0]) // FCI template tag
        assertEquals(0x10.toByte(), response.data[1]) // FCI template length
        assertEquals(0x84.toByte(), response.data[2]) // DF name tag
        assertEquals(0x07.toByte(), response.data[3]) // DF name length
    }

    @Test
    fun `test invalid APDU handling`() {
        // Test empty APDU
        try {
            CommandAPDU(byteArrayOf())
            assert(false) { "Should throw exception for empty APDU" }
        } catch (e: Exception) {
            // Expected behavior
        }
        
        // Test APDU with invalid length
        try {
            CommandAPDU(byteArrayOf(0x00, 0xA4)) // Too short
            assert(false) { "Should throw exception for invalid APDU length" }
        } catch (e: Exception) {
            // Expected behavior
        }
    }

    @Test
    fun `test challenge generation randomness`() {
        val challenges = mutableSetOf<String>()
        
        // Generate multiple challenges and verify they're different
        repeat(10) {
            val challenge = ByteArray(8)
            java.security.SecureRandom().nextBytes(challenge)
            val challengeHex = challenge.joinToString("") { "%02X".format(it) }
            challenges.add(challengeHex)
        }
        
        // All challenges should be unique (very high probability)
        assertTrue(challenges.size >= 9, "Challenges should be random and unique")
    }
}