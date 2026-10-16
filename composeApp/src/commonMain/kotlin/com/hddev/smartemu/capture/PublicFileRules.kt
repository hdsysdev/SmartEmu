package com.hddev.smartemu.capture

import kotlin.math.ln

/** A small BER-TLV reader: enough to walk the public files of a chip, strict about lengths, with no indefinite forms. */
internal object Ber {
    private const val MAX_DEPTH = 8

    class Node(val tag: Int, val constructed: Boolean, val start: Int, val valueStart: Int, val end: Int) {
        val length: Int get() = end - valueStart

        fun value(bytes: ByteArray): ByteArray = bytes.copyOfRange(valueStart, end)

        fun raw(bytes: ByteArray): ByteArray = bytes.copyOfRange(start, end)
    }

    /** The data objects that exactly fill [from] until [to], or null if they don't (a bad length, a cut-off object). */
    fun children(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, depth: Int = 0): List<Node>? {
        if (depth > MAX_DEPTH) return null
        val out = ArrayList<Node>()
        var i = from
        while (i < to) {
            val start = i
            val first = bytes[i++].toInt() and 0xFF
            var tag = first
            if (first and 0x1F == 0x1F) {
                var count = 0
                do {
                    if (i >= to || ++count > 2) return null
                    val next = bytes[i++].toInt() and 0xFF
                    tag = (tag shl 8) or next
                } while (next and 0x80 != 0)
            }
            if (i >= to) return null
            var length = bytes[i++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                if (count == 0 || count > 3 || i + count > to) return null
                length = 0
                repeat(count) { length = (length shl 8) or (bytes[i++].toInt() and 0xFF) }
            }
            if (length > to - i) return null
            out += Node(tag, first and 0x20 != 0, start, i, i + length)
            i += length
        }
        return out
    }

    /** The dotted form of the OBJECT IDENTIFIER [node], or null if it isn't a well-formed one. */
    fun oid(bytes: ByteArray, node: Node): String? {
        if (node.tag != 0x06 || node.length == 0 || node.length > 24) return null
        val arcs = ArrayList<Long>()
        var value = 0L
        var count = 0
        for (i in node.valueStart until node.end) {
            val b = bytes[i].toInt() and 0xFF
            if (++count > 8) return null
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                arcs += value
                value = 0
                count = 0
            }
        }
        if (count != 0 || arcs.isEmpty()) return null
        val first = arcs[0]
        val head = if (first < 80) listOf(first / 40, first % 40) else listOf(2L, first - 80)
        return (head + arcs.drop(1)).joinToString(".")
    }

    /** The DER encoding of a data object of [tag] holding [value]. */
    fun encode(tag: Int, value: ByteArray): ByteArray {
        val tagBytes = when {
            tag > 0xFFFF -> byteArrayOf((tag shr 16).toByte(), (tag shr 8).toByte(), tag.toByte())
            tag > 0xFF -> byteArrayOf((tag shr 8).toByte(), tag.toByte())
            else -> byteArrayOf(tag.toByte())
        }
        val length = when {
            value.size < 0x80 -> byteArrayOf(value.size.toByte())
            value.size < 0x100 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return tagBytes + length + value
    }
}

/**
 * What a capture may hold of the three public files and of the link's historical bytes: a structural allowlist, so
 * that a per-chip or per-issuance data object can't ride along in a field that is called public. [reduce] gives the
 * bytes a builder may store (what the allowlist keeps; null if nothing parseable is left, in which case only the
 * length is kept), and [problems] is the lint's view of bytes already stored: any difference from what [reduce] would
 * keep is a problem. Neither repeats the bytes it looked at.
 *
 * - EF.CardAccess is a SET of SecurityInfo. Kept: PACEInfo, PACEDomainParameterInfo whose parameters are a reference to
 *   a standard domain, ChipAuthenticationInfo, TerminalAuthenticationInfo, EFDIRInfo and ActiveAuthenticationInfo. Dropped:
 *   ChipAuthenticationPublicKeyInfo and anything that carries a key's bytes, explicit domain parameters, and entries of an
 *   unknown protocol with more than [MAX_UNKNOWN_PAYLOAD] bytes of payload.
 * - EF.ATR/INFO is data objects. Kept: 7F66 (extended length information), 43 and 47 (card service data and capabilities) and
 *   5F52 (historical bytes, themselves reduced). Dropped: 45 and 46 (card issuer's and pre-issuing data) and every other tag.
 * - EF.DIR is application templates. Kept: 61 holding only 4F, the application identifier.
 * - Historical bytes: bounded, in compact-TLV, without the issuer's and the pre-issuing data (tags 4 and 5).
 */
internal object PublicFileRules {
    const val MAX_UNKNOWN_PAYLOAD = 32
    const val MAX_HISTORICAL_BYTES = 64
    private const val MAX_NFC_A_HISTORICAL_BYTES = 15
    private const val MAX_AID = 16

