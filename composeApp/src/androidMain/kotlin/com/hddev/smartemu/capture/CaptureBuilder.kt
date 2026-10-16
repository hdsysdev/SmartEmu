package com.hddev.smartemu.capture

import java.util.UUID

/**
 * The only way to make a [CaptureRecord]. Every setter takes a typed observation and stores no more than the schema
 * allows: there is no setter that takes free text, and the three files a capture holds raw (EF.CardAccess, EF.ATR/INFO,
 * EF.DIR) each have their own setter. Sizes are rounded here, the capture date is the month only, and the capture id
 * is a random UUID, not derived from the device, the chip or the time.
 *
 * Setters can be called in any order and again to overwrite, so that an interrupted run can be resumed from a
 * [CaptureDraft].
 */
class CaptureBuilder private constructor(private val captureId: String) {
    private var captureMonth: String? = null
    private var source: CaptureSource = CaptureSource.ANDROID_READER
    private var probePack: ProbePackRef? = null
    private var additionalProbePacks: List<ProbePackRef> = emptyList()
    private var reader: ReaderDevice? = null
    private var link: LinkObservation? = null
    private var files = UnauthenticatedFiles()
    private var application: ApplicationObservation? = null
    private var bacCommands: BacCommandObservation? = null
    private var authenticated: AuthenticatedObservation? = null
    private val probes = ArrayList<ProbeResult>()
    private var aborted: AbortReason? = null

    // What a resume must know but a record doesn't hold (see DraftState)
    private var externalAuthenticateSent = false
    private var canAttemptStarted = false

    fun setSource(source: CaptureSource): CaptureBuilder = apply { this.source = source }

    fun setProbePack(pack: ProbePackRef): CaptureBuilder = apply { probePack = pack }

    /** The packs run after the main one in the same session, in order. */
    fun setAdditionalProbePacks(packs: List<ProbePackRef>): CaptureBuilder = apply { additionalProbePacks = packs.toList() }

    /** The capture month, from [year] and [month] (1 to 12); the day and time are never taken. */
    fun setCaptureMonth(year: Int, month: Int): CaptureBuilder = apply {
        require(year in 2000..9999 && month in 1..12) { "Not a month: $year-$month" }
        captureMonth = "%04d-%02d".format(java.util.Locale.ROOT, year, month)
    }

    /** The reading device; its names are cut to a short plain form, since they come from the phone's firmware. */
    fun setReader(device: ReaderDevice): CaptureBuilder = apply {
        reader = device.copy(
            model = plain(device.model),
            androidVersion = plain(device.androidVersion),
            nfcChipset = device.nfcChipset?.let(::plain)
        )
    }

    /**
     * What the link showed. Historical bytes are kept only in a form that names nothing identifying (see
     * [PublicFileRules.reduceHistorical]): the issuer's and pre-issuing data are cut out, and bytes that aren't short
     * hex in a known category are dropped, in which case only their length is kept in `historicalBytesLength`.
     */
    fun setLink(observation: LinkObservation): CaptureBuilder = apply {
        val given = observation.historicalBytes
        val original = given?.takeIf { Hex.isHex(it) && it.length <= MAX_HISTORICAL_HEX }?.let(Hex::decode)
        val kept = original?.let { PublicFileRules.reduceHistorical(it) }
        link = observation.copy(
            historicalBytes = kept?.let(Hex::encode),
            historicalBytesLength = when {
                given == null || !Hex.isHex(given) -> observation.historicalBytesLength
                kept != null && original.contentEquals(kept) -> observation.historicalBytesLength
                else -> (given.length / 2).coerceAtMost(PublicFileRules.MAX_HISTORICAL_BYTES)
            }
        )
    }

    fun setApplication(selectStatusWord: Int, responseLength: Int?): CaptureBuilder = apply {
        application = ApplicationObservation(selectStatusWord, responseLength?.let(Redaction::probeSizeRange))
    }

    /** EF.CardAccess, raw and in the chip's order, or null if it wasn't readable. */
    fun setCardAccess(selectStatusWord: Int, contents: ByteArray?): CaptureBuilder = apply {
        files = files.copy(cardAccess = file(selectStatusWord, contents, PublicFileRules::reduceCardAccess))
    }

