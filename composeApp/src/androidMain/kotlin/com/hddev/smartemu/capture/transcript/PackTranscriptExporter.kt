package com.hddev.smartemu.capture.transcript

import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.CaptureSource
import com.hddev.smartemu.capture.Hex
import com.hddev.smartemu.capture.LinkObservation
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ReaderDevice

/**
 * Writes the commands a lab operator sends to a chip with a PC/SC reader or a Proxmark, in the order the capture runner
 * sends them, so the session can be recorded as a [Transcript] and imported ([TranscriptImporter]).
 *
 * The list is made by running the real [CaptureRunner] against a reference chip, so it can't drift from the runner: the
 * commands of the probe packs (their SELECT set-ups, preambles, repeats and steps) are exact, and so are the fixed
 * commands that read the public files and probe BAC. The one thing that depends on the chip is how many READ BINARY
 * commands read a file: the list assumes each public file (EF.CardAccess, EF.ATR/INFO, EF.DIR) fits one READ BINARY of
 * 223 bytes. For a longer file the runner sends the next READ BINARY at the offset where the last one ended with the
 * same Le, and the operator adds it; the importer's mismatch message names the command it expected. See docs/lab-captures.md.
 *
 * Nothing in the list is sent with a key, a CAN or an MRZ.
 */
object PackTranscriptExporter {

    /**
     * The command sheet for [packs] (the first is the main pack): `# comment` lines naming each step, and one `> command`
     * line per command. A sheet is not yet a transcript: the operator's tool adds a `< response` line after each command.
     * [extendedLength] false leaves out the extended-length steps, for a reader that can't send them.
     */
    fun render(
        packs: List<ProbePack>,
        extendedLength: Boolean = true,
        reader: String? = null,
        source: CaptureSource = CaptureSource.PCSC
    ): String {
        require(packs.isNotEmpty()) { "No probe pack to export" }
        require(source != CaptureSource.ANDROID_READER) { "A lab transcript is a PCSC or PROXMARK capture" }
        val chip = ReferenceChip(extendedLength)
        var label = ""
        val runner = CaptureRunner(pack = packs.first(), additionalPacks = packs.drop(1), source = source, acknowledgeSacrificialCard = true)
        val result = runner.run(chip) { step, _, _ -> label = step; if (step != "done") chip.label(step) }
        check(result is CaptureResult.Complete) { "The reference run did not complete: $label" }
        return buildString {
            appendLine("# Command sheet for the probe pack${if (packs.size > 1) "s" else ""} ${packs.joinToString(", ") { "${it.id} v${it.version}" }}.")
            appendLine("# Send each command to the chip, in this order, and write the response after it as a '< ...' line (data then status word).")
            appendLine("# Send nothing else, and nothing in between: the importer replays this sequence and refuses any other.")
            appendLine("# Assumes each public file fits one READ BINARY; for a longer file add the next READ BINARY, see docs/lab-captures.md.")
            if (packs.any { it.requiresSacrificialCardAcknowledgement }) {
                appendLine("# TIER C: use a sacrificial or test card only. This pack may count failed attempts.")
            }
            appendLine("#! ${Transcript.Directive.SOURCE}: ${source.name}")
            reader?.let { appendLine("#! ${Transcript.Directive.READER}: $it") }
            appendLine("#! ${Transcript.Directive.EXTENDED_LENGTH}: $extendedLength")
            appendLine("#! ${Transcript.Directive.PACKS}: ${packs.joinToString(", ") { "${it.id}/${it.version}" }}")
            chip.lines.forEach { appendLine(it) }
        }
    }

    /** The commands alone, in order, as bytes: what a script sends. */
    fun commands(packs: List<ProbePack>, extendedLength: Boolean = true): List<ByteArray> {
        val chip = ReferenceChip(extendedLength)
        val runner = CaptureRunner(pack = packs.first(), additionalPacks = packs.drop(1), acknowledgeSacrificialCard = true)
        check(runner.run(chip) is CaptureResult.Complete) { "The reference run did not complete" }
        return chip.sent
    }

    /**
     * A chip that answers the way the commands list assumes: the application and the master file select, EF.CardAccess is
     * there and fits one READ BINARY, EF.ATR/INFO and EF.DIR aren't there. It keeps the commands in the order sent.
     */
    private class ReferenceChip(private val extendedLength: Boolean) : ApduTransport {
        val lines = ArrayList<String>()
        val sent = ArrayList<ByteArray>()
        private var lastFileSelected: Int? = null

        fun label(step: String) {
            lines += "# $step"
        }

        override fun transmit(command: ByteArray): ByteArray {
            lines += "> ${Hex.encode(command)}"
            sent += command.copyOf()
            val ins = command[1].toInt() and 0xFF
            val p1 = command[2].toInt() and 0xFF
            return when {
                ins == 0xA4 && p1 == 0x02 || ins == 0xA4 && p1 == 0x00 -> {
                    val fid = if (command.size >= 7) ((command[5].toInt() and 0xFF) shl 8) or (command[6].toInt() and 0xFF) else 0
                    lastFileSelected = fid
                    if (fid == 0x3F00 || fid == 0x011C) byteArrayOf(0x90.toByte(), 0x00) else byteArrayOf(0x6A, 0x82.toByte())
                }
                ins == 0xB0 && lastFileSelected == 0x011C && command.size == 5 -> byteArrayOf(0x31, 0x00, 0x90.toByte(), 0x00)
                ins == 0xB0 -> byteArrayOf(0x6A, 0x82.toByte())
                else -> byteArrayOf(0x90.toByte(), 0x00)
            }
        }

        override fun linkInfo(): LinkObservation = LinkObservation(NfcTechnology.NFC_A)

        override fun readerDevice(): ReaderDevice = ReaderDevice(
            model = "Reference", androidVersion = "n/a", controllerMaxTransceiveLength = null, controllerExtendedLengthSupported = extendedLength
        )
    }
}
