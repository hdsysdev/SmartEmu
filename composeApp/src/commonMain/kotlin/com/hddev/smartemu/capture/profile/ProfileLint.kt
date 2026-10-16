package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.RedactionLint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The redaction lint for what is committed in `profiles/`: merged profiles, which hold counts and random capture ids
 * and nothing a holder, a device or a chip could be recognised by. [RedactionLint] is for a single capture record; this
 * is its counterpart for a merged profile, so that CI can run a lint over everything in the repository that came from
 * captures. A [RedactionLint.Violation] never repeats the offending text.
 *
 * A file fails if it doesn't decode strictly as a [MeasuredProfile] (an unknown field, or a per-capture record, which has
 * fields a profile doesn't), has a string that looks like an MRZ, holds a long hex or base64 string anywhere but the few
 * fields that carry public file contents, or names a capture by anything but a random UUID.
 */
object ProfileLint {
    private val strict = Json { ignoreUnknownKeys = false }
    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val identifier = Regex("[A-Za-z0-9._-]{1,64}")
    private val generation = Regex("[0-9A-Fa-f]{1,32}")
    private val hexBlob = Regex("[0-9A-Fa-f]{24,}")
    private val base64Blob = Regex("[A-Za-z0-9+/]{32,}={0,2}")

    /** Fields that carry public file contents (EF.CardAccess, EF.ATR/INFO, EF.DIR) as hex, with the most bytes each may hold. */
    private val contentFields = mapOf(
        "observations.cardAccessHex" to 2048,
        "observations.atrInfoHex" to 512,
        "observations.dirHex" to 512
    )

    /** Fields of a disagreement or of the link that hold a value as text, which may be hex of a public file. */
    private val valueFields = setOf("disagreements[].values[].value", "observations.link.historicalBytes")

    fun check(json: String): List<RedactionLint.Violation> {
        val root = try {
            strict.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return listOf(violation(RedactionLint.Rule.MALFORMED_JSON, "", "Not JSON"))
        } catch (e: IllegalArgumentException) {
            return listOf(violation(RedactionLint.Rule.MALFORMED_JSON, "", "Not JSON"))
        }
        if (root !is JsonObject) return listOf(violation(RedactionLint.Rule.MALFORMED_JSON, "", "Not a JSON object"))
        val out = mutableListOf<RedactionLint.Violation>()
        try {
            MeasuredProfileJson.decode(json)
        } catch (e: SerializationException) {
            // The exception's text quotes the input, so it isn't passed on
            out += violation(RedactionLint.Rule.SCHEMA_MISMATCH, "", "Not a measured profile of this schema version: unknown, missing or mistyped field, or another kind of file")
        } catch (e: IllegalArgumentException) {
            out += violation(RedactionLint.Rule.SCHEMA_MISMATCH, "", "Not a measured profile of this schema version: unknown, missing or mistyped field, or another kind of file")
        }
        walk(root, "", out)
        return out
    }

    private fun violation(rule: RedactionLint.Rule, path: String, detail: String) = RedactionLint.Violation(rule, path, detail)

    private fun walk(element: JsonElement, path: String, out: MutableList<RedactionLint.Violation>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> walk(value, if (path.isEmpty()) key else "$path.$key", out) }
            is JsonArray -> element.forEach { walk(it, "$path[]", out) }
            is JsonNull -> Unit
            is JsonPrimitive -> if (element.isString) checkString(element.content, path.replace(Regex("\\[\\d+]"), "[]"), out)
        }
    }

    private fun checkString(value: String, path: String, out: MutableList<RedactionLint.Violation>) {
        when {
            path in contentFields -> {
                checkHex(value, path, contentFields.getValue(path), out)
                // The same structural allowlist a capture's files pass (see com.hddev.smartemu.capture.PublicFileRules)
                if (Hex.looksLikeHex(value) || value.isEmpty()) {
                    val file = when (path) { "observations.cardAccessHex" -> "cardAccess"; "observations.atrInfoHex" -> "atrInfo"; else -> "dir" }
                    com.hddev.smartemu.capture.PublicFileRules.problems(file, Hex.toBytes(value)).forEach { out += violation(RedactionLint.Rule.BAD_FIELD, path, it) }
                }
            }
            path == "generation" -> if (!generation.matches(value)) out += violation(RedactionLint.Rule.BAD_FIELD, path, "Not a short hex digest")
            path == "id" || path == "chipProfileId" -> if (!identifier.matches(value)) out += violation(RedactionLint.Rule.BAD_FIELD, path, "Not a profile id")
            path.endsWith("captureIds[]") || path.endsWith("heldOutCaptureId") ->
                if (!uuidV4.matches(value)) out += violation(RedactionLint.Rule.BAD_FIELD, path, "Not a random (version 4) UUID")
            path in valueFields -> if (Hex.looksLikeHex(value)) checkHex(value, path, 2048, out) else checkText(value, path, out)
            else -> checkText(value, path, out)
        }
    }

    private fun checkHex(value: String, path: String, maxBytes: Int, out: MutableList<RedactionLint.Violation>) {
        if (!Hex.looksLikeHex(value)) {
            out += violation(RedactionLint.Rule.BAD_FIELD, path, "Not hex")
        } else if (value.length / 2 > maxBytes) {
            out += violation(RedactionLint.Rule.BAD_FIELD, path, "More than $maxBytes bytes")
        } else if (RedactionLint.looksLikeMrz(Hex.toText(value))) {
            out += violation(RedactionLint.Rule.MRZ_PATTERN, path, "MRZ-like text in the file contents")
        }
    }

    private fun checkText(value: String, path: String, out: MutableList<RedactionLint.Violation>) {
        if (RedactionLint.looksLikeMrz(value)) out += violation(RedactionLint.Rule.MRZ_PATTERN, path, "MRZ-like text")
        if (value.split(' ', '\t', '\n', '\r', ';', ',', '|').any { hexBlob.matches(it) || base64Blob.matches(it) }) {
            out += violation(RedactionLint.Rule.HIGH_ENTROPY_BLOB, path, "A long hex or base64 string where none is allowed")
        }
    }

    private object Hex {
        fun looksLikeHex(text: String): Boolean = text.isNotEmpty() && text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

        fun toBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun toText(hex: String): String = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }.decodeToString()
    }
}
