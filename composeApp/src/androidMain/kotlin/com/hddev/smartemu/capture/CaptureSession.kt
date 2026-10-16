package com.hddev.smartemu.capture

/**
 * One run's conversation with a chip: sends each command through the [ApduTransport], times it, and keeps the exchanges
 * in memory so that [wipe] can erase them at the end of the run.
 *
 * The log is deliberately unreadable from outside the package: nothing here writes an APDU to logcat, a file or any
 * other output, this class doesn't use `android.util.Log`, and a capture is built from typed observations only (see
 * [CaptureBuilder]). Keep it that way: a response can hold the MRZ.
 *
 * Once a response carries a blocked status word (see [BlockedStatus], and [blockRule] for which words count) the session
 * refuses to send anything more, and [blockedStatusWord] says which one.
 */
class CaptureSession(
    private val transport: ApduTransport,
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * Which answers end the run. The default is the probe rule ([BlockedStatus.probes]); an authenticated read passes
     * [BlockedStatus.authentication], and a Tier C probe is run under [BlockedStatus.wrongKeyProbes] (see [CaptureRunner]).
     */
    var blockRule: BlockedStatus.Rule = BlockedStatus.probes
) {
    /** A response APDU and how long the exchange took. */
    class Response(val bytes: ByteArray, val nanos: Long) {
        /** The status word, or null if the response is too short to hold one. */
        val statusWord: Int? =
            if (bytes.size >= 2) ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 1].toInt() and 0xFF) else null

        /** The response data, without the status word. */
        val data: ByteArray get() = if (bytes.size >= 2) bytes.copyOfRange(0, bytes.size - 2) else byteArrayOf()

        val isSuccess: Boolean get() = statusWord == SW_OK
    }

    /** A command and its response in memory; the response is null if the tag was lost. */
    internal class Exchange(val command: ByteArray, val response: ByteArray?, val nanos: Long)

    private val log = ArrayList<Exchange>()

    /** How many commands have been sent. */
    val exchangeCount: Int get() = log.size

    /** The blocked status word that stopped the session, or null. */
    var blockedStatusWord: Int? = null
        private set

    /**
     * Whether an EXTERNAL AUTHENTICATE (INS 82) was handed to the transport in this session, even if the tag was lost
     * before the answer came: the chip may have counted it. A runner keeps this in the draft, so that a resume never
     * sends one again (see [CaptureDraft]).
     */
    var externalAuthenticateSent: Boolean = false
        private set

    private val device: ReaderDevice by lazy { transport.readerDevice() }

    internal fun exchanges(): List<Exchange> = log

    /** Whether a command with instruction [ins] was sent after the first [fromExchange] exchanges of this session. */
    internal fun sentSince(fromExchange: Int, ins: Int): Boolean =
        log.drop(fromExchange).any { it.command.size > 1 && (it.command[1].toInt() and 0xFF) == ins }

    /**
     * Sends [command] and returns the response. Throws [TagLostException] if the tag goes, [BlockedStatusException] if
     * an earlier response was a blocked status word, and [CommandNotSupportedByReader] if the command is longer than
     * the phone's controller transmits.
     */
    fun transmit(command: ByteArray): Response {
        blockedStatusWord?.let { throw BlockedStatusException(it) }
        device.controllerMaxTransceiveLength?.let { max ->
            if (command.size > max) throw CommandNotSupportedByReader("Command of ${command.size} bytes exceeds the controller's $max")
        }
        if (command.size > 1 && command[1] == INS_EXTERNAL_AUTHENTICATE) externalAuthenticateSent = true
        val start = nanoTime()
        val bytes = try {
            transport.transmit(command)
        } catch (e: TagLostException) {
            log += Exchange(command.copyOf(), null, nanoTime() - start)
            throw e
        }
        val response = Response(bytes, nanoTime() - start)
        log += Exchange(command.copyOf(), bytes.copyOf(), response.nanos)
        response.statusWord?.takeIf { blockRule.blocks(command, it) }?.let { blockedStatusWord = it }
        return response
    }

    /** SELECT of the eMRTD application, `00 A4 04 0C 07 A0000002471001`, which asks for no response data. */
    fun selectEmrtdApplication(): Response = transmit(Apdu.selectEmrtdApplication)

    /** SELECT of the master file by its identifier, to return to a known state. */
    fun selectMasterFile(): Response = transmit(Apdu.selectFileId(0x3F00, p1 = 0x00))

    /** SELECT of an EF by its two-byte identifier in the current DF. */
    fun selectFileId(fileId: Int, p1: Int = 0x02, p2: Int = 0x0C): Response = transmit(Apdu.selectFileId(fileId, p1, p2))

    /** READ BINARY of the selected file; [le] 0 asks for 256 bytes. */
    fun readBinary(offset: Int, le: Int): Response = transmit(Apdu.readBinary(offset, le))

    /** READ BINARY addressing an EF by its short identifier, which selects it. */
    fun readBinaryBySfi(sfi: Int, offset: Int, le: Int): Response = transmit(Apdu.readBinaryBySfi(sfi, offset, le))

    /**
     * Reads the whole selected file, a block at a time, and handles the answers chips differ in: 6CXX names the right
     * Le, 6700 means the block was too big, and 6282 or an offset past the end (6B00, 6A86) means the file ended. A file
     * longer than [maxBytes] is not read to the end. Pass [sfi] to read by short identifier: the first block then
     * selects the file.
     */
    fun readSelectedFile(maxBytes: Int, sfi: Int? = null): FileRead {
        val contents = java.io.ByteArrayOutputStream()
        var block = BLOCK_SIZES.first()
        var blockIndex = 0
        var lastStatusWord: Int? = null
        var attempts = 0
        // The attempt limit stops a chip that answers 6CXX again and again
        while (contents.size() <= maxBytes && attempts++ < MAX_READ_ATTEMPTS) {
            val offset = contents.size()
            val response = if (sfi != null && offset == 0) readBinaryBySfi(sfi, 0, block) else readBinary(offset, block)
            val statusWord = response.statusWord
            lastStatusWord = statusWord
            val data = response.data
            when {
                statusWord == null -> return FileRead(null, null, truncated = false)
                statusWord == SW_OK || statusWord == SW_END_OF_FILE -> {
                    contents.write(data)
                    // A short block, or the end-of-file warning, is the end; so is an empty one
                    if (statusWord == SW_END_OF_FILE || data.size < effectiveLe(block) || data.isEmpty()) {
                        return FileRead(contents.toByteArray(), statusWord, truncated = false)
                    }
                }
                statusWord shr 8 == 0x6C -> block = statusWord and 0xFF
                statusWord == SW_WRONG_LENGTH && blockIndex < BLOCK_SIZES.lastIndex -> block = BLOCK_SIZES[++blockIndex]
                offset > 0 && (statusWord == SW_WRONG_P1P2 || statusWord == SW_OFFSET_OUTSIDE_FILE) ->
                    return FileRead(contents.toByteArray(), statusWord, truncated = false)
                else -> return FileRead(null, statusWord, truncated = false)
            }
        }
        return FileRead(null, lastStatusWord, truncated = true)
    }

    private fun effectiveLe(le: Int): Int = if (le == 0) 256 else le

    /** Erases the log. The caller drops the session after this. */
    fun wipe() {
        log.forEach { exchange ->
            exchange.command.fill(0)
            exchange.response?.fill(0)
        }
        log.clear()
    }

    /** What reading a file gave: its contents, or null if it couldn't be read or was too long to keep, and the last status word. */
    class FileRead(val contents: ByteArray?, val statusWord: Int?, val truncated: Boolean)

    companion object {
        const val SW_OK = 0x9000
        const val SW_END_OF_FILE = 0x6282
        const val SW_WRONG_LENGTH = 0x6700
        const val SW_WRONG_P1P2 = 0x6B00
        const val SW_OFFSET_OUTSIDE_FILE = 0x6A86
        private const val INS_EXTERNAL_AUTHENTICATE = 0x82.toByte()

        /** The block sizes tried in turn: 0xDF fits every chip's APDU buffer, and the smaller ones are for chips that refuse it. */
        private const val MAX_READ_ATTEMPTS = 512
        private val BLOCK_SIZES = intArrayOf(0xDF, 0x80, 0x20, 0x08)
    }
}

