package com.hddev.smartemu.capture

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSessionTest {

    private val file = ByteArray(500) { (it * 7).toByte() }

    /** A chip with one selected file of [content] that answers READ BINARY like a real one, up to [maxLe] bytes. */
    private fun fileChip(content: ByteArray = file, maxLe: Int = 256, eofWith6282: Boolean = false): ScriptedTransport =
        ScriptedTransport { command ->
            val bySfi = command[2].toInt() and 0x80 != 0
            val offset = if (bySfi) command[3].toInt() and 0xFF else ((command[2].toInt() and 0xFF) shl 8) or (command[3].toInt() and 0xFF)
            val le = if (command[4].toInt() == 0) 256 else command[4].toInt() and 0xFF
            when {
                offset >= content.size -> response(0x6B00)
                le > maxLe -> response(0x6700)
                offset + le > content.size && eofWith6282 -> response(0x6282, content.copyOfRange(offset, content.size))
                else -> response(0x9000, content.copyOfRange(offset, minOf(content.size, offset + le)))
            }
        }

    @Test
    fun `reads a file in blocks to its end`() {
        val read = CaptureSession(fileChip()).readSelectedFile(maxBytes = 2048)
        assertContentEquals(file, read.contents)
    }

    @Test
    fun `reads a file that ends exactly on a block boundary`() {
        val content = ByteArray(0xDF * 2) { it.toByte() }
        assertContentEquals(content, CaptureSession(fileChip(content)).readSelectedFile(2048).contents)
    }

    @Test
    fun `steps the block size down when the chip answers wrong length`() {
        val transport = fileChip(maxLe = 0x20)
        val read = CaptureSession(transport).readSelectedFile(2048)
        assertContentEquals(file, read.contents)
        assertTrue(transport.sent.any { it[4].toInt() == 0x20 })
    }

    @Test
    fun `follows 6CXX to the right length`() {
        val transport = ScriptedTransport { command ->
            val offset = ((command[2].toInt() and 0xFF) shl 8) or (command[3].toInt() and 0xFF)
            val le = command[4].toInt() and 0xFF
            when {
                offset >= 100 -> response(0x6B00)
                le > 100 - offset -> response(0x6C00 or (100 - offset))
                else -> response(0x9000, file.copyOfRange(offset, offset + le))
            }
        }
        assertContentEquals(file.copyOfRange(0, 100), CaptureSession(transport).readSelectedFile(2048).contents)
    }

    @Test
    fun `takes 6282 as the end of the file`() {
        assertContentEquals(file, CaptureSession(fileChip(eofWith6282 = true)).readSelectedFile(2048).contents)
    }

    @Test
    fun `a file longer than the limit is not kept`() {
        val read = CaptureSession(fileChip()).readSelectedFile(maxBytes = 100)
        assertNull(read.contents)
        assertTrue(read.truncated)
    }

    @Test
    fun `a chip that answers 6C forever does not loop forever`() {
        val read = CaptureSession(ScriptedTransport { response(0x6C10) }).readSelectedFile(2048)
        assertNull(read.contents)
    }

    @Test
    fun `an unreadable file gives its status word`() {
        val read = CaptureSession(ScriptedTransport { response(0x6982) }).readSelectedFile(2048)
        assertNull(read.contents)
        assertEquals(0x6982, read.statusWord)
    }

    @Test
    fun `reads by short EF identifier on the first block`() {
        val transport = fileChip()
        val read = CaptureSession(transport).readSelectedFile(2048, sfi = 0x1C)
        assertContentEquals(file, read.contents)
        assertContentEquals(hexBytes("00B09C00DF"), transport.sent[0])
        assertContentEquals(hexBytes("00B000DFDF"), transport.sent[1])
    }

    @Test
    fun `times each exchange`() {
        var now = 0L
        val session = CaptureSession(ScriptedTransport { response(0x9000) }, nanoTime = { now.also { now += 5_000_000 } })
        val first = session.transmit(hexBytes("00A4040C00"))
        assertEquals(5_000_000, first.nanos)
        assertEquals(1, session.exchangeCount)
    }

    @Test
    fun `a blocked status word stops the session from sending more`() {
        for (blocked in listOf(0x6983, 0x6984, 0x63C0)) {
            val transport = ScriptedTransport { response(blocked) }
            val session = CaptureSession(transport)
            assertEquals(blocked, session.transmit(hexBytes("0084000008")).statusWord)
            assertEquals(blocked, session.blockedStatusWord)
            assertFailsWith<BlockedStatusException> { session.transmit(hexBytes("0084000008")) }
            assertEquals(1, transport.sent.size)
        }
    }

    @Test
    fun `any 63Cx, and 6300 after an EXTERNAL AUTHENTICATE, are blocked by the probe rule`() {
        val externalAuthenticate = hexBytes("0082000028" + "00".repeat(40) + "28")
        for (statusWord in listOf(0x63C0, 0x63C1, 0x63C2, 0x63C5, 0x63CF)) {
            val session = CaptureSession(ScriptedTransport { response(statusWord) })
            session.transmit(hexBytes("00B0000004"))
            assertEquals(statusWord, session.blockedStatusWord, "%04X to a READ BINARY".format(statusWord))
        }
        val afterExternalAuthenticate = CaptureSession(ScriptedTransport { response(0x6300) })
        afterExternalAuthenticate.transmit(externalAuthenticate)
        assertEquals(0x6300, afterExternalAuthenticate.blockedStatusWord)
        // 6300 to anything else is an ordinary refusal
        val ordinary = CaptureSession(ScriptedTransport { response(0x6300) })
        ordinary.transmit(hexBytes("00B0000004"))
        assertEquals(null, ordinary.blockedStatusWord)
    }

    @Test
    fun `the wrong-key rule of tier c stops at 63C1 and below only, and the authentication rule keeps the old words`() {
        val externalAuthenticate = hexBytes("0082000028" + "00".repeat(40) + "28")
        fun blocked(rule: BlockedStatus.Rule, statusWord: Int): Boolean = rule.blocks(externalAuthenticate, statusWord)
        listOf(0x6983, 0x6984, 0x63C0, 0x63C1).forEach { assertEquals(true, blocked(BlockedStatus.wrongKeyProbes, it), "%04X".format(it)) }
        listOf(0x6300, 0x63C2, 0x63C3, 0x63CF, 0x6985, 0x6982).forEach { assertEquals(false, blocked(BlockedStatus.wrongKeyProbes, it), "%04X".format(it)) }
        listOf(0x6983, 0x6984, 0x63C0).forEach { assertEquals(true, blocked(BlockedStatus.authentication, it), "%04X".format(it)) }
        listOf(0x6300, 0x63C1, 0x63C2).forEach { assertEquals(false, blocked(BlockedStatus.authentication, it), "%04X".format(it)) }
    }

    @Test
    fun `the session notes an EXTERNAL AUTHENTICATE handed to the chip, even if the tag was lost before the answer`() {
        val session = CaptureSession(TagLossTransport(ScriptedTransport { response(0x9000) }, loseAt = 2))
        session.transmit(hexBytes("0084000008"))
        assertEquals(false, session.externalAuthenticateSent)
        assertFailsWith<TagLostException> { session.transmit(hexBytes("0082000028" + "00".repeat(40) + "28")) }
        assertEquals(true, session.externalAuthenticateSent)
    }

    @Test
    fun `ordinary refusals are not blocked status words`() {
        for (statusWord in listOf(0x6982, 0x6985, 0x6A82, 0x6700, 0x6D00, 0x63C3)) {
            assertEquals(false, BlockedStatus.isBlocked(statusWord), "%04X".format(statusWord))
        }
    }

    @Test
    fun `a lost tag surfaces and is logged`() {
        val session = CaptureSession(TagLossTransport(ScriptedTransport { response(0x9000) }, loseAt = 2))
        session.transmit(hexBytes("0084000008"))
        assertFailsWith<TagLostException> { session.transmit(hexBytes("0084000008")) }
        assertEquals(2, session.exchangeCount)
    }

    @Test
    fun `a command longer than the controller transmits is refused before sending`() {
        val transport = ScriptedTransport(testReader.copy(controllerMaxTransceiveLength = 8)) { response(0x9000) }
        assertFailsWith<CommandNotSupportedByReader> { CaptureSession(transport).transmit(ByteArray(9)) }
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `wiping erases the log`() {
        val session = CaptureSession(ScriptedTransport { response(0x9000, byteArrayOf(1, 2, 3)) })
        session.transmit(hexBytes("0084000008"))
        val exchange = session.exchanges().single()
        session.wipe()
        assertEquals(0, session.exchangeCount)
        assertContentEquals(ByteArray(5), exchange.command)
        assertContentEquals(ByteArray(5), exchange.response)
    }
}
