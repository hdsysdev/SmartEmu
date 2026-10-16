package com.hddev.smartemu.capture

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Which field of the credentials a [CredentialProblem] is about. */
enum class CredentialField { DOCUMENT_NUMBER, DATE_OF_BIRTH, DATE_OF_EXPIRY, CAN }

enum class CredentialProblemKind {
    EMPTY,
    WRONG_LENGTH,
    INVALID_CHARACTERS,
    CHECK_DIGIT_MISMATCH,

    /**
     * An MRZ field was given without its check digit. The digits are required: a mistyped field is a wrong key, which
     * spends one of the chip's attempts, and the check digit is what catches most typing mistakes before anything is sent.
     */
    CHECK_DIGIT_MISSING,
    NOT_A_DATE,
    IMPLAUSIBLE_DATE,
    DATES_INCONSISTENT
}

/**
 * Why credentials were refused before any command was sent. It names the field and the kind of problem and never the
 * value, so it is safe to show, log and keep; [message] is for the person typing.
 */
data class CredentialProblem(val credentialField: CredentialField, val kind: CredentialProblemKind) {
    val message: String
        get() {
            val what = when (credentialField) {
                CredentialField.DOCUMENT_NUMBER -> "The document number"
                CredentialField.DATE_OF_BIRTH -> "The date of birth"
                CredentialField.DATE_OF_EXPIRY -> "The date of expiry"
                CredentialField.CAN -> "The card access number (CAN)"
            }
            return when (kind) {
                CredentialProblemKind.EMPTY -> "$what is missing."
                CredentialProblemKind.WRONG_LENGTH -> when (credentialField) {
                    CredentialField.CAN -> "$what has 6 digits."
                    CredentialField.DOCUMENT_NUMBER -> "$what is 1 to 14 characters long."
                    else -> "$what is 6 digits: year, month and day (YYMMDD)."
                }
                CredentialProblemKind.INVALID_CHARACTERS -> when (credentialField) {
                    CredentialField.DOCUMENT_NUMBER -> "$what holds only capital letters, digits and the filler <."
                    else -> "$what holds only digits."
                }
                CredentialProblemKind.CHECK_DIGIT_MISMATCH -> "$what and its check digit don't match. Check what you typed against the document."
                CredentialProblemKind.CHECK_DIGIT_MISSING -> "The check digit after ${what.replaceFirstChar { it.lowercase() }} is missing. Type it as printed on the document."
                CredentialProblemKind.NOT_A_DATE -> "$what is not a date (YYMMDD)."
                CredentialProblemKind.IMPLAUSIBLE_DATE -> "$what is not a plausible date for a travel document."
                CredentialProblemKind.DATES_INCONSISTENT -> "The date of birth is after the date of expiry."
            }
        }
}

/**
 * What a reader needs to open a chip: the three MRZ fields (BAC, and PACE with the MRZ), or the card access number
 * (PACE with the CAN). The values are kept in character arrays that [close] overwrites, never in a `data class`, a
 * `toString()` or a record: [toString] says only which kind this is.
 *
 * [close] is the end of the use. Wrap the credentials in [use], and run the capture with them; nothing reads them after
 * that, and [isClosed] says it happened. Typing gets them into a `String` first, which can't be erased and is left
 * to the garbage collector: build them from a `CharArray` ([Mrz.takingOwnershipOf], [Can.takingOwnershipOf]) where the
 * input allows it.
 */
sealed class AccessCredentials {
    abstract val kind: CredentialKind

    var isClosed: Boolean = false
        private set

    /** Every problem with these credentials as of [today]; empty if they may be tried. No command is sent for a non-empty list. */
    abstract fun validate(today: LocalDate): List<CredentialProblem>

    /** Overwrites the values with zeros. Safe to call twice. */
    fun close() {
        if (isClosed) return
        isClosed = true
        wipe()
    }

    protected abstract fun wipe()

    protected fun checkOpen() = check(!isClosed) { "The credentials were used and erased" }

    override fun toString(): String = "AccessCredentials($kind, redacted)"