    private const val ID_BSI = "0.4.0.127.0.7.2.2"
    private const val ENTROPY_WINDOW = 32
    private const val MAX_WINDOW_ENTROPY = 4.5

    private val keyInfoPrefix = "$ID_BSI.1."
    private val terminalAuthentication = "$ID_BSI.2"
    private val chipAuthenticationPrefix = "$ID_BSI.3."
    private val pacePrefix = "$ID_BSI.4."
    private val efDirInfo = "$ID_BSI.13"
    private const val ACTIVE_AUTHENTICATION_INFO = "2.23.136.1.1.5"

    // ---- EF.CardAccess ----

    fun reduceCardAccess(bytes: ByteArray): ByteArray? {
        val top = Ber.children(bytes, 0, trimmedEnd(bytes)) ?: return null
        val set = top.singleOrNull()?.takeIf { it.constructed && (it.tag == 0x31 || it.tag == 0x30) } ?: return null
        val infos = Ber.children(bytes, set.valueStart, set.end) ?: return null
        val kept = infos.filter { keepSecurityInfo(bytes, it) }
        if (kept.isEmpty()) return null
        val body = kept.fold(ByteArray(0)) { acc, node -> acc + node.raw(bytes) }
        return Ber.encode(set.tag, body).takeUnless { highEntropy(it) }
    }

    private fun keepSecurityInfo(bytes: ByteArray, info: Ber.Node): Boolean {
        if (info.tag != 0x30) return false
        val parts = Ber.children(bytes, info.valueStart, info.end) ?: return false
        val oid = parts.firstOrNull()?.let { Ber.oid(bytes, it) } ?: return false
        val rest = parts.drop(1)
        fun integers(max: Int, from: List<Ber.Node> = rest) = from.all { it.tag == 0x02 && !it.constructed && it.length in 1..max }
        return when {
            oid.startsWith(keyInfoPrefix) -> false
            oid.startsWith(pacePrefix) -> when {
                rest.isEmpty() -> false
                // PACEInfo: version and, optionally, the parameter id
                rest[0].tag == 0x02 -> rest.size <= 2 && integers(2)
                // PACEDomainParameterInfo: an AlgorithmIdentifier whose parameters are the number of a standard domain
                rest[0].tag == 0x30 -> rest.size <= 2 && integers(2, rest.drop(1)) && standardDomainReference(bytes, rest[0])
                else -> false
            }
            oid.startsWith(chipAuthenticationPrefix) -> rest.size in 1..2 && integers(4)
            oid == terminalAuthentication -> rest.isNotEmpty() && rest.size <= 2 && integers(2, rest.take(1)) &&
                rest.drop(1).all { fileReference(bytes, it) }
            oid == efDirInfo -> rest.size == 1 && rest[0].tag == 0x04 && rest[0].length <= MAX_UNKNOWN_PAYLOAD * 2 &&
                reduceDir(rest[0].value(bytes))?.contentEquals(rest[0].value(bytes)) == true
            oid == ACTIVE_AUTHENTICATION_INFO -> rest.size == 2 && rest[0].tag == 0x02 && rest[1].tag == 0x06 && integers(2, rest.take(1))
            else -> parts.drop(1).sumOf { it.end - it.start } <= MAX_UNKNOWN_PAYLOAD
        }
    }

    private fun standardDomainReference(bytes: ByteArray, algorithm: Ber.Node): Boolean {
        val parts = Ber.children(bytes, algorithm.valueStart, algorithm.end) ?: return false
        return parts.size == 2 && parts[0].tag == 0x06 && parts[1].tag == 0x02 && !parts[1].constructed && parts[1].length in 1..2
    }

    private fun fileReference(bytes: ByteArray, node: Ber.Node): Boolean {
        if (node.tag == 0x04) return node.length in 1..3
        if (node.tag != 0x30) return false
        val parts = Ber.children(bytes, node.valueStart, node.end) ?: return false
        return parts.size in 1..2 && parts.all { it.tag == 0x04 && it.length in 1..2 }
    }

    // ---- EF.ATR/INFO ----

    fun reduceAtrInfo(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val nodes = Ber.children(bytes, 0, trimmedEnd(bytes)) ?: return null
        val kept = nodes.mapNotNull { node ->
            when {
                node.tag == 0x7F66 && node.constructed -> node.raw(bytes).takeIf { extendedLengthInfo(bytes, node) }
                node.tag == 0x43 && !node.constructed && node.length <= 1 -> node.raw(bytes)
                node.tag == 0x47 && !node.constructed && node.length <= 3 -> node.raw(bytes)
                node.tag == 0x5F52 && !node.constructed ->
                    reduceHistorical(node.value(bytes), MAX_NFC_A_HISTORICAL_BYTES)?.let { Ber.encode(0x5F52, it) }
                else -> null
            }
        }
        if (kept.isEmpty()) return null
        return kept.fold(ByteArray(0)) { acc, part -> acc + part }.takeUnless { highEntropy(it) }
    }

