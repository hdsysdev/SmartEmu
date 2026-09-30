package com.hddev.smartemu.data

/**
 * The kind of travel document the chip belongs to, ICAO 9303 parts 4 and 5. It sets the MRZ format and the
 * document code: a passport has the two 44-character lines of TD3, and an ID card or residence permit, the size of
 * a bank card, the three 30-character lines of TD1 on its back.
 */
enum class DocumentType(
    val displayName: String,
    /** The document code the MRZ starts with, without fillers. */
    val documentCode: String,
    val mrzLineCount: Int,
    val mrzLineLength: Int,
    /** Characters the MRZ has for the surname and given names together. */
    val mrzNameLength: Int
) {
    PASSPORT("Passport", "P", mrzLineCount = 2, mrzLineLength = 44, mrzNameLength = 39),
    ID_CARD("ID card", "ID", mrzLineCount = 3, mrzLineLength = 30, mrzNameLength = 30),
    RESIDENCE_PERMIT("Residence permit", "IR", mrzLineCount = 3, mrzLineLength = 30, mrzNameLength = 30);

    /** Whether the document is card-sized, with its MRZ on the back (TD1). */
    val isCard: Boolean get() = mrzLineCount == 3

    /** The name of the MRZ format, for those who know it. */
    val formatName: String get() = if (isCard) "TD1" else "TD3"
}