    /**
 * The MRZ key fields: document number, date of birth and date of expiry (YYMMDD), each with its check digit. The check
 * digits are required: [validate] reports [CredentialProblemKind.CHECK_DIGIT_MISSING] for a field given without one, so
 * no command is sent with a key that could not be checked first.
 */
    class Mrz private constructor(
        private val documentNumber: CharArray,
        private val dateOfBirth: CharArray,
        private val dateOfExpiry: CharArray,
        private val checkDigits: CharArray
    ) : AccessCredentials() {
        override val kind: CredentialKind get() = CredentialKind.MRZ

        internal fun documentNumber(): String {
            checkOpen()
            return String(documentNumber).uppercase()
        }

        internal fun dateOfBirth(): String {
            checkOpen()
            return String(dateOfBirth)
        }

        internal fun dateOfExpiry(): String {
            checkOpen()
            return String(dateOfExpiry)
        }

        override fun validate(today: LocalDate): List<CredentialProblem> {
            checkOpen()
            val problems = mutableListOf<CredentialProblem>()
            val number = String(documentNumber).uppercase()
            when {
                number.isEmpty() -> problems += CredentialProblem(CredentialField.DOCUMENT_NUMBER, CredentialProblemKind.EMPTY)
                number.length > MAX_DOCUMENT_NUMBER -> problems += CredentialProblem(CredentialField.DOCUMENT_NUMBER, CredentialProblemKind.WRONG_LENGTH)
                !number.all { it in '0'..'9' || it in 'A'..'Z' || it == '<' } ->
                    problems += CredentialProblem(CredentialField.DOCUMENT_NUMBER, CredentialProblemKind.INVALID_CHARACTERS)
                else -> {
                    val digit = checkDigit(0)
                    // The MRZ pads a short number with fillers to 9 characters before the check digit
                    if (digit == null) {
                        problems += CredentialProblem(CredentialField.DOCUMENT_NUMBER, CredentialProblemKind.CHECK_DIGIT_MISSING)
                    } else if (!MrzCheck.isValid(number.padEnd(9, '<'), digit)) {
                        problems += CredentialProblem(CredentialField.DOCUMENT_NUMBER, CredentialProblemKind.CHECK_DIGIT_MISMATCH)
                    }
                }
            }
            val birth = dateProblems(CredentialField.DATE_OF_BIRTH, String(dateOfBirth), 1, problems)
            val expiry = dateProblems(CredentialField.DATE_OF_EXPIRY, String(dateOfExpiry), 2, problems)
            val resolvedBirth = birth?.let { MrzCheck.resolveNotAfter(it, today) }
            if (birth != null && resolvedBirth == null) {
                problems += CredentialProblem(CredentialField.DATE_OF_BIRTH, CredentialProblemKind.IMPLAUSIBLE_DATE)
            } else if (resolvedBirth != null && resolvedBirth < today.plus(DatePeriod(years = -MAX_AGE_YEARS))) {
                problems += CredentialProblem(CredentialField.DATE_OF_BIRTH, CredentialProblemKind.IMPLAUSIBLE_DATE)
            }
            val resolvedExpiry = expiry?.let { MrzCheck.resolveInTwoThousands(it) }
            if (expiry != null && resolvedExpiry == null) {
                problems += CredentialProblem(CredentialField.DATE_OF_EXPIRY, CredentialProblemKind.NOT_A_DATE)
            } else if (resolvedExpiry != null &&
                (resolvedExpiry < today.plus(DatePeriod(years = -MAX_EXPIRED_YEARS)) || resolvedExpiry > today.plus(DatePeriod(years = MAX_VALID_YEARS)))
            ) {
                problems += CredentialProblem(CredentialField.DATE_OF_EXPIRY, CredentialProblemKind.IMPLAUSIBLE_DATE)
            }
            if (resolvedBirth != null && resolvedExpiry != null && resolvedBirth > resolvedExpiry) {
                problems += CredentialProblem(CredentialField.DATE_OF_BIRTH, CredentialProblemKind.DATES_INCONSISTENT)
            }
            return problems
        }

        /** The text of a date field if it is six digits; otherwise adds the problem (and its check digit's) and returns null. */
        private fun dateProblems(field: CredentialField, text: String, checkIndex: Int, out: MutableList<CredentialProblem>): String? {
            when {
                text.isEmpty() -> out += CredentialProblem(field, CredentialProblemKind.EMPTY)
                text.length != 6 -> out += CredentialProblem(field, CredentialProblemKind.WRONG_LENGTH)
                !text.all { it in '0'..'9' } -> out += CredentialProblem(field, CredentialProblemKind.INVALID_CHARACTERS)
                !MrzCheck.isDateShape(text) -> out += CredentialProblem(field, CredentialProblemKind.NOT_A_DATE)
                else -> {
                    val digit = checkDigit(checkIndex)
                    if (digit == null) {
                        out += CredentialProblem(field, CredentialProblemKind.CHECK_DIGIT_MISSING)
                    } else if (!MrzCheck.isValid(text, digit)) {
                        out += CredentialProblem(field, CredentialProblemKind.CHECK_DIGIT_MISMATCH)
                    }
                    return text
                }
            }
            return null
        }

        /** The check digit typed for field [index] (0 number, 1 birth, 2 expiry), or null if none was given. */
        private fun checkDigit(index: Int): Char? = checkDigits[index].takeIf { it != NO_CHECK_DIGIT }

        override fun wipe() {
            documentNumber.fill('\u0000')
            dateOfBirth.fill('\u0000')
            dateOfExpiry.fill('\u0000')
            checkDigits.fill('\u0000')
        }

        companion object {
            private const val MAX_DOCUMENT_NUMBER = 14
            private const val MAX_AGE_YEARS = 125
            private const val MAX_EXPIRED_YEARS = 30
            private const val MAX_VALID_YEARS = 20
            private const val NO_CHECK_DIGIT = '\u0001'

            /**
             * The fields as typed, copied, with the check digits the person typed from the document's MRZ line. A digit
             * left out is a [CredentialProblemKind.CHECK_DIGIT_MISSING] and a wrong one a
             * [CredentialProblemKind.CHECK_DIGIT_MISMATCH], each a [CredentialProblem] before anything is sent.
             */
            fun of(
                documentNumber: String,
                dateOfBirth: String,
                dateOfExpiry: String,
                documentNumberCheckDigit: Char? = null,
                dateOfBirthCheckDigit: Char? = null,
                dateOfExpiryCheckDigit: Char? = null
            ): Mrz = takingOwnershipOf(
                documentNumber.trim().toCharArray(), dateOfBirth.trim().toCharArray(), dateOfExpiry.trim().toCharArray(),
                documentNumberCheckDigit, dateOfBirthCheckDigit, dateOfExpiryCheckDigit
            )

            /**
             * As [of], from fields that each end in their check digit, as the MRZ line gives them ("L898902C36",
             * "7408122", "9406236"). A field of the wrong shape gets no check digit and is reported when validated.
             */
            fun fromFieldsWithCheckDigits(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): Mrz {
                fun split(field: String, bodyLength: Int?): Pair<String, Char?> {
                    val trimmed = field.trim()
                    val last = trimmed.lastOrNull()
                    return if (last != null && last in '0'..'9' && trimmed.length > 1 && (bodyLength == null || trimmed.length == bodyLength + 1)) {
                        trimmed.dropLast(1) to last
                    } else {
                        trimmed to null
                    }
                }
                val (number, numberDigit) = split(documentNumber, null)
                val (birth, birthDigit) = split(dateOfBirth, 6)
                val (expiry, expiryDigit) = split(dateOfExpiry, 6)
                return of(number, birth, expiry, numberDigit, birthDigit, expiryDigit)
            }

            /** Takes the arrays as they are, without a copy, and overwrites them on [close]; the caller must not keep using them. */
            fun takingOwnershipOf(
                documentNumber: CharArray,
                dateOfBirth: CharArray,
                dateOfExpiry: CharArray,
                documentNumberCheckDigit: Char? = null,
                dateOfBirthCheckDigit: Char? = null,
                dateOfExpiryCheckDigit: Char? = null
            ): Mrz = Mrz(
                documentNumber, dateOfBirth, dateOfExpiry,
                charArrayOf(
                    documentNumberCheckDigit ?: NO_CHECK_DIGIT, dateOfBirthCheckDigit ?: NO_CHECK_DIGIT, dateOfExpiryCheckDigit ?: NO_CHECK_DIGIT
                )
            )
        }
    }

