package com.hddev.smartemu.capture

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Bumped on any change to [CaptureRecord]'s JSON that an older strict reader would refuse or misread; see
 * docs/capture-schema.md for the migration policy. Version 2 made `fingerprint` the generation key and added
 * `observationDigest`; version 1 records are still read and are upgraded by [CaptureMigration].
 */
const val CAPTURE_SCHEMA_VERSION = 2

/** The oldest schema version [CaptureJson.decode] still reads (and upgrades). */
const val MIN_CAPTURE_SCHEMA_VERSION = 1

/** Where a capture was taken from, so lab captures from USB readers can be ingested next to phone captures. */
@Serializable
enum class CaptureSource { ANDROID_READER, PCSC, PROXMARK }

/**
 * A redacted record of how one real chip behaved, built only by typed extractors (see [CaptureBuilder]).
 *
 * This is an allowlist: every field here is approved to leave the phone, and nothing else is. There is no free-text
 * field and no raw transcript. Anything that could identify the holder or the individual chip (DG1, DG2, DG7, DG11,
 * DG12 contents, the MRZ, the CAN, the UID, per-chip AA/CA public keys, EF.SOD data-group hashes, per-chip parts of
 * EF.CardSecurity) has no field, and [redactions] lists what was left out.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CaptureRecord(
    val schemaVersion: Int = CAPTURE_SCHEMA_VERSION,
    /** Random, not derived from the device, the chip or the time. */
    val captureId: String,
    /** "YYYY-MM": the month only, so a capture can't be tied to a day. */
    val captureMonth: String,
    val source: CaptureSource,
    val probePack: ProbePackRef,
    /**
     * Further packs run in the same session after [probePack] (for example tier-b after tier-a), in the order they
     * ran. Each probe result names its own pack version in [ProbeResult.packVersion]; empty for a one-pack capture.
     */
    val additionalProbePacks: List<ProbePackRef> = emptyList(),
    val reader: ReaderDevice,
    val link: LinkObservation,
    /** The files read without authentication; null for a file the chip didn't offer. */
    val files: UnauthenticatedFiles = UnauthenticatedFiles(),
    /** How the chip answered SELECT of the eMRTD application; null if the run stopped before that. */
    val application: ApplicationObservation? = null,
    /** The status words of the BAC commands before any authentication; null if the run stopped before that. */
    val bacCommands: BacCommandObservation? = null,
    /**
     * What an authenticated read (MRZ or CAN) showed; null for a capture without one, in which case the field is left out
     * of the JSON, so a record without authentication is the same text it was before this field existed.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val authenticated: AuthenticatedObservation? = null,
    /** Probe results keyed by probe id and pack version, in the order they ran. */
    val probes: List<ProbeResult> = emptyList(),
    /** True if the run stopped early (tag lost, a blocked status word); the missing probes are absent, not failed. */
    val aborted: AbortReason? = null,
    /**
     * The generation key: a hash of what the chip itself fixes (EF.CardAccess, the application SELECT, EF.ATR/INFO and
     * EF.DIR), shared by every capture of one kind of chip. Under schema version 1 it was the hash of every observation.
     * See [CaptureFingerprint].
     */
    val fingerprint: String = "",
    /**
     * Schema version 2: a hash of every observation, for exact equality; empty in a draft and in a version 1 record as
     * written (a decoded version 1 record is upgraded and gets one). Omitted from the JSON while empty.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observationDigest: String = "",
    val redactions: List<RedactionEntry> = RedactionManifest.entries
)

@Serializable
data class ProbePackRef(val id: String, val version: Int)

/**
 * The device that read the chip. Its limits are the phone's NFC controller's, not the chip's, so merging treats them
 * as reader-side confounders.
 */
@Serializable
data class ReaderDevice(
    val model: String,
    val androidVersion: String,
    val nfcChipset: String? = null,
    val controllerMaxTransceiveLength: Int? = null,
    val controllerExtendedLengthSupported: Boolean? = null
)

