package com.hddev.smartemu.capture

import java.util.Base64
import kotlin.test.fail

/**
 * Personal data with known bytes, for the canary test: a capture of a chip that holds it must not contain it in any
 * encoding. Phase 2's authenticated path reuses [assertNoPii] on its own output.
 */
class CanaryPii(val items: Map<String, ByteArray>) {
    init {
        items.forEach { (name, bytes) -> require(bytes.size >= MIN_BYTES) { "$name is too short to be a safe canary" } }
    }

    companion object {
        /** Shorter patterns would turn up in a JSON by chance. */
        const val MIN_BYTES = 6
    }
}

/**
 * Fails if [json] holds any of the [pii] items as hex (either case), as ASCII text (in any case), or as base64 (standard
 * or URL-safe, whichever way the bytes line up in the encoded text).
 */
fun assertNoPii(json: String, pii: CanaryPii) {
    pii.items.forEach { (name, bytes) ->
        needles(bytes).forEach { (encoding, needle) ->
            if (json.contains(needle, ignoreCase = encoding == "ascii" || encoding == "hex")) {
                fail("The capture holds $name as $encoding")
            }
        }
    }
}

/** The ways [bytes] could show up in a JSON text, as (encoding, text). */
internal fun needles(bytes: ByteArray): List<Pair<String, String>> {
    val found = mutableListOf("hex" to Hex.encode(bytes))
    val ascii = String(bytes, Charsets.ISO_8859_1)
    if (ascii.all { it in ' '..'~' }) found += "ascii" to ascii
    for (padding in 0..2) {
        val padded = ByteArray(padding) + bytes
        // The first characters mix in the padding bytes, and the last one depends on bytes that follow
        val drop = (padding * 8 + 5) / 6
        val trailing = if ((padded.size * 8) % 6 != 0) 1 else 0
        for ((name, encoder) in listOf("base64" to Base64.getEncoder(), "base64url" to Base64.getUrlEncoder())) {
            val text = encoder.withoutPadding().encodeToString(padded)
            val needle = text.substring(drop, text.length - trailing)
            if (needle.length >= 8) found += name to needle
        }
    }
    return found
}