    private fun extendedLengthInfo(bytes: ByteArray, node: Ber.Node): Boolean {
        val parts = Ber.children(bytes, node.valueStart, node.end) ?: return false
        return parts.size in 1..2 && parts.all { it.tag == 0x02 && !it.constructed && it.length in 1..4 }
    }

    // ---- EF.DIR ----

    fun reduceDir(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val nodes = Ber.children(bytes, 0, trimmedEnd(bytes)) ?: return null
        val kept = nodes.mapNotNull { node ->
            if (node.tag != 0x61 || !node.constructed) return@mapNotNull null
            val parts = Ber.children(bytes, node.valueStart, node.end) ?: return@mapNotNull null
            val aid = parts.firstOrNull { it.tag == 0x4F && !it.constructed && it.length in 1..MAX_AID } ?: return@mapNotNull null
            Ber.encode(0x61, aid.raw(bytes))
        }
        if (kept.isEmpty()) return null
        return kept.fold(ByteArray(0)) { acc, part -> acc + part }
    }

    // ---- historical bytes ----

    /**
     * [bytes] without the issuer's and the pre-issuing data of a compact-TLV historical-bytes field, or null if they are
     * not in a category this knows (a proprietary one) or aren't well-formed, or are longer than [max].
     */
    fun reduceHistorical(bytes: ByteArray, max: Int = MAX_HISTORICAL_BYTES): ByteArray? {
        if (bytes.isEmpty() || bytes.size > max) return null
        return when (bytes[0].toInt() and 0xFF) {
            // Compact-TLV; with category 00 the last three bytes are the life cycle status and a status word
            0x00 -> if (bytes.size < 4) null else compact(bytes, 1, bytes.size - 3)
                ?.let { byteArrayOf(0) + it + bytes.copyOfRange(bytes.size - 3, bytes.size) }
            0x80 -> compact(bytes, 1, bytes.size)?.let { byteArrayOf(0x80.toByte()) + it }
            0x10 -> bytes.takeIf { it.size == 2 }
            else -> null
        }
    }

    private fun compact(bytes: ByteArray, from: Int, to: Int): ByteArray? {
        var out = ByteArray(0)
        var i = from
        while (i < to) {
            val b = bytes[i].toInt() and 0xFF
            val tag = b shr 4
            val length = b and 0x0F
            if (i + 1 + length > to) return null
            // 4: card issuer's data, 5: pre-issuing data: they name an issuance
            if (tag != 4 && tag != 5) out += bytes.copyOfRange(i, i + 1 + length)
            i += 1 + length
        }
        return out
    }

    // ---- shared ----

    /** The end of [bytes] without the 00 or FF fill a transparent file may be padded with: the first end at which they parse. */
    private fun trimmedEnd(bytes: ByteArray): Int {
        var end = bytes.size
        while (true) {
            if (Ber.children(bytes, 0, end) != null) return end
            val last = if (end > 0) bytes[end - 1].toInt() else return bytes.size
            if (last != 0 && last != -1) return bytes.size
            end--
        }
    }

    /** True if some [ENTROPY_WINDOW] bytes of [bytes] have the byte statistics of random data, as a key or a hash does. */
    fun highEntropy(bytes: ByteArray): Boolean {
        if (bytes.size < ENTROPY_WINDOW) return false
        for (start in 0..bytes.size - ENTROPY_WINDOW step 4) {
            val counts = IntArray(256)
            for (i in start until start + ENTROPY_WINDOW) counts[bytes[i].toInt() and 0xFF]++
            var entropy = 0.0
            for (c in counts) if (c > 0) {
                val p = c.toDouble() / ENTROPY_WINDOW
                entropy -= p * ln(p) / ln(2.0)
            }
            if (entropy > MAX_WINDOW_ENTROPY) return true
        }
        return false
    }

    /** What is wrong with stored [bytes] of the named file, if anything; the text never quotes them. */
    fun problems(file: String, bytes: ByteArray): List<String> {
        val reduced = when (file) {
            "cardAccess" -> reduceCardAccess(bytes)
            "atrInfo" -> if (bytes.isEmpty()) bytes else reduceAtrInfo(bytes)
            "dir" -> if (bytes.isEmpty()) bytes else reduceDir(bytes)
            else -> return listOf("Not a public file")
        }
        return when {
            reduced == null -> listOf("Not parseable as the data objects this file holds")
            !reduced.contentEquals(bytes) -> listOf("Holds data objects that are not on the allowlist for this file, or padding")
            else -> emptyList()
        }
    }
}
