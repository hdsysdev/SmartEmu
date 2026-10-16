package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.BlockedStatus
import com.hddev.smartemu.capture.BlockedStatusException
import com.hddev.smartemu.capture.CaptureSession
import com.hddev.smartemu.capture.ScriptedTransport
import com.hddev.smartemu.capture.TagLostException
import com.hddev.smartemu.capture.response
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransportCardServiceTest {
    private fun externalAuthenticate(le: Int, fill: Byte = 7) = CommandAPDU(0x00, 0x82, 0x00, 0x00, ByteArray(40) { fill }, le)

    @Test
    fun `JMRTD's repeat of a refused EXTERNAL AUTHENTICATE is not sent, so one attempt counts once`() {
        for (refusal in listOf(0x6300, 0x6985, 0x6982, 0x6A80, 0x63C2)) {
            val transport = ScriptedTransport { response(refusal) }
            val card = TransportCardService(CaptureSession(transport, blockRule = BlockedStatus.authentication))
            assertEquals(refusal, card.transmit(externalAuthenticate(40)).sw)
            assertEquals(refusal, card.transmit(externalAuthenticate(0)).sw, "the repeat gets the first answer")
            assertEquals(1, transport.sent.size, "%04X".format(refusal))
        }
    }

    @Test
    fun `a repeat is sent if the first answer was about length, or if it is not a repeat`() {
        for (refusal in listOf(0x6700, 0x6C28)) {
            val transport = ScriptedTransport { response(refusal) }
            val card = TransportCardService(CaptureSession(transport, blockRule = BlockedStatus.authentication))
            card.transmit(externalAuthenticate(40))
            card.transmit(externalAuthenticate(0))
            assertEquals(2, transport.sent.size, "%04X".format(refusal))
        }
        val transport = ScriptedTransport { response(0x6300) }
        val card = TransportCardService(CaptureSession(transport, blockRule = BlockedStatus.authentication))
        card.transmit(externalAuthenticate(40, fill = 1))
        card.transmit(externalAuthenticate(40, fill = 2))
        assertEquals(2, transport.sent.size, "a different cryptogram is a different attempt")
    }

    @Test
    fun `a lost tag and a blocked status word are kept to be put back after JMRTD wraps them`() {
        val lost = TransportCardService(CaptureSession(ScriptedTransport { throw TagLostException() }))
        assertFailsWith<CardServiceException> { lost.transmit(CommandAPDU(0x00, 0xA4, 0x04, 0x0C, byteArrayOf(1))) }
        assertTrue(lost.isConnectionLost(null))
        assertIs<TagLostException>(assertFailsWith<TagLostException> { lost.rethrowPendingStop() })

        val session = CaptureSession(ScriptedTransport { response(0x6983) })
        val blocked = TransportCardService(session)
        blocked.transmit(CommandAPDU(0x00, 0xA4, 0x04, 0x0C, byteArrayOf(1)))
        assertFailsWith<CardServiceException> { blocked.transmit(CommandAPDU(0x00, 0xA4, 0x04, 0x0C, byteArrayOf(1))) }
        assertFailsWith<BlockedStatusException> { blocked.rethrowPendingStop() }
    }

    @Test
    fun `closing forgets the cached cryptogram and answer, so nothing of an attempt is kept after the run`() {
        val transport = ScriptedTransport { response(0x6300) }
        val card = TransportCardService(CaptureSession(transport, blockRule = BlockedStatus.authentication))
        card.transmit(externalAuthenticate(40))
        card.close()
        // After close() a repeat is no longer answered from the first response: it goes to the chip again
        card.transmit(externalAuthenticate(0))
        assertEquals(2, transport.sent.size)
    }
}