    /** EF.ATR/INFO (2F01), raw, or null if it wasn't readable. */
    fun setAtrInfo(selectStatusWord: Int, contents: ByteArray?): CaptureBuilder = apply {
        files = files.copy(atrInfo = file(selectStatusWord, contents, PublicFileRules::reduceAtrInfo))
    }

    /** EF.DIR (2F00), raw, or null if it wasn't readable. */
    fun setDir(selectStatusWord: Int, contents: ByteArray?): CaptureBuilder = apply {
        files = files.copy(dir = file(selectStatusWord, contents, PublicFileRules::reduceDir))
    }

    fun setBacCommands(getChallenge: Int?, externalAuthenticate: Int?): CaptureBuilder = apply {
        bacCommands = BacCommandObservation(getChallenge, externalAuthenticate)
    }

    /** Adds the result of one probe, replacing an earlier result of the same probe and pack version. */
    fun addProbe(result: ProbeResult): CaptureBuilder = apply {
        probes.removeAll { it.probeId == result.probeId && it.packVersion == result.packVersion }
        probes += result
    }

    fun setAborted(reason: AbortReason?): CaptureBuilder = apply { aborted = reason }

    fun hasApplication(): Boolean = application != null

    fun hasBacCommands(): Boolean = bacCommands != null

    /**
     * What an authenticated read showed (see [AuthenticatedObservation], which holds only structure). Replaces an earlier
     * one, so that a resumed run overwrites a partial read.
     */
    fun setAuthenticated(observation: AuthenticatedObservation?): CaptureBuilder = apply { authenticated = observation }

    fun hasAuthenticated(): Boolean = authenticated != null

    /** The raw EF.CardAccess already read, if it was, so that an authenticated read doesn't ask the chip for it again. */
    fun cardAccessContents(): ByteArray? = files.cardAccess?.contentsHex?.let(Hex::decode)

    fun hasCardAccess(): Boolean = files.cardAccess != null

    fun hasAtrInfo(): Boolean = files.atrInfo != null

    fun hasDir(): Boolean = files.dir != null

    fun hasProbe(probeId: String, packVersion: Int): Boolean = probes.any { it.probeId == probeId && it.packVersion == packVersion }

    /** The result already added for [probeId] under pack [packVersion], or null. */
    fun probeResult(probeId: String, packVersion: Int): ProbeResult? =
        probes.firstOrNull { it.probeId == probeId && it.packVersion == packVersion }

    /** Whether a pack with [packId] at [minVersion] or later is among the packs of this capture. */
    fun runsPack(packId: String, minVersion: Int): Boolean =
        (listOfNotNull(probePack) + additionalProbePacks).any { it.id == packId && it.version >= minVersion }

    /** Whether an EXTERNAL AUTHENTICATE was sent in this capture, here or in the run the draft came from. */
    fun externalAuthenticateSent(): Boolean = externalAuthenticateSent

    /** Whether a PACE attempt with a CAN was begun in this capture. */
    fun canAttemptStarted(): Boolean = canAttemptStarted

    /**
     * Notes that a PACE attempt with a CAN is about to start, before its first command: a chip may count the attempt
     * even if the tag is lost before it answers, so a resume must not make it silently again.
     */
    fun markCanAttemptStarted(): CaptureBuilder = apply { canAttemptStarted = true }

    /** What a [CaptureDraft] of this capture holds besides the record; [sessionSentExternalAuthenticate] is the session's flag. */
    fun draftState(sessionSentExternalAuthenticate: Boolean = false): DraftState =
        DraftState(externalAuthenticateSent || sessionSentExternalAuthenticate, canAttemptStarted)

    /**
     * Whether this capture's chip answered as [other]'s did, for each of the application SELECT and the three public
     * files that both hold. Nothing to compare counts as the same: a draft that only got as far as the reader and the
     * link has nothing to tell.
     */
    fun sameDocumentAs(other: CaptureBuilder): Boolean =
        listOf(
            application to other.application,
            files.cardAccess to other.files.cardAccess,
            files.atrInfo to other.files.atrInfo,
            files.dir to other.files.dir
        ).all { (mine, theirs) -> mine == null || theirs == null || mine == theirs }

