package com.hddev.smartemu.capture.transcript

import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.ReaderDevice

/** One command and the response it got, as bytes. */
class TranscriptExchange(val command: ByteArray, val response: ByteArray, val line: Int = 0) {
    override fun toString(): String = "> ${Hex.encode(command)} / < ${Hex.encode(response)}"
}

/** The transcript is malformed: [line] is 1-based, 0 where the problem isn't on one line. The message never quotes a response. */
class TranscriptFormatException(val line: Int, message: String) :
    IllegalArgumentException(if (line > 0) "Transcript line $line: $message" else "Transcript: $message")

/**
 * A transcript can't stand in for the chip: the capture run sent a command the transcript doesn't have at that point.
 * The message names both commands (they are commands of a published probe pack or of the capture runner, never data
 * from the holder) and never a response.
 */
class TranscriptMismatchException(message: String) : IllegalStateException(message)

/**
 * A recorded lab session with a chip, to be replayed to the capture runner so that a capture taken with a PC/SC reader
 * or a Proxmark is ingested like one taken with the phone (see docs/lab-captures.md).
 *
 * The text format, one exchange per pair of lines:
 *
 *     # a comment, ignored
 *     #! source: PCSC            <- optional directives (see [Directive]), also comments to other tools
 *     > 00A4040C07A0000002471001   <- a command APDU, hex, spaces allowed
 *     < 9000                       <- the response APDU: data then status word, hex, two bytes at least
 *
 * Every `>` line is followed by exactly one `<` line. Blank lines are ignored.
 */
class Transcript(val exchanges: List<TranscriptExchange>, val directives: Map<String, String> = emptyMap()) {

    /** Directive names the format knows; any other `#!` line is a format error, so a typo isn't silently ignored. */
    object Directive {
        const val SOURCE = "source"
        const val READER = "reader"
        const val EXTENDED_LENGTH = "extended-length"
        const val PACKS = "packs"
        val all = setOf(SOURCE, READER, EXTENDED_LENGTH, PACKS)
    }

    /** The capture source the directive names (PCSC or PROXMARK), or null. */
    val source: String? get() = directives[Directive.SOURCE]

    /** The probe packs the commands are those of, as `id/version` in order, or empty if the transcript doesn't say. */
    val packs: List<Pair<String, Int>>
        get() = directives[Directive.PACKS].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val id = it.substringBeforeLast('/')
            val version = it.substringAfterLast('/', "").toIntOrNull() ?: throw TranscriptFormatException(0, "'$it' is not pack/version, as in tier-a/1")
            id to version
        }

    /** The text of the transcript; [parse] of it gives this transcript back. */
    fun render(): String = buildString {
        directives.forEach { (name, value) -> appendLine("#! $name: $value") }
        exchanges.forEach {
            appendLine("> ${Hex.encode(it.command)}")
            appendLine("< ${Hex.encode(it.response)}")
        }
    }

    /** The transport the capture runner replays this transcript through; see [TranscriptTransport]. */
    fun transport(): TranscriptTransport = TranscriptTransport(this)

    companion object {
        private val modelShape = Regex("[A-Za-z0-9 ._+/()-]{1,64}")

        fun parse(text: String): Transcript {
            val exchanges = ArrayList<TranscriptExchange>()
            val directives = linkedMapOf<String, String>()
            var pending: Pair<ByteArray, Int>? = null
            text.lineSequence().forEachIndexed { index, raw ->
                val number = index + 1
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#!") -> {
                        val name = line.removePrefix("#!").substringBefore(':').trim()
                        val value = line.substringAfter(':', "").trim()
                        if (name !in Directive.all) throw TranscriptFormatException(number, "Unknown directive '#! $name' (known: ${Directive.all.joinToString()})")
                        if (name in directives) throw TranscriptFormatException(number, "Directive '$name' appears twice")
                        if (name == Directive.READER && !modelShape.matches(value)) {
                            throw TranscriptFormatException(number, "The reader name must be 1 to 64 letters, digits, spaces and . _ + / ( ) -")
                        }
                        if (name == Directive.EXTENDED_LENGTH && value != "true" && value != "false") {
                            throw TranscriptFormatException(number, "extended-length is true or false")
                        }
                        directives[name] = value
                    }
                    line.startsWith("#") -> Unit
                    line.startsWith(">") -> {
                        if (pending != null) throw TranscriptFormatException(pending!!.second, "Command has no response before the next command")
                        pending = hexOf(line.substring(1), number, "command", minBytes = 4) to number
                    }
                    line.startsWith("<") -> {
                        val command = pending ?: throw TranscriptFormatException(number, "Response with no command before it")
                        exchanges += TranscriptExchange(command.first, hexOf(line.substring(1), number, "response", minBytes = 2), command.second)
                        pending = null
                    }
                    else -> throw TranscriptFormatException(number, "A line starts with '>', '<' or '#'")
                }
            }
            pending?.let { throw TranscriptFormatException(it.second, "Command has no response at the end of the transcript") }
            return Transcript(exchanges, directives)
        }

        private fun hexOf(text: String, line: Int, what: String, minBytes: Int): ByteArray {
            val digits = text.filter { !it.isWhitespace() }
            if (!Hex.isHex(digits)) throw TranscriptFormatException(line, "The $what is not an even number of hex digits")
            if (digits.length / 2 < minBytes) throw TranscriptFormatException(line, "The $what is shorter than $minBytes bytes")
            return Hex.decode(digits)
        }
    }
}

