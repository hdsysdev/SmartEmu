package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.DataGroupObservation
import com.hddev.smartemu.capture.Redaction
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.PassportService
import org.jmrtd.protocol.ReadBinaryAPDUSender

/** Data group numbers, their file identifiers and their BER tags (ICAO 9303 part 10). */
object DataGroups {
    val numbers: IntRange = 1..16

    private val TAGS = intArrayOf(0x61, 0x75, 0x63, 0x76, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70)

    /** The tag of data group [number]'s file, which is also what EF.COM's tag list names it by. */
    fun tag(number: Int): Int = TAGS[number - 1]

    /** The data group an EF.COM tag names, or null if it names none. */
    fun numberOfTag(tag: Int): Int? = numbers.firstOrNull { tag(it) == tag }

    /** The file identifier of data group [number]: 0101 for DG1, up to 0110. */
    fun fileId(number: Int): Short = (PassportService.EF_DG1 + number - 1).toShort()
}

/**
 * Reads files of the eMRTD application over an open secure session ([service]), keeping from each only what a capture
 * may hold. A data group of a person (DG1, DG2, DG7, DG11, DG12) is never read beyond its BER header: the first
 * [HEADER_BYTES] bytes give its length, which is all a capture records, and they are erased as soon as the length is
 * taken. Files that hold no personal data (EF.COM, EF.SOD, DG14, DG15, EF.CVCA, EF.CardSecurity) are read whole by
 * JMRTD, parsed, reduced to structure, and their buffers erased.
 */
class ChipFiles(private val service: PassportService, private val card: TransportCardService) {

    private val sender = ReadBinaryAPDUSender(service.secureMessagingAPDUSender)

    /** What the reader learned of one data group without reading its contents. */
    class Header(val selectStatusWord: Int?, val readStatusWord: Int?, val length: Int?)

    /**
     * SELECT of [number]'s file, then READ BINARY of its first [HEADER_BYTES] bytes, both under the session's secure
     * messaging. The status words are the chip's answers, 9000 for success; a refusal (6982 for DG3 and DG4 without
     * Terminal Authentication) is a result here, not an error.
     */
    fun header(number: Int): Header = header(DataGroups.fileId(number))

    fun header(fileId: Short): Header {
        try {
            sender.sendSelectFile(service.wrapper, fileId)
        } catch (e: CardServiceException) {
            card.rethrowPendingStop()
            return Header(statusWordOf(e), null, null)
        }
        val bytes = try {
            sender.sendReadBinary(service.wrapper, 0, 0, HEADER_BYTES, false, false)
        } catch (e: CardServiceException) {
            card.rethrowPendingStop()
            return Header(SW_OK, statusWordOf(e), null)
        }
        try {
            return Header(SW_OK, SW_OK, bytes?.let(BerHeader::totalLength))
        } finally {
            bytes?.fill(0)
        }
    }

    /**
     * Reads the whole of [fileId] through JMRTD, or returns null if the chip refuses or the file is longer than [maxBytes].
     * The caller erases the bytes after parsing them.
     */
    fun readWhole(fileId: Short, maxBytes: Int): WholeFile {
        return try {
            val stream = service.getInputStream(fileId, PassportService.DEFAULT_MAX_BLOCKSIZE)
            if (stream.length > maxBytes) {
                stream.close()
                WholeFile(null, SW_OK, stream.length)
            } else {
                WholeFile(stream.readBytes(), SW_OK, stream.length)
            }
        } catch (e: CardServiceException) {
            card.rethrowPendingStop()
            WholeFile(null, statusWordOf(e), null)
        }
    }

    class WholeFile(val bytes: ByteArray?, val statusWord: Int?, val length: Int?) {
        val size get() = (bytes?.size ?: length)?.let(Redaction::sizeRange)
    }

    companion object {
        const val SW_OK = 0x9000

        /** Tag, a three-byte length, and nothing of the value for the biggest data groups. */
        const val HEADER_BYTES = 4

        /** The chip's status word in [e], or null if there was none (the exception came from the reader). */
        fun statusWordOf(e: Throwable): Int? {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is CardServiceException && (cause.sw shr 12) in 6..9 && cause.sw <= 0xFFFF) return cause.sw and 0xFFFF
                cause = cause.cause
            }
            return null
        }
    }
}

/** A data group's observation from what was learned of it. */
internal fun dataGroupObservation(number: Int, inCom: Boolean, inSod: Boolean, header: ChipFiles.Header): DataGroupObservation =
    DataGroupObservation(
        number = number,
        inCom = inCom,
        inSod = inSod,
        selectStatusWord = header.selectStatusWord,
        readStatusWord = header.readStatusWord,
        size = header.length?.let(Redaction::sizeRange)
    )
