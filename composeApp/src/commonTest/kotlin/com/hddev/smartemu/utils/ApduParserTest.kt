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
}