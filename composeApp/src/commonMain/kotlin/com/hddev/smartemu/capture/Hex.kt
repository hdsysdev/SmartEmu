package com.hddev.smartemu.capture

/** Upper-case hex, the form of every byte string in a [CaptureRecord]. */
internal object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(DIGITS[(b.toInt() shr 4) and 0xF])
            append(DIGITS[b.toInt() and 0xF])
        }
    }

    fun isHex(text: String): Boolean = text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    /** Throws [IllegalArgumentException] if [text] isn't an even number of hex digits. */
    fun decode(text: String): ByteArray {
        require(isHex(text)) { "Not hex: ${text.take(16)}" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
