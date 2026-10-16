package com.hddev.smartemu.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.ln

/**
 * The second line of defence against personal data in a capture, run where captures come in: it reads the JSON as
 * text, whatever the app that wrote it believed it was saving. It refuses a record with the wrong schema version, a
 * field outside the schema, an MRZ-like line, or a long hex or base64 string anywhere but the few fields that hold
 * file contents.
 *
 * A [Violation] never repeats the offending text: the log of a rejected submission must not become a copy of it.
 */
object RedactionLint {

    enum class Rule {
        MALFORMED_JSON,
        SCHEMA_VERSION,
        SCHEMA_MISMATCH,
        MRZ_PATTERN,
        HIGH_ENTROPY_BLOB,
        BAD_FIELD,

        /** The fingerprint is empty, malformed, or not the one the record's contents give. */
        FINGERPRINT_MISMATCH,

        /** `redactions` isn't the manifest for the record's schema version and content. */
        REDACTIONS_MISMATCH
    }

    data class Violation(val rule: Rule, val path: String, val detail: String)

    /** The fields that hold file contents as hex, with the most bytes each may hold. */
    private val contentFields = mapOf(
        "files.cardAccess.contentsHex" to 2048,
        "files.atrInfo.contentsHex" to 512,
        "files.dir.contentsHex" to 512
    )

    private val identifierFields = setOf("probePack.id", "additionalProbePacks[].id", "probes[].probeId", "probes[].steps[].stepId")
    private val identifier = Regex("[a-z0-9-]{1,48}")
    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val month = Regex("\\d{4}-(0[1-9]|1[0-2])")
    private val mrzCharacters = Regex("[A-Z0-9<]+")
    private val mrzFragment = Regex("[A-Z0-9<]{10,}")
    private val hexBlob = Regex("[0-9A-Fa-f]{24,}")
    private val base64Blob = Regex("[A-Za-z0-9+/]{24,}={0,2}")
    private val base64UrlBlob = Regex("[A-Za-z0-9_-]{32,}={0,2}")
    private const val MIN_ENTROPY = 3.0
    private const val MIN_URL_ENTROPY = 3.5

    private val strict = Json { ignoreUnknownKeys = false }

    /** Every problem with [json] as a capture submission; empty if it may be ingested. */
    fun check(json: String): List<Violation> {
        val root = try {
            strict.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return listOf(Violation(Rule.MALFORMED_JSON, "", "Not JSON"))
        } catch (e: IllegalArgumentException) {
            return listOf(Violation(Rule.MALFORMED_JSON, "", "Not JSON"))
        }
        if (root !is JsonObject) return listOf(Violation(Rule.MALFORMED_JSON, "", "Not a JSON object"))
        val version = (root.get("schemaVersion") as? JsonPrimitive)?.intOrNull
        if (version == null || version !in MIN_CAPTURE_SCHEMA_VERSION..CAPTURE_SCHEMA_VERSION) {
            return listOf(
                Violation(
                    Rule.SCHEMA_VERSION, "schemaVersion",
                    "Expected schema version $MIN_CAPTURE_SCHEMA_VERSION to $CAPTURE_SCHEMA_VERSION, got ${version ?: "none"}"
                )
            )
        }
        val violations = mutableListOf<Violation>()
        try {
            // As written: a version 1 record is checked as version 1 (its own fingerprint, its own manifest), not upgraded first
            val record = CaptureJson.decodeAsWritten(json)
            // The authenticated part is structure only; each of its fields has a tight shape (see AuthenticatedLint)
            violations += AuthenticatedLint.check(record)
            violations += RecordLint.check(record)
        } catch (e: SerializationException) {
            // The exception's text quotes the input, so it isn't passed on
            violations += Violation(Rule.SCHEMA_MISMATCH, "", "Does not match its schema version: unknown, missing or mistyped field")
        } catch (e: IllegalArgumentException) {
            violations += Violation(Rule.SCHEMA_MISMATCH, "", "Does not match its schema version: unknown, missing or mistyped field")
        }
        walk(root, "", violations)
        return violations
    }

    /**
     * Whether [text] looks like MRZ lines: a run of ten or more MRZ characters with a filler `<` in it, or consecutive
     * lines of the exact MRZ length (3 of 30, 2 of 36, 2 of 44) made of MRZ characters.
     */
    fun looksLikeMrz(text: String): Boolean {
        if (mrzFragment.findAll(text).any { '<' in it.value }) return true
        val lines = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        return listOf(30 to 3, 36 to 2, 44 to 2).any { (length, count) ->
            lines.windowed(count).any { window -> window.all { it.length == length && mrzCharacters.matches(it) } }
        }
    }

