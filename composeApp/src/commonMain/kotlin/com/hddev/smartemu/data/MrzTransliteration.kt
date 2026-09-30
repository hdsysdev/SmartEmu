package com.hddev.smartemu.data

/**
 * Transliterates the Latin letters with diacritics that names commonly have into the A to Z of the MRZ, following
 * the recommended transliterations of ICAO 9303-3 section 6: Ä as AE, Ø as OE, ß as SS, É as E and so on.
 * Characters it doesn't know are left alone, for the MRZ encoding to turn into fillers.
 */
object MrzTransliteration {

    private val table: Map<Char, String> = buildMap {
        fun map(letters: String, to: String) = letters.forEach { put(it, to) }
        map("ÀÁÂÃĀĂĄ", "A")
        map("ÄÆ", "AE")
        map("Å", "AA")
        map("ÇĆĈĊČ", "C")
        map("ÐĎĐ", "D")
        map("ÈÉÊËĒĔĖĘĚ", "E")
        map("ĜĞĠĢ", "G")
        map("ĤĦ", "H")
        map("ÌÍÎÏĨĪĬĮİ", "I")
        map("Ĳ", "IJ")
        map("Ĵ", "J")
        map("Ķ", "K")
        map("ĹĻĽĿŁ", "L")
        map("ÑŃŅŇŊ", "N")
        map("ÒÓÔÕŌŎŐ", "O")
        map("ÖØŒ", "OE")
        map("ŔŖŘ", "R")
        map("ŚŜŞŠ", "S")
        map("ẞ", "SS")
        map("ŢŤŦ", "T")
        map("Þ", "TH")
        map("ÙÚÛŨŪŬŮŰŲ", "U")
        map("Ü", "UE")
        map("Ŵ", "W")
        map("ÝŶŸ", "Y")
        map("ŹŻŽ", "Z")
    }

    /** [upperCaseName] with each letter the table knows replaced by its transliteration. */
    fun transliterate(upperCaseName: String): String = buildString {
        upperCaseName.forEach { char -> append(table[char] ?: char) }
    }
}
