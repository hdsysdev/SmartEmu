package com.hddev.smartemu.capture

import kotlinx.serialization.Serializable

/**
 * A status word reduced to what an access attempt can mean, so that a record says "the key was refused" without
 * depending on the exact word, and the reader can decide whether another attempt is safe.
 */
@Serializable
enum class StatusClass {
    /** 9000. */
    OK,

    /** 6300 or 63Cx: the key was refused, and the chip may count the failure. */
    KEY_REJECTED,

    /** 6983 or 6984: the access method is blocked or its reference data invalidated. */
    BLOCKED,

    /** 6985: conditions of use not satisfied; after a failure this can mean the chip is counting. */
    CONDITIONS_NOT_SATISFIED,

    /** 6982: security status not satisfied. */
    SECURITY_STATUS,

    /** 6A82 or 6A83: no such file or record. */
    NOT_FOUND,

    /** 6A80, 6A81, 6A86, 6D00, 6E00, 6F00, 6900, 6901: the chip doesn't do what was asked. */
    NOT_SUPPORTED,

    /** 67xx or 6Cxx: a length it didn't accept. */
    WRONG_LENGTH,

    /** No status word came: the command never reached the chip, or the failure was in the reader's own cryptography. */
    NO_ANSWER,

    /** Any other. */
    OTHER;

    /**
     * Whether a failure of this class ends every further attempt, once an authentication has begun (a GENERAL
     * AUTHENTICATE or EXTERNAL AUTHENTICATE was sent). Every failure does: a refused key (6300, 63Cx), a blocked
     * status word, 6985, and also 6982, 6988, 6A80 and the rest, because after a key-dependent step the chip may be
     * counting whatever it answered, and "no answer" can be a reader-side failure after the key was already sent.
     * [OK] is not a failure.
     */
    val stopsFurtherAttempts: Boolean
        get() = this != OK

    /**
     * Whether a failure of this class, at MSE:Set AT and before any key-dependent command was sent, still lets the next
     * PACE suite be tried: only a plain "the chip doesn't do that" ([NOT_SUPPORTED]) does. Anything else at that stage
     * (a counter hint, 6985, 6982, a length complaint) ends the attempts, as after a started authentication.
     */
    val allowsNextSuiteBeforeKeyIsSent: Boolean
        get() = this == NOT_SUPPORTED

    companion object {
        fun of(statusWord: Int?): StatusClass = when {
            statusWord == null -> NO_ANSWER
            statusWord == 0x9000 -> OK
            statusWord == 0x6300 || statusWord and 0xFFF0 == 0x63C0 -> KEY_REJECTED
            statusWord == 0x6983 || statusWord == 0x6984 -> BLOCKED
            statusWord == 0x6985 -> CONDITIONS_NOT_SATISFIED
            statusWord == 0x6982 -> SECURITY_STATUS
            statusWord == 0x6A82 || statusWord == 0x6A83 -> NOT_FOUND
            statusWord in NOT_SUPPORTED_WORDS -> NOT_SUPPORTED
            statusWord shr 8 == 0x67 || statusWord shr 8 == 0x6C -> WRONG_LENGTH
            else -> OTHER
        }

        private val NOT_SUPPORTED_WORDS = setOf(0x6A80, 0x6A81, 0x6A86, 0x6D00, 0x6E00, 0x6F00, 0x6900, 0x6901)
    }
}