/**
 * What the link layer showed. Not emulatable on Android HCE, so recorded and labelled, never claimed (see
 * [NotEmulatable]).
 */
@Serializable
data class LinkObservation(
    val technology: NfcTechnology,
    /** NFC-A historical bytes or NFC-B higher-layer response, hex. Android exposes nothing more of the ATS. */
    val historicalBytes: String? = null,
    /** True if the UID looked random (0x08 prefix) and a second tap gave a different one; the UID itself is never stored. */
    val uidRandom: Boolean? = null,
    val maxApduLengthObserved: Int? = null,
    /**
     * The length of the historical bytes (or higher-layer response) when [historicalBytes] had to be cut down or left out
     * because they weren't in a form that holds nothing identifying; null when stored whole. Omitted from the JSON while null.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val historicalBytesLength: Int? = null
)

@Serializable
enum class NfcTechnology { NFC_A, NFC_B }

/** The properties Android HCE can't copy, whatever the capture shows. */
object NotEmulatable {
    val properties: List<String> = listOf(
        "NFC-B: HCE offers NFC-A only",
        "UID, ATS and historical bytes: HCE can't set them",
        "Behaviour before the eMRTD application is selected: Android answers only once the AID has been selected"
    )
}

/** Files readable with no access control. Bytes are hex, and kept in the chip's own order. */
@Serializable
data class UnauthenticatedFiles(
    /** EF.CardAccess (011C), raw: public PACEInfo and friends, in the order the chip wrote them. */
    val cardAccess: FileObservation? = null,
    /** EF.ATR/INFO (2F01). */
    val atrInfo: FileObservation? = null,
    /** EF.DIR (2F00). */
    val dir: FileObservation? = null
)

/** The answer to SELECT of the eMRTD application (AID A0000002471001). */
@Serializable
data class ApplicationObservation(
    val selectStatusWord: Int,
    /** The length of the response data (the FCI, if the chip returns one), in [Redaction.PROBE_BUCKET]-byte buckets. */
    val responseSize: SizeRange? = null
)

/**
 * The status words of the commands BAC starts with, sent before any authentication. A null is no answer. Only the
 * answers are kept: the challenge itself is random and never stored.
 */
@Serializable
data class BacCommandObservation(
    /** GET CHALLENGE with Le 08. */
    val getChallenge: Int? = null,
    /** EXTERNAL AUTHENTICATE with 40 bytes of zeros and no GET CHALLENGE before it: the out-of-sequence answer. */
    val externalAuthenticate: Int? = null
)

@Serializable
data class FileObservation(
    /** The SELECT's status word. */
    val selectStatusWord: Int,
    /** Raw contents as hex; null if the file wasn't readable. */
    val contentsHex: String? = null,
    /** A size bucket, never the exact length. */
    val size: SizeRange? = null
)

/** A size rounded out to [Redaction.SIZE_BUCKET]-byte buckets, so exact lengths can't single out a chip. */
@Serializable
data class SizeRange(val minBytes: Int, val maxBytes: Int)

@Serializable
data class ProbeResult(
    val probeId: String,
    val packVersion: Int,
    val tier: ProbeTier,
    val steps: List<StepResult>
)

@Serializable
enum class ProbeTier { A, B, C }

@Serializable
data class StepResult(
    val stepId: String,
    /** Null if no answer came: the reader couldn't send the command at all (a tag lost ends the run instead). */
    val statusWord: Int?,
    /** Length of the response data, in [Redaction.PROBE_BUCKET]-byte buckets. */
    val responseSize: SizeRange? = null,
    val timing: TimingSummary? = null,
    /** The status word of the re-SELECT that put the chip in the step's starting state; null if the step needs none. */
    val setupStatusWord: Int? = null,
    /** False if repeating the command gave different status words, in which case [statusWord] is the first. */
    val repeatsAgree: Boolean = true
)

/** Informational only; never diffed exactly. */
@Serializable
data class TimingSummary(val minMs: Long, val medianMs: Long, val maxMs: Long, val samples: Int)

@Serializable
enum class AbortReason { TAG_LOST, BLOCKED_STATUS_WORD, USER_CANCELLED, UNSUPPORTED_CHIP }

/** One thing the capture deliberately left out, and why, so the phone's "what will be saved" preview can show it. */
@Serializable
data class RedactionEntry(val item: String, val reason: String)

/** The fixed list of what is never stored, written into every capture and shown on the preview screen. */
object RedactionManifest {
    /** The list schema version 1 records carry. */
    val entriesV1: List<RedactionEntry> = listOf(
        RedactionEntry("DG1 contents and the MRZ", "Identifies the holder"),
        RedactionEntry("DG2 contents (portrait)", "Identifies the holder"),
        RedactionEntry("DG7, DG11 and DG12 contents", "Identify the holder"),
        RedactionEntry("The CAN and any access key", "Grants access to the chip"),
        RedactionEntry("The chip UID", "Identifies a single chip"),
        RedactionEntry("Per-chip Active Authentication and Chip Authentication public keys", "Identify a single chip"),
        RedactionEntry("EF.SOD data-group hash values", "The DG1 hash can be brute-forced from the MRZ space"),
        RedactionEntry("Per-chip parts of EF.CardSecurity", "Identify a single chip"),
        RedactionEntry("Exact file sizes", "Rounded to buckets: quasi-identifiers"),
        RedactionEntry("Capture day and time", "Only the month is kept"),
        RedactionEntry("The raw APDU transcript", "Only typed observations are saved")
    )

    /** Schema version 2: [entriesV1] and what the public files are reduced to. */
    val entries: List<RedactionEntry> = entriesV1 + RedactionEntry(
        "Per-chip and per-issuance data objects inside the public files and the historical bytes",
        "Public key infos in EF.CardAccess, issuer and pre-issuing data in EF.ATR/INFO and the historical bytes, and everything but the application identifiers in EF.DIR are dropped before saving"
    )

    /** What a record of [schemaVersion] must carry in [CaptureRecord.redactions], given whether it holds an authenticated read. */
    fun expected(schemaVersion: Int, authenticated: Boolean): List<RedactionEntry> =
        (if (schemaVersion < 2) entriesV1 else entries) + if (authenticated) authenticatedEntries else emptyList()

    /**
     * What an authenticated read leaves out besides the above, added to the record's [CaptureRecord.redactions] when it
     * holds an [AuthenticatedObservation]. A superset of [entries], so that a record without one is unchanged.
     */
    val authenticatedEntries: List<RedactionEntry> = listOf(
        RedactionEntry(
            "The document signer certificate's serial number, key identifier, names and bytes",
            "Identify one signer certificate; only its algorithms, key size and curve, issuer country and validity length are kept"
        ),
        RedactionEntry("EF.CVCA beyond the country of its certification authority", "The rest names a key generation of one issuer"),
        RedactionEntry("Data group contents beyond the length in their BER header", "DG1, DG2, DG7, DG11 and DG12 identify the holder; the header's bytes are erased once the length is taken")
    )
}

/** Rounding that keeps quasi-identifiers out of a capture. */
object Redaction {
    const val SIZE_BUCKET = 256

    /**
     * The finer bucket for probe responses. Probes read only the public EF.CardAccess, so their lengths are no
     * quasi-identifier, and 256 bytes is too coarse to tell a chip that answers 223 bytes from one that answers 255.
     */
    const val PROBE_BUCKET = 16

    fun probeSizeRange(size: Int): SizeRange {
        val lo = size / PROBE_BUCKET * PROBE_BUCKET
        return SizeRange(lo, lo + PROBE_BUCKET - 1)
    }

    fun sizeRange(size: Int): SizeRange {
        val lo = size / SIZE_BUCKET * SIZE_BUCKET
        return SizeRange(lo, lo + SIZE_BUCKET - 1)
    }
}