/**
 * Replays a [Transcript] as an [ApduTransport]: the n-th command the capture run sends must be the n-th command in the
 * transcript, and the transport answers with the response recorded for it. A different command, or one more than the
 * transcript holds, throws a [TranscriptMismatchException] that names the position, the line, and both commands.
 *
 * The reader is the lab reader of the transcript, not a phone: its controller limits are unknown (null), and it sends
 * extended-length commands unless the transcript says `#! extended-length: false`. The link layer isn't known.
 */
class TranscriptTransport(private val transcript: Transcript, private val device: ReaderDevice = deviceOf(transcript)) : ApduTransport {
    private var position = 0

    /** How many recorded exchanges the run has not used. Zero after a run that matched the transcript to its end. */
    val remaining: Int get() = transcript.exchanges.size - position

    override fun transmit(command: ByteArray): ByteArray {
        val expected = transcript.exchanges.getOrNull(position) ?: throw TranscriptMismatchException(
            "The capture run sent command #${position + 1} (${Hex.encode(command)}) but the transcript ends after ${transcript.exchanges.size} exchanges. " +
                "Record the whole probe pack, in order, as listed by the exporter."
        )
        if (!expected.command.contentEquals(command)) {
            throw TranscriptMismatchException(
                "Transcript exchange #${position + 1} (line ${expected.line}) does not match the capture run: it sent ${Hex.encode(command)} " +
                    "but the transcript has ${Hex.encode(expected.command)}. Commands must be sent in the order of the probe pack's command list."
            )
        }
        position++
        return expected.response.copyOf()
    }

    override fun linkInfo(): LinkObservation? = null

    override fun readerDevice(): ReaderDevice = device

    companion object {
        /** The default for a lab capture: the reader the transcript names, with no phone controller limits. */
        fun deviceOf(transcript: Transcript): ReaderDevice {
            val source = transcript.source
            val fallback = if (source == "PROXMARK") "Proxmark lab reader" else "PC/SC lab reader"
            return ReaderDevice(
                model = transcript.directives[Transcript.Directive.READER] ?: fallback,
                androidVersion = "n/a",
                controllerMaxTransceiveLength = null,
                controllerExtendedLengthSupported = transcript.directives[Transcript.Directive.EXTENDED_LENGTH]?.toBooleanStrict() ?: true
            )
        }
    }
}

/** Wraps a transport and keeps every exchange, to write a [Transcript] of a session: how lab-style transcripts are made in tests. */
class RecordingTransport(private val inner: ApduTransport) : ApduTransport {
    private val recorded = ArrayList<TranscriptExchange>()

    override fun transmit(command: ByteArray): ByteArray {
        val response = inner.transmit(command)
        recorded += TranscriptExchange(command.copyOf(), response.copyOf())
        return response
    }

    override fun linkInfo(): LinkObservation? = inner.linkInfo()

    override fun readerDevice(): ReaderDevice = inner.readerDevice()

    fun transcript(directives: Map<String, String> = emptyMap()): Transcript = Transcript(recorded.toList(), directives)
}
