package com.hddev.smartemu.capture

/**
 * The structural rules of a decoded [CaptureRecord] that [RedactionLint] runs after the strict decode: what the schema
 * says in prose and JSON Schema can't express. A fingerprint that is empty or isn't the one the contents give, a
 * `redactions` list that isn't the manifest, a size that isn't a bucket, a number outside the range a phone or a chip
 * can produce, a device name that isn't a device name. Like every [RedactionLint.Violation] these never repeat the text
 * they found.
 *
 * The lint can't tell whether a probe pack or probe id exists: that is checked against the registry where one is
 * available (capture intake, the tests), and here only the identifier shape is.
 */
internal object RecordLint {
    private val deviceName = Regex("[A-Za-z0-9 ._+\\-/()]{1,64}")
    private val fingerprint = Regex("[0-9A-F]{16}")
    private val sixDigits = Regex("[0-9]{6}")
    private val documentNumberLike = Regex("[A-Z0-9]{9}")

    const val MAX_TIMING_MS = 600_000L
    const val MAX_SAMPLES = 50
    private const val MAX_TRANSCEIVE = 65535

    fun check(record: CaptureRecord): List<RedactionLint.Violation> {
        val out = mutableListOf<RedactionLint.Violation>()
        fun bad(path: String, detail: String, rule: RedactionLint.Rule = RedactionLint.Rule.BAD_FIELD) {
            out += RedactionLint.Violation(rule, path, detail)
        }

        // fingerprint and digest
        when {
            record.fingerprint.isEmpty() -> bad("fingerprint", "Empty: a record is fingerprinted before it is saved", RedactionLint.Rule.FINGERPRINT_MISMATCH)
            !fingerprint.matches(record.fingerprint) -> bad("fingerprint", "Not 16 upper-case hex digits", RedactionLint.Rule.FINGERPRINT_MISMATCH)
            !CaptureFingerprint.isConsistent(record) -> bad("fingerprint", "Not the one the contents give", RedactionLint.Rule.FINGERPRINT_MISMATCH)
        }
        if (record.schemaVersion < 2 && record.observationDigest.isNotEmpty()) bad("observationDigest", "Schema version 1 has no observation digest")

        // redactions: the manifest, exactly
        if (record.redactions != RedactionManifest.expected(record.schemaVersion, record.authenticated != null)) {
            bad("redactions", "Not the manifest for this schema version and content", RedactionLint.Rule.REDACTIONS_MISMATCH)
        }

        // the reader's names come from a phone's firmware: short, plain, and not a key someone typed in the wrong box
        deviceString("reader.model", record.reader.model, ::bad)
        deviceString("reader.androidVersion", record.reader.androidVersion, ::bad)
        record.reader.nfcChipset?.let { deviceString("reader.nfcChipset", it, ::bad) }
        range("reader.controllerMaxTransceiveLength", record.reader.controllerMaxTransceiveLength?.toLong(), 0, MAX_TRANSCEIVE.toLong(), ::bad)
        range("link.maxApduLengthObserved", record.link.maxApduLengthObserved?.toLong(), 0, MAX_TRANSCEIVE.toLong(), ::bad)
        range("link.historicalBytesLength", record.link.historicalBytesLength?.toLong(), 0, PublicFileRules.MAX_HISTORICAL_BYTES.toLong(), ::bad)

        // status words and size buckets
        listOf("cardAccess" to record.files.cardAccess, "atrInfo" to record.files.atrInfo, "dir" to record.files.dir).forEach { (name, file) ->
            file ?: return@forEach
            statusWord("files.$name.selectStatusWord", file.selectStatusWord, ::bad)
            bucket("files.$name.size", file.size, Redaction.SIZE_BUCKET, ::bad)
        }
        record.application?.let {
            statusWord("application.selectStatusWord", it.selectStatusWord, ::bad)
            bucket("application.responseSize", it.responseSize, Redaction.PROBE_BUCKET, ::bad)
        }
        record.bacCommands?.let {
            statusWord("bacCommands.getChallenge", it.getChallenge, ::bad)
            statusWord("bacCommands.externalAuthenticate", it.externalAuthenticate, ::bad)
        }
        record.probes.forEach { probe ->
            if (probe.packVersion < 1) bad("probes[].packVersion", "Not a pack version")
            probe.steps.forEach { step ->
                statusWord("probes[].steps[].statusWord", step.statusWord, ::bad)
                statusWord("probes[].steps[].setupStatusWord", step.setupStatusWord, ::bad)
                bucket("probes[].steps[].responseSize", step.responseSize, Redaction.PROBE_BUCKET, ::bad)
                step.timing?.let { t ->
                    val p = "probes[].steps[].timing"
                    range("$p.minMs", t.minMs, 0, MAX_TIMING_MS, ::bad)
                    range("$p.medianMs", t.medianMs, 0, MAX_TIMING_MS, ::bad)
                    range("$p.maxMs", t.maxMs, 0, MAX_TIMING_MS, ::bad)
                    range("$p.samples", t.samples.toLong(), 1, MAX_SAMPLES.toLong(), ::bad)
                    if (t.minMs > t.medianMs || t.medianMs > t.maxMs) bad(p, "Not ordered minimum, median, maximum")
                }
            }
        }
        return out
    }

    private fun deviceString(path: String, value: String, bad: (String, String, RedactionLint.Rule) -> Unit) {
        deviceNameProblem(value)?.let { bad(path, it, RedactionLint.Rule.BAD_FIELD) }
    }

    /** Why [value] isn't an acceptable device name, or null if it is; never quotes it. */
    fun deviceNameProblem(value: String): String? {
        if (!deviceName.matches(value)) return "Not a short plain device name (letters, digits, space and . _ + - / ( ))"
        val tokens = value.split(' ', '/', '(', ')')
        if (tokens.any { sixDigits.matches(it) }) return "A six-digit number, as a CAN is"
        // An MRZ document number is nine letters and digits, mostly digits; a model such as M2007J3SG has fewer
        if (tokens.any { documentNumberLike.matches(it) && it.count(Char::isDigit) >= 6 }) {
            return "A nine-character token that looks like a document number"
        }
        return null
    }

    private fun statusWord(path: String, value: Int?, bad: (String, String, RedactionLint.Rule) -> Unit) =
        range(path, value?.toLong(), 0, 0xFFFF, bad)

    private fun range(path: String, value: Long?, min: Long, max: Long, bad: (String, String, RedactionLint.Rule) -> Unit) {
        if (value != null && value !in min..max) bad(path, "Out of range", RedactionLint.Rule.BAD_FIELD)
    }

    /** A bucket of [size] bytes: `minBytes` a multiple of it and `maxBytes` the last byte of the bucket. */
    private fun bucket(path: String, value: SizeRange?, size: Int, bad: (String, String, RedactionLint.Rule) -> Unit) {
        if (value == null) return
        if (value.minBytes < 0 || value.minBytes % size != 0 || value.maxBytes != value.minBytes + size - 1) {
            bad(path, "Not a $size-byte bucket", RedactionLint.Rule.BAD_FIELD)
        }
    }
}
