package com.hddev.smartemu.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import com.hddev.smartemu.utils.ApduParser
import com.hddev.smartemu.utils.ApduParser.ApduCommandType
import com.hddev.smartemu.utils.ApduParser.ApduParseResult

class ApduParserTest {

    @Test
    fun testParseSelectByFileId_EfCom() {
        // SELECT P1=0x02 P2=0x0C Lc=0x02 Data=011E (EF.COM)
        val apdu = byteArrayOf(
            0x00.toByte(), 0xA4.toByte(), 0x02.toByte(), 0x0C.toByte(), 
            0x02.toByte(), 0x01.toByte(), 0x1E.toByte()
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduCommandType.SELECT, result.commandType)
        assertTrue(result.isValid)
        assertEquals(2, result.p1)
        assertEquals(0x0C, result.p2)
        assertTrue(byteArrayOf(0x01.toByte(), 0x1E.toByte()).contentEquals(result.data!!))
    }

    @Test
    fun testParseReadBinary_WithOffset() {
        // READ BINARY P1=0x01 P2=0x05 Le=0x20
        // Offset = 0x0105 = 261
        val apdu = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x01.toByte(), 0x05.toByte(), 
            0x20.toByte()
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduCommandType.READ_BINARY, result.commandType)
        assertTrue(result.isValid)
        assertEquals(0x01, result.p1)
        assertEquals(0x05, result.p2)
        assertEquals(0x20, result.le)
    }

    @Test
    fun testParseReadBinary_NoLe() {
        // READ BINARY P1=0x00 P2=0x00 (No Le -> Le=0)
        val apdu = byteArrayOf(
            0x00.toByte(), 0xB0.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        
        val result = ApduParser.parseApduCommand(apdu)
        
        assertEquals(ApduCommandType.READ_BINARY, result.commandType)
        assertTrue(result.isValid)
        assertEquals(0, result.p1)
        assertEquals(0, result.p2)
        assertEquals(0, result.le)
    }

    @Test
    fun testParseInternalAuthenticate_ShortAndExtendedLength() {
        val challenge = ByteArray(8) { it.toByte() }
        val short = byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x08) + challenge + byteArrayOf(0x00)
        // An RSA-2048 Active Authentication signature needs an extended Le: 00 Lc1 Lc2 data Le1 Le2
        val extended = byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00, 0x08) + challenge + byteArrayOf(0x00, 0x00)

        for (apdu in listOf(short, extended)) {
            val result = ApduParser.parseApduCommand(apdu)
            assertEquals(ApduCommandType.INTERNAL_AUTHENTICATE, result.commandType)
            assertTrue(result.isValid)
            assertTrue(challenge.contentEquals(result.data!!))
        }
    }

    private fun hex(apdu: ByteArray) = apdu.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun testParseInternalAuthenticate_ExtendedLengthWithoutLe() {
        val challenge = ByteArray(8) { it.toByte() }
        // 00 Lc1 Lc2 data, no Le at all: the data is still the challenge
        val apdu = byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00, 0x08) + challenge

        val result = ApduParser.parseApduCommand(apdu)

        assertEquals(ApduCommandType.INTERNAL_AUTHENTICATE, result.commandType)
        assertTrue(result.isValid)
        assertTrue(challenge.contentEquals(result.data!!))
    }

    @Test
    fun testParseInternalAuthenticate_TruncatedExtendedInputIsInvalid() {
        val challenge = ByteArray(8) { it.toByte() }
        val truncated = listOf(
            // Header only, and a header with a lone Lc byte, shorter than the 7 bytes an extended APDU needs
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00),
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00),
            // Extended Lc of 8, but only 5 data bytes present
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00, 0x08) + challenge.copyOf(5),
            // Extended Lc of 0x0100 with 8 data bytes present
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x01, 0x00) + challenge,
            // Short Lc of 8 with 3 data bytes present
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x08) + challenge.copyOf(3)
        )

        for (apdu in truncated) {
            val result = ApduParser.parseApduCommand(apdu)
            assertFalse(result.isValid, hex(apdu))
            assertTrue(ApduParser.SW_WRONG_LENGTH.contentEquals(result.errorResponse!!))
        }
    }

    @Test
    fun testParseInternalAuthenticate_ZeroLcIsInvalid() {
        val zeroLc = listOf(
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00),
            // Extended, Lc 0000, with and without a trailing Le
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        )

        for (apdu in zeroLc) {
            val result = ApduParser.parseApduCommand(apdu)
            assertEquals(ApduCommandType.INTERNAL_AUTHENTICATE, result.commandType)
            assertFalse(result.isValid, hex(apdu))
        }
    }

    @Test
    fun testParseInternalAuthenticate_MalformedHeaderIsInvalid() {
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(0x00),
            byteArrayOf(0x00, 0x88.toByte()),
            byteArrayOf(0x00, 0x88.toByte(), 0x00)
        )

        for (apdu in malformed) {
            val result = ApduParser.parseApduCommand(apdu)
            assertFalse(result.isValid)
            assertTrue(ApduParser.SW_WRONG_LENGTH.contentEquals(result.errorResponse!!))
        }
        assertFalse(ApduParser.parseApduCommand(null).isValid)
    }
}