/** Thrown by the next command after a blocked status word, so that nothing more is sent to a locked document. */
class BlockedStatusException(val statusWord: Int) : Exception("Blocked status word %04X".format(statusWord))

/**
 * The status words that end a run, by rule ([Rule]); every rule ends it at 6983 (the access method is blocked) and
 * 6984 (its reference data is invalidated).
 *
 * - [probes] (Tier A and B, and every other probe): also 63Cx, any retry counter hint, and 6300 as the answer to an
 *   EXTERNAL AUTHENTICATE. Those mean a chip evaluated a key or cryptogram and may have counted a failure; nothing is
 *   sent after one. The probes send no key, so a chip that answers like this was already counting.
 * - [wrongKeyProbes] (Tier C, whose design is a bounded run of wrong-key EXTERNAL AUTHENTICATE commands): the
 *   refusals it expects, 6300 and 63C2 and up, are not a stop, but the counter at 63C1 or less is, so is 63C0.
 * - [authentication] (an authenticated read, which has its own attempt policy): 6983, 6984 and 63C0, as before.
 */
object BlockedStatus {
    /** Whether [command]'s answer [statusWord] ends the run. */
    fun interface Rule {
        fun blocks(command: ByteArray, statusWord: Int): Boolean
    }

    /** 6983, 6984 or 63C0 on its own. */
    fun isBlocked(statusWord: Int): Boolean = statusWord == 0x6983 || statusWord == 0x6984 || statusWord == 0x63C0

    private fun isExternalAuthenticate(command: ByteArray) = command.size > 1 && command[1] == 0x82.toByte()

    private fun counterHint(statusWord: Int) = statusWord and 0xFFF0 == 0x63C0

    private fun blockedOrInvalidated(statusWord: Int) = statusWord == 0x6983 || statusWord == 0x6984

    val probes = Rule { command, statusWord ->
        blockedOrInvalidated(statusWord) || counterHint(statusWord) || (statusWord == 0x6300 && isExternalAuthenticate(command))
    }

    val wrongKeyProbes = Rule { _, statusWord ->
        blockedOrInvalidated(statusWord) || (counterHint(statusWord) && statusWord and 0x000F <= 1)
    }

    val authentication = Rule { _, statusWord -> isBlocked(statusWord) }
}