    /** The card access number printed on an ID card, six digits. */
    class Can private constructor(private val can: CharArray) : AccessCredentials() {
        override val kind: CredentialKind get() = CredentialKind.CAN

        internal fun can(): String {
            checkOpen()
            return String(can)
        }

        override fun validate(today: LocalDate): List<CredentialProblem> {
            checkOpen()
            return when {
                can.isEmpty() -> listOf(CredentialProblem(CredentialField.CAN, CredentialProblemKind.EMPTY))
                !can.all { it in '0'..'9' } -> listOf(CredentialProblem(CredentialField.CAN, CredentialProblemKind.INVALID_CHARACTERS))
                can.size != CAN_LENGTH -> listOf(CredentialProblem(CredentialField.CAN, CredentialProblemKind.WRONG_LENGTH))
                else -> emptyList()
            }
        }

        override fun wipe() {
            can.fill('\u0000')
        }

        companion object {
            const val CAN_LENGTH = 6

            /** A copy of [can]. */
            fun of(can: String): Can = Can(can.trim().toCharArray())

            /** Takes [can] as it is, without a copy, and overwrites it on [close]. */
            fun takingOwnershipOf(can: CharArray): Can = Can(can)
        }
    }
}

/** Runs [block] with these credentials and erases them afterwards, whatever [block] does. */
inline fun <C : AccessCredentials, R> C.use(block: (C) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
