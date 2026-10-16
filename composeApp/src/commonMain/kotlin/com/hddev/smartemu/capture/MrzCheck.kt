package com.hddev.smartemu.capture

import kotlinx.datetime.LocalDate

/**
 * ICAO 9303 part 3 check digits and date shapes, for validating what a person typed before any command is sent to a
 * chip: a mistyped MRZ field is a wrong key, and a wrong key spends one of the chip's attempts.
 */
object MrzCheck {
    private val weights = intArrayOf(7, 3, 1)

    /** The value of an MRZ character: digits as they are, A to Z as 10 to 35, and the filler `<` as 0; null for any other. */
    private fun value(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        '<' -> 0
        else -> null
    }

    /** The check digit of [field] (weights 7, 3, 1 repeating, sum modulo 10), or null if it holds a character an MRZ can't. */
    fun checkDigit(field: CharSequence): Char? {
        var sum = 0
        for (i in field.indices) {
            val v = value(field[i]) ?: return null
            sum += v * weights[i % 3]
        }
        return '0' + sum % 10
    }

    /** Whether [digit] is the check digit of [field]. */
    fun isValid(field: CharSequence, digit: Char): Boolean = checkDigit(field) == digit

    /** Whether [yymmdd] is six digits that name a month from 1 to 12 and a day that month has in some year. */
    fun isDateShape(yymmdd: CharSequence): Boolean {
        if (yymmdd.length != 6 || !yymmdd.all { it in '0'..'9' }) return false
        val month = (yymmdd[2] - '0') * 10 + (yymmdd[3] - '0')
        val day = (yymmdd[4] - '0') * 10 + (yymmdd[5] - '0')
        if (month !in 1..12 || day < 1) return false
        val longest = when (month) {
            2 -> 29
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= longest
    }

    /**
     * The date [yymmdd] names, with the century chosen so that it is not after [today]: a birth date is read as the
     * latest such year. Null if it is no date (29 February of a year that has none).
     */
    fun resolveNotAfter(yymmdd: CharSequence, today: LocalDate): LocalDate? {
        if (!isDateShape(yymmdd)) return null
        val yy = (yymmdd[0] - '0') * 10 + (yymmdd[1] - '0')
        val month = (yymmdd[2] - '0') * 10 + (yymmdd[3] - '0')
        val day = (yymmdd[4] - '0') * 10 + (yymmdd[5] - '0')
        var year = today.year / 100 * 100 + yy
        repeat(3) {
            date(year, month, day)?.let { if (it <= today) return it }
            year -= 100
        }
        return null
    }

    /** The date [yymmdd] names in the 2000s, or null if it is no date. */
    fun resolveInTwoThousands(yymmdd: CharSequence): LocalDate? {
        if (!isDateShape(yymmdd)) return null
        val yy = (yymmdd[0] - '0') * 10 + (yymmdd[1] - '0')
        return date(2000 + yy, (yymmdd[2] - '0') * 10 + (yymmdd[3] - '0'), (yymmdd[4] - '0') * 10 + (yymmdd[5] - '0'))
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate(year, month, day)
        } catch (e: IllegalArgumentException) {
            null
        }
}