    /** The pack the draft was started with, to check that a resume is on the same one. */
    fun probePackRef(): ProbePackRef? = probePack

    fun additionalProbePackRefs(): List<ProbePackRef> = additionalProbePacks

    /** The record so far, with its fingerprint. Throws [IllegalStateException] if the month, pack, reader or link isn't set. */
    fun build(): CaptureRecord {
        val unsealed = CaptureRecord(
            captureId = captureId,
            captureMonth = checkNotNull(captureMonth) { "The capture month isn't set" },
            source = source,
            probePack = checkNotNull(probePack) { "The probe pack isn't set" },
            additionalProbePacks = additionalProbePacks,
            reader = checkNotNull(reader) { "The reader isn't set" },
            link = checkNotNull(link) { "The link isn't set" },
            files = files,
            application = application,
            bacCommands = bacCommands,
            authenticated = authenticated,
            redactions = RedactionManifest.expected(CAPTURE_SCHEMA_VERSION, authenticated != null),
            probes = probes.toList(),
            aborted = aborted
        )
        return CaptureFingerprint.seal(unsealed)
    }

    /**
     * A public file as the capture keeps it: what [reduce] (the structural allowlist of [PublicFileRules]) leaves of the
     * bytes. If nothing parseable is left only the length is kept, as a bucket. The size is the stored bytes' when they are kept.
     */
    private fun file(selectStatusWord: Int, contents: ByteArray?, reduce: (ByteArray) -> ByteArray?): FileObservation {
        val kept = contents?.let(reduce)
        return FileObservation(
            selectStatusWord,
            kept?.let(Hex::encode),
            (kept?.size ?: contents?.size)?.let(Redaction::sizeRange)
        )
    }

    /** A name from the phone's firmware in the plain ASCII form the lint accepts, and one that can't be a CAN or a document number. */
    private fun plain(text: String): String {
        val cleaned = text.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in " ._+-/()" }
            .trim().replace(Regex(" {2,}"), " ").take(MAX_NAME).trim()
        return if (cleaned.isEmpty() || RecordLint.deviceNameProblem(cleaned) != null) "unknown" else cleaned
    }

    companion object {
        private const val MAX_NAME = 48
        private const val MAX_HISTORICAL_HEX = 128

        /** A new capture with a random id. */
        fun create(): CaptureBuilder = CaptureBuilder(UUID.randomUUID().toString())

        /** A builder holding what [draft] already has, under the same capture id. */
        fun resume(draft: CaptureDraft): CaptureBuilder {
            val record = draft.record
            return CaptureBuilder(record.captureId).also { builder ->
                val (year, month) = record.captureMonth.split('-').map { it.toInt() }
                builder.setCaptureMonth(year, month)
                    .setSource(record.source)
                    .setProbePack(record.probePack)
                    .setAdditionalProbePacks(record.additionalProbePacks)
                    .setReader(record.reader)
                    .setLink(record.link)
                builder.files = record.files
                builder.application = record.application
                builder.bacCommands = record.bacCommands
                builder.authenticated = record.authenticated
                builder.probes += record.probes
                builder.externalAuthenticateSent = draft.state.externalAuthenticateSent
                builder.canAttemptStarted = draft.state.canAttemptStarted
            }
        }
    }
}

/**
 * What a stopped run had captured, for resuming after a tag was lost. It is the partial [CaptureRecord] itself, so it
 * holds nothing a record doesn't, and what is in it is not read again; [state] is what the run remembers that a record
 * has no field for.
 */
data class CaptureDraft(val record: CaptureRecord, val state: DraftState = DraftState())

/**
 * What a resume must know, kept in memory with the draft and never in a record.
 *
 * - [externalAuthenticateSent]: an EXTERNAL AUTHENTICATE was handed to the chip. A chip may count it as a failed BAC
 *   attempt even when the tag was lost before it answered, so a resume doesn't send one again: the probe that holds it
 *   is left out of the record.
 * - [canAttemptStarted]: a PACE attempt with a CAN began (see [CaptureBuilder.markCanAttemptStarted]). A resume asks
 *   the person again, saying the attempt may have counted, before it tries the CAN again.
 */
data class DraftState(val externalAuthenticateSent: Boolean = false, val canAttemptStarted: Boolean = false)