    private fun walk(element: JsonElement, path: String, out: MutableList<Violation>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> walk(value, if (path.isEmpty()) key else "$path.$key", out) }
            is JsonArray -> element.forEach { walk(it, "$path[]", out) }
            is JsonNull -> Unit
            is JsonPrimitive -> if (element.isString) checkString(element.content, path.replace(Regex("\\[\\d+]"), "[]"), out)
        }
    }

    private fun checkString(value: String, path: String, out: MutableList<Violation>) {
        when {
            path in contentFields -> checkContents(value, path, contentFields.getValue(path), out)
            path == "link.historicalBytes" -> {
                if (!Hex.isHex(value) || value.length > PublicFileRules.MAX_HISTORICAL_BYTES * 2) {
                    out += Violation(Rule.BAD_FIELD, path, "Not hex of at most ${PublicFileRules.MAX_HISTORICAL_BYTES} bytes")
                } else if (PublicFileRules.reduceHistorical(Hex.decode(value))?.contentEquals(Hex.decode(value)) != true) {
                    out += Violation(Rule.BAD_FIELD, path, "Not compact-TLV historical bytes without issuer or pre-issuing data")
                }
            }
            path == "captureId" -> if (!uuidV4.matches(value)) out += Violation(Rule.BAD_FIELD, path, "Not a random (version 4) UUID")
            path == "captureMonth" -> if (!month.matches(value)) out += Violation(Rule.BAD_FIELD, path, "Not YYYY-MM")
            // Shape and consistency are RecordLint's; here only that the text is a short hex digest, so it can't carry anything else
            path == "fingerprint" || path == "observationDigest" -> if (value.isNotEmpty() && !(Hex.isHex(value) && value.length <= 32)) {
                out += Violation(Rule.BAD_FIELD, path, "Not a short hex digest")
            }
            path in identifierFields -> if (!identifier.matches(value)) out += Violation(Rule.BAD_FIELD, path, "Not a short lower-case identifier")
            else -> {
                if (looksLikeMrz(value)) out += Violation(Rule.MRZ_PATTERN, path, "MRZ-like text")
                if (value.split(' ', '\t', '\n', '\r').any(::isBlob)) {
                    out += Violation(Rule.HIGH_ENTROPY_BLOB, path, "A long hex or base64 string where none is allowed")
                }
            }
        }
    }

    private fun checkContents(value: String, path: String, maxBytes: Int, out: MutableList<Violation>) {
        if (!Hex.isHex(value)) {
            out += Violation(Rule.BAD_FIELD, path, "Not hex")
            return
        }
        val bytes = Hex.decode(value)
        if (bytes.size > maxBytes) {
            out += Violation(Rule.BAD_FIELD, path, "More than $maxBytes bytes")
            return
        }
        if (looksLikeMrz(bytes.decodeToString())) out += Violation(Rule.MRZ_PATTERN, path, "MRZ-like text in the file contents")
        // The structural allowlist of the file (see PublicFileRules): per-chip key infos, issuer data and unknown objects are refused
        val file = path.removePrefix("files.").substringBefore('.')
        PublicFileRules.problems(file, bytes).forEach { out += Violation(Rule.BAD_FIELD, path, it) }
    }

    private fun isBlob(token: String): Boolean {
        val isHexOrBase64 = hexBlob.matches(token) || base64Blob.matches(token)
        if (isHexOrBase64 && entropy(token) >= MIN_ENTROPY) return true
        return base64UrlBlob.matches(token) && categories(token) >= 3 && entropy(token) >= MIN_URL_ENTROPY
    }

    private fun categories(token: String): Int =
        listOf(token.any { it.isUpperCase() }, token.any { it.isLowerCase() }, token.any { it.isDigit() }, token.any { it == '-' || it == '_' })
            .count { it }

    /** Shannon entropy in bits per character. */
    private fun entropy(token: String): Double {
        val counts = token.groupingBy { it }.eachCount()
        return -counts.values.sumOf { n ->
            val p = n.toDouble() / token.length
            p * ln(p) / ln(2.0)
        }
    }
}

/** A well-formedness check of BER-TLV, enough to tell a file of data objects from arbitrary bytes. */
internal object BerTlv {
    private const val MAX_DEPTH = 8

    fun isWellFormed(bytes: ByteArray): Boolean = bytes.isNotEmpty() && parse(bytes, 0, bytes.size, 0)

    private fun parse(bytes: ByteArray, start: Int, end: Int, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        var i = start
        while (i < end) {
            val first = bytes[i++].toInt() and 0xFF
            if (first and 0x1F == 0x1F) {
                // A multi-byte tag: further bytes follow while bit 8 is set
                do {
                    if (i >= end) return false
                    val more = bytes[i++].toInt() and 0x80 != 0
                } while (more)
            }
            if (i >= end) return false
            var length = bytes[i++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                if (count == 0 || count > 3 || i + count > end) return false
                length = 0
                repeat(count) { length = (length shl 8) or (bytes[i++].toInt() and 0xFF) }
            }
            if (length > end - i) return false
            if (first and 0x20 != 0 && !parse(bytes, i, i + length, depth + 1)) return false
            i += length
        }
        return true
    }
}
