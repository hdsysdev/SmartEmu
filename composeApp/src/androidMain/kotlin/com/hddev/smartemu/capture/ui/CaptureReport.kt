package com.hddev.smartemu.capture.ui

import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.FileObservation
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.capture.NotEmulatable
import com.hddev.smartemu.capture.ProbeResult
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set

/**
 * The one-page report of a capture, built from the [CaptureRecord] alone so that it holds nothing the record doesn't:
 * no document details, no UID, no transcript. Plain text, to read in a mail or chat.
 */
object CaptureReport {

    /** The fidelity contract's wording, as the capture plan fixes it. */
    const val FIDELITY_CONTRACT =
        "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, for the fields marked " +
            "compared, from n captures."

    const val NOT_READ = "Not read (no access key used)"

    fun render(record: CaptureRecord, match: MatchSummary = MatchSummary.NotCompared): String = buildString {
        line("PassportEmu capture report")
        line("==========================")
        line("Captured: ${record.captureMonth} (month only)")
        line("Capture ID: ${record.captureId}")
        line("Probe pack: ${record.probePack.id} v${record.probePack.version}")
        line("Read with: ${record.reader.model}, Android ${record.reader.androidVersion}")
        line(
            record.aborted?.let { "Run: stopped early (${abortText(it)}); probes after that are missing, not failed" }
                ?: "Run: completed"
        )
        blank()

        val authenticated = record.authenticated
        section("The document")
        if (authenticated == null) {
            line("Country: $NOT_READ")
            line("Document type: $NOT_READ")
            line("Holder details: never read, never saved")
        } else {
            line("Country: ${authenticated.sod?.signer?.issuerCountry?.let { "$it (country of the signing authority; the holder's is not saved)" } ?: "not saved"}")
            line("Document type: not saved (the MRZ is never kept)")
            line("Holder details: used in memory only, never saved")
            line("Access key: ${if (authenticated.credential == CredentialKind.CAN) "CAN" else "MRZ"}, used in memory only, never saved")
        }
        blank()

        section("Protocols offered (EF.CardAccess, read without a key)")
        protocols(record.files.cardAccess)
        blank()

        section("Link layer (can't be copied by Android HCE)")
        line("Technology: ${if (record.link.technology == NfcTechnology.NFC_B) "NFC-B (type B)" else "NFC-A (type A)"}")
        line("Historical bytes / higher-layer response: ${record.link.historicalBytes ?: "none reported"}")
        line(
            "UID random: " + when (record.link.uidRandom) {
                true -> "yes (0x08 prefix, confirmed by a second tap)"
                false -> "no (the UID is not random)"
                null -> "unconfirmed"
            } + " (the UID itself is never saved)"
        )
        blank()

        section("Capturing phone (the phone's NFC controller, not the document's)")
        line("Max command length the phone's NFC controller accepts: ${record.reader.controllerMaxTransceiveLength ?: "unknown"}")
        line(
            "Extended-length APDUs supported by the phone's controller: " +
                (record.reader.controllerExtendedLengthSupported?.let { if (it) "yes" else "no" } ?: "unknown")
        )
        line("NFC chipset: ${record.reader.nfcChipset ?: "unknown"}")
        blank()

        section("Files read without a key")
        fileLine("EF.CardAccess (011C)", record.files.cardAccess)
        fileLine("EF.ATR/INFO (2F01)", record.files.atrInfo)
        fileLine("EF.DIR (2F00)", record.files.dir)
        blank()

        if (authenticated != null) {
            section("Read with the document's key")
            line(AuthenticatedSummary.MEMORY_ONLY_NOTE)
            line("Result: ${AuthenticatedSummary.notice(AuthenticatedSummary.outcome(authenticated)).title}")
            AuthenticatedSummary.sections(authenticated).forEach { part ->
                blank()
                line(part.title)
                part.rows.forEach { row -> line("  ${row.label}: ${row.value}") }
            }
            blank()
        } else {
            section("Read with the document's key")
            line("Not done (no access key used).")
            blank()
        }

        section("Probes: status words")
        if (record.probes.isEmpty()) {
            line("No probes ran.")
        } else {
            statusTable(record.probes)
        }
        blank()

        section("Identification")
        line("Fingerprint: ${record.fingerprint.ifEmpty { "not computed" }}")
        line("Matched profile: ${match.description}")
        blank()

        section("Limits")
        line(FIDELITY_CONTRACT)
        line("Link-layer properties are listed as not emulatable on Android HCE and are never claimed:")
        NotEmulatable.properties.forEach { line("  - $it") }
        line("Timing, where recorded, is informational and never compared exactly.")
        blank()

        section("Left out of this record, on purpose")
        record.redactions.forEach { line("  - ${it.item}: ${it.reason}") }
    }.trimEnd() + "\n"

    private fun StringBuilder.line(text: String) = append(text).append('\n')
    private fun StringBuilder.blank() = append('\n')
    private fun StringBuilder.section(title: String) {
        line(title.uppercase())
    }

    private fun StringBuilder.protocols(cardAccess: FileObservation?) {
        val hex = cardAccess?.contentsHex
        when {
            cardAccess == null -> line("EF.CardAccess wasn't read.")
            hex == null -> line(
                "The document didn't offer EF.CardAccess (select answered ${statusWord(cardAccess.selectStatusWord)}). " +
                    "It may use BAC only, or not be an eMRTD."
            )
            else -> {
                val summary = CardAccessSummary.parse(hex)
                if (summary == null) {
                    line("EF.CardAccess was read but couldn't be parsed.")
                } else {
                    if (summary.pace.isEmpty()) line("No PACE suites offered.")
                    summary.pace.forEach { line("  - PACE: $it") }
                    summary.other.forEach { line("  - $it") }
                }
            }
        }
    }

    private fun StringBuilder.fileLine(name: String, file: FileObservation?) {
        when {
            file == null -> line("$name: not read")
            file.contentsHex == null -> line("$name: not offered, select answered ${statusWord(file.selectStatusWord)}")
            else -> line("$name: read, select ${statusWord(file.selectStatusWord)}, size ${sizeText(file.size)}")
        }
    }

    private fun StringBuilder.statusTable(probes: List<ProbeResult>) {
        val rows = probes.flatMap { probe ->
            probe.steps.map { step ->
                listOf(
                    "${probe.probeId} (tier ${probe.tier})",
                    step.stepId,
                    step.statusWord?.let(::statusWord) ?: "no answer",
                    sizeText(step.responseSize)
                )
            }
        }
        val header = listOf("Probe", "Step", "Status", "Response size")
        val widths = header.indices.map { column -> (rows.map { it[column].length } + header[column].length).max() }
        fun row(cells: List<String>) = line(cells.indices.joinToString("  ") { cells[it].padEnd(widths[it]) }.trimEnd())
        row(header)
        row(widths.map { "-".repeat(it) })
        rows.forEach(::row)
    }

    private fun abortText(reason: com.hddev.smartemu.capture.AbortReason) = when (reason) {
        com.hddev.smartemu.capture.AbortReason.TAG_LOST -> "the document moved away"
        com.hddev.smartemu.capture.AbortReason.BLOCKED_STATUS_WORD -> "the document reported it was locked"
        com.hddev.smartemu.capture.AbortReason.USER_CANCELLED -> "stopped by the user before the key was tried"
        com.hddev.smartemu.capture.AbortReason.UNSUPPORTED_CHIP -> "not a chip this app can read"
    }

    internal fun sizeText(size: SizeRange?): String =
        if (size == null) "none" else "${size.minBytes}-${size.maxBytes} bytes"

    /** "9000 (OK)", "6A82 (file not found)": the status word as hex, with its usual meaning where there is one. */
    internal fun statusWord(sw: Int): String {
        val hex = "%04X".format(sw and 0xFFFF)
        val meaning = STATUS_MEANINGS[sw and 0xFFFF] ?: return hex
        return "$hex ($meaning)"
    }

    private val STATUS_MEANINGS = mapOf(
        0x9000 to "OK",
        0x6300 to "authentication failed",
        0x6700 to "wrong length",
        0x6982 to "security status not satisfied",
        0x6983 to "authentication method blocked",
        0x6984 to "reference data not usable",
        0x6985 to "conditions of use not satisfied",
        0x6A80 to "incorrect data",
        0x6A82 to "file not found",
        0x6A86 to "incorrect P1/P2",
        0x6B00 to "wrong parameters",
        0x6C00 to "wrong Le",
        0x6D00 to "instruction not supported",
        0x6E00 to "class not supported",
        0x6F00 to "no precise diagnosis"
    )
}

/** What EF.CardAccess offers, read from its raw bytes: the PACE suites and a count of the other entries. */
internal class CardAccessSummary(val pace: List<String>, val other: List<String>) {
    companion object {
        private const val ID_PACE = "0.4.0.127.0.7.2.2.4."
        private const val ID_CA = "0.4.0.127.0.7.2.2.3."
        private const val ID_CA_PUBLIC_KEY = "0.4.0.127.0.7.2.2.1."
        private const val ID_TA = "0.4.0.127.0.7.2.2.2"
        private const val ID_AA = "2.23.136.1.1.5"
        private const val ID_EF_DIR = "2.23.136.1.1.13"

        private val MAPPINGS = mapOf(
            "1" to "DH, Generic Mapping",
            "2" to "ECDH, Generic Mapping",
            "3" to "DH, Integrated Mapping",
            "4" to "ECDH, Integrated Mapping",
            "6" to "ECDH, Chip Authentication Mapping (PACE-CAM)"
        )
        private val CIPHERS = mapOf("1" to "3DES", "2" to "AES-128", "3" to "AES-192", "4" to "AES-256")

        fun parse(hex: String): CardAccessSummary? = try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val set = ASN1InputStream(bytes).use { it.readObject() } as? ASN1Set ?: return null
            val pace = mutableListOf<String>()
            var domainInfos = 0
            var caInfos = 0
            var caKeys = 0
            var taInfos = 0
            var aaInfos = 0
            var efDirInfos = 0
            var unknown = 0
            for (element in set) {
                val info = element as? ASN1Sequence ?: continue
                val oid = (info.getObjectAt(0) as? ASN1ObjectIdentifier)?.id ?: continue
                when {
                    oid.startsWith(ID_PACE) -> {
                        val rest = oid.removePrefix(ID_PACE).split('.')
                        if (rest.size == 2) {
                            val parameterId = (info.toArray().getOrNull(2) as? ASN1Integer)?.value?.toInt()
                            pace += "${MAPPINGS[rest[0]] ?: "mapping ${rest[0]}"}, " +
                                "${CIPHERS[rest[1]] ?: "cipher ${rest[1]}"}, " +
                                (parameterId?.let(::domainText) ?: "domain parameters not stated")
                        } else {
                            domainInfos++
                        }
                    }
                    oid.startsWith(ID_CA) -> caInfos++
                    oid.startsWith(ID_CA_PUBLIC_KEY) -> caKeys++
                    oid.startsWith(ID_TA) -> taInfos++
                    oid == ID_AA -> aaInfos++
                    oid == ID_EF_DIR -> efDirInfos++
                    else -> unknown++
                }
            }
            val other = buildList {
                if (domainInfos > 0) add("PACE domain parameter entries: $domainInfos")
                if (caInfos > 0) add("Chip Authentication entries: $caInfos")
                if (caKeys > 0) add("Chip Authentication public key entries: $caKeys")
                if (taInfos > 0) add("Terminal Authentication entries: $taInfos")
                if (aaInfos > 0) add("Active Authentication entries: $aaInfos")
                if (efDirInfos > 0) add("EF.DIR entries: $efDirInfos")
                if (unknown > 0) add("Other entries: $unknown")
            }
            CardAccessSummary(pace, other)
        } catch (_: Exception) {
            null
        }

        private fun domainText(id: Int): String =
            DhGroup.entries.find { it.paceParameterId == id }?.let { "${it.displayName} (parameter ID $id)" }
                ?: EcCurve.entries.find { it.paceParameterId == id }?.let { "${it.displayName} (parameter ID $id)" }
                ?: "parameter ID $id"
    }
}
