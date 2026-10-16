package com.hddev.smartemu.capture

/**
 * Two hashes of a capture, for two different questions. Both are 16 upper-case hex digits of a SHA-256 over plain text,
 * computed in Kotlin so the app, the tests and the intake tool agree.
 *
 * - [of], stored as [CaptureRecord.fingerprint], is the **generation key**: what identifies a kind of chip, and nothing
 *   else. It hashes only what the chip itself fixes: EF.CardAccess as the chip wrote it, the answer to SELECT of the
 *   eMRTD application, and EF.ATR/INFO and EF.DIR where there are any (each with the status word of its SELECT). It leaves out
 *   everything a reader, a phone, a holder or a day can change: probe status words and sizes, timing, the steps a phone
 *   couldn't send, the probe packs run, the authenticated read, the link and the reader. Captures of one kind of chip
 *   therefore share a key whatever they answered to the probes, which is what lets the hold-out rule fail: two captures of
 *   one generation can still disagree, and then a field earns nothing.
 * - [digestOf], stored as [CaptureRecord.observationDigest], hashes every observation (the probe answers, the packs, the
 *   authenticated structure): equal digests mean equal observations, for deduplication and for "did anything change".
 *
 * Hex is hashed in upper case whatever case it was written in.
 *
 * Schema version 1 records carry [legacyV1Of] in `fingerprint`, which hashed every observation, and no digest; see
 * [CaptureMigration].
 */
object CaptureFingerprint {
    private const val LENGTH = 16

    /** The generation key of [record]. */
    fun of(record: CaptureRecord): String = hash(
        buildString {
            append("generation/1\n")
            append("application=${record.application?.selectStatusWord}|${size(record.application?.responseSize)}\n")
            chipFile("cardAccess", record.files.cardAccess)
            chipFile("atrInfo", record.files.atrInfo)
            chipFile("dir", record.files.dir)
        }
    )

    /** The hash of every observation in [record]: equal if and only if the records observed the same. */
    fun digestOf(record: CaptureRecord): String = hash(
        buildString {
            append("digest/1\n")
            append("pack=${record.probePack.id}/${record.probePack.version}\n")
            if (record.additionalProbePacks.isNotEmpty()) {
                append("additionalPacks=${record.additionalProbePacks.joinToString(",") { "${it.id}/${it.version}" }}\n")
            }
            append("application=${record.application?.selectStatusWord}|${size(record.application?.responseSize)}\n")
            file("cardAccess", record.files.cardAccess)
            file("atrInfo", record.files.atrInfo)
            file("dir", record.files.dir)
            append("bac=${record.bacCommands?.getChallenge}|${record.bacCommands?.externalAuthenticate}\n")
            record.probes.forEach { probe ->
                probe.steps.forEach { step ->
                    append("probe=${probe.probeId}/${probe.packVersion}/${step.stepId}=")
                    append("${step.statusWord}|${size(step.responseSize)}|${step.setupStatusWord}|${step.repeatsAgree}\n")
                }
            }
            record.authenticated?.let { append("authenticated=${AuthenticatedObservations.structuralText(it)}\n") }
        }
    )

    /**
     * The fingerprint schema version 1 stored: the full hash, exactly as it was computed then (contents as written, so a
     * lower-case file hashes differently from the same file in upper case). Kept to check records written under version 1.
     */
    fun legacyV1Of(record: CaptureRecord): String = hash(
        buildString {
            append("fingerprint/1\n")
            append("pack=${record.probePack.id}/${record.probePack.version}\n")
            if (record.additionalProbePacks.isNotEmpty()) {
                append("additionalPacks=${record.additionalProbePacks.joinToString(",") { "${it.id}/${it.version}" }}\n")
            }
            append("application=${record.application?.selectStatusWord}|${size(record.application?.responseSize)}\n")
            legacyFile("cardAccess", record.files.cardAccess)
            legacyFile("atrInfo", record.files.atrInfo)
            legacyFile("dir", record.files.dir)
            append("bac=${record.bacCommands?.getChallenge}|${record.bacCommands?.externalAuthenticate}\n")
            record.probes.forEach { probe ->
                probe.steps.forEach { step ->
                    append("probe=${probe.probeId}/${probe.packVersion}/${step.stepId}=")
                    append("${step.statusWord}|${size(step.responseSize)}|${step.setupStatusWord}|${step.repeatsAgree}\n")
                }
            }
            record.authenticated?.let { append("authenticated=${AuthenticatedObservations.structuralText(it)}\n") }
        }
    )

    /** Whether [record]'s stored fingerprint (and digest, from schema version 2) are the ones its contents give. */
    fun isConsistent(record: CaptureRecord): Boolean =
        if (record.schemaVersion < 2) record.fingerprint == legacyV1Of(record)
        else record.fingerprint == of(record) && record.observationDigest == digestOf(record)

    /** [record] with its fingerprint and digest set from its contents, for the current schema version. */
    fun seal(record: CaptureRecord): CaptureRecord =
        record.copy(fingerprint = of(record), observationDigest = digestOf(record))

    private fun hash(text: String): String = Hex.encode(Sha256.digest(text.encodeToByteArray())).take(LENGTH)

    private fun StringBuilder.chipFile(name: String, file: FileObservation?) {
        append("$name=")
        if (file != null) append("${file.selectStatusWord}|${file.contentsHex?.uppercase().orEmpty()}")
        append('\n')
    }

    private fun StringBuilder.file(name: String, file: FileObservation?) {
        append("$name=")
        if (file != null) append("${file.selectStatusWord}|${file.contentsHex?.uppercase()}|${size(file.size)}")
        append('\n')
    }

    private fun StringBuilder.legacyFile(name: String, file: FileObservation?) {
        append("$name=")
        if (file != null) append("${file.selectStatusWord}|${file.contentsHex}|${size(file.size)}")
        append('\n')
    }

    private fun size(range: SizeRange?): String = range?.let { "${it.minBytes}-${it.maxBytes}" } ?: "-"
}
