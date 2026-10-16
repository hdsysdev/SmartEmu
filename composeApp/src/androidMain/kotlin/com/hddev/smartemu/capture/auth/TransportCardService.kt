package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.BlockedStatusException
import com.hddev.smartemu.capture.CaptureSession
import com.hddev.smartemu.capture.CommandNotSupportedByReader
import com.hddev.smartemu.capture.TagLostException
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU

/**
 * Carries JMRTD's commands to the chip through a [CaptureSession], so that an authenticated read keeps the session's
 * rules: every exchange is timed and held in memory only (and wiped at the end of the run), and a blocked status word
 * stops anything further from being sent.
 *
 * JMRTD wraps what a transport throws in its own exceptions, which a caller can't tell from a refusal by the chip. So
 * the reasons a run must end, a lost tag and a blocked status word, are kept here ([pendingStop]) and [rethrowPendingStop]
 * puts them back after JMRTD has thrown something else.
 */
class TransportCardService(private val session: CaptureSession) : CardService() {
    private var open = false

    /** The tag loss or blocked status word that JMRTD's last exception stands for, if any. */
    var pendingStop: Exception? = null
        private set

    override fun open() {
        open = true
    }

    override fun isOpen(): Boolean = open

    private var lastCryptogram: ByteArray? = null
    private var lastCryptogramResponse: ByteArray? = null

    override fun transmit(commandAPDU: CommandAPDU): ResponseAPDU {
        // JMRTD's BAC repeats a refused EXTERNAL AUTHENTICATE once, with Le 0, in case the chip didn't like Le 40. A chip
        // that counts failed attempts would count it twice, so the repeat is answered from the first response unless that
        // was a complaint about length
        if (commandAPDU.ins == INS_EXTERNAL_AUTHENTICATE) {
            val previous = lastCryptogram
            val previousResponse = lastCryptogramResponse
            if (previous != null && previousResponse != null && previous.contentEquals(commandAPDU.data) && !mayBeLengthComplaint(previousResponse)) {
                return ResponseAPDU(previousResponse.copyOf())
            }
        }
        try {
            val response = session.transmit(commandAPDU.bytes).bytes
            if (commandAPDU.ins == INS_EXTERNAL_AUTHENTICATE) {
                lastCryptogram?.fill(0)
                lastCryptogram = commandAPDU.data
                lastCryptogramResponse = response.copyOf()
            }
            return ResponseAPDU(response)
        } catch (e: TagLostException) {
            pendingStop = e
            throw CardServiceException("The tag was lost", e)
        } catch (e: BlockedStatusException) {
            pendingStop = e
            throw CardServiceException("The chip is blocked", e)
        } catch (e: CommandNotSupportedByReader) {
            throw CardServiceException(e.message ?: "The reader can't send this command", e)
        }
    }

    override fun getATR(): ByteArray = byteArrayOf()

    /**
     * Zeroes the last EXTERNAL AUTHENTICATE's cryptogram and its answer, which are kept to answer JMRTD's repeat. The
     * byte arrays JMRTD and the JCE copied on their way are theirs, and so are the Strings made from the typed key: a known
     * limit, left to the garbage collector.
     */
    override fun close() {
        open = false
        lastCryptogram?.fill(0)
        lastCryptogram = null
        lastCryptogramResponse?.fill(0)
        lastCryptogramResponse = null
    }

    /** A status word that says the command was malformed for the chip (67xx, 6Cxx) rather than refused, or no status word at all. */
    private fun mayBeLengthComplaint(response: ByteArray): Boolean {
        if (response.size < 2) return true
        val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or (response[response.size - 1].toInt() and 0xFF)
        return sw == 0x9000 || sw shr 8 == 0x67 || sw shr 8 == 0x6C
    }

    override fun isConnectionLost(e: Exception?): Boolean = pendingStop is TagLostException

    /** Throws the [TagLostException] or [BlockedStatusException] that ended a JMRTD call, if one did. */
    fun rethrowPendingStop() {
        pendingStop?.let { throw it }
    }

    private companion object {
        const val INS_EXTERNAL_AUTHENTICATE = 0x82
    }
}
