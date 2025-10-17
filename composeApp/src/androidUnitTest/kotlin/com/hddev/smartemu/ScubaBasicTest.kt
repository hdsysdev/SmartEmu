package com.hddev.smartemu

import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.smartcards.ISO7816
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Basic tests for SCUBA library functionality.
 */
class ScubaBasicTest {

    @Test
    fun `test SCUBA CommandAPDU basic functionality`() {
        // Test valid SELECT command APDU
        val selectApdu = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x07, // SELECT command header
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01 // Passport AID
        )
        
        val command = CommandAPDU(selectApdu)
        
        assertEquals(0x00, command.cla.toInt())
        assertEquals(0xA4, command.ins.toInt())
        assertEquals(0x04, command.p1.toInt())
        assertEquals(0x00, command.p2.toInt())
        assertNotNull(command.data)
        assertEquals(7, command.data.size)
    }

    @Test
    fun `test SCUBA ResponseAPDU basic functionality`() {
        // Test success response
        val successBytes = byteArrayOf(0x90.toByte(), 0x00)
        val successResponse = ResponseAPDU(successBytes)
        assertEquals(0x9000, successResponse.sw.toInt())
        assertEquals(2, successResponse.bytes.size)
        
        // Test response with data
        val testData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val dataBytes = testData + byteArrayOf(0x90.toByte(), 0x00)
        val dataResponse = ResponseAPDU(dataBytes)
        assertEquals(0x9000, dataResponse.sw.toInt())
        assertEquals(6, dataResponse.bytes.size)
        assertTrue(dataResponse.data.contentEquals(testData))
    }

    @Test
    fun `test SCUBA ISO7816 constants`() {
        // Test that ISO7816 constants are accessible
        assertEquals(0x9000, ISO7816.SW_NO_ERROR.toInt())
        assertEquals(0x6A82, ISO7816.SW_FILE_NOT_FOUND.toInt())
        assertEquals(0x6982, ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
        assertEquals(0x6700, ISO7816.SW_WRONG_LENGTH.toInt())
        assertEquals(0x6E00, ISO7816.SW_CLA_NOT_SUPPORTED.toInt())
        assertEquals(0x6D00, ISO7816.SW_INS_NOT_SUPPORTED.toInt())
    }

    @Test
    fun `test passport AID structure`() {
        val passportAid = byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
        )
        
        // Create SELECT command for passport AID
        val selectCommand = CommandAPDU(
            0x00, 0xA4, 0x04, 0x00, 
            passportAid
        )
        
        assertEquals(0xA4, selectCommand.ins.toInt()) // SELECT instruction
        assertEquals(0x04, selectCommand.p1.toInt()) // Select by DF name
        assertTrue(selectCommand.data.contentEquals(passportAid))
    }

    @Test
    fun `test error response creation`() {
        val errorCodes = listOf(
            0x6A82, // FILE_NOT_FOUND
            0x6982, // SECURITY_STATUS_NOT_SATISFIED
            0x6700, // WRONG_LENGTH
            0x6E00, // CLA_NOT_SUPPORTED
            0x6D00  // INS_NOT_SUPPORTED
        )
        
        errorCodes.forEach { errorCode ->
            val sw1 = (errorCode shr 8).toByte()
            val sw2 = (errorCode and 0xFF).toByte()
            val response = ResponseAPDU(byteArrayOf(sw1, sw2))
            assertEquals(errorCode, response.sw.toInt())
            assertEquals(2, response.bytes.size)
        }
    }
}