package com.hddev.smartemu.data

/**
 * A deliberate flaw in the chip, for checking that a reader rejects a document that isn't genuine. Each one breaks
 * a single check, so a reader that passes the rest and fails that one has caught it:
 * - passive authentication, the EF.SOD signature and the data group hashes it covers, for the first five;
 * - Active Authentication, the chip's proof that it holds the key in DG15, for [CLONED_CHIP].
 *
 * The chip still opens with the details on the data page, so the reader gets as far as the check.
 */
enum class ChipFault(
    val displayName: String,
    val description: String,
    /** Whether the fault only shows with Active Authentication on. */
    val needsActiveAuthentication: Boolean = false
) {
    NONE("None", "A genuine chip: every check passes"),
    ALTERED_DETAILS(
        "Altered DG1",
        "EF.SOD holds the hash of different MRZ data, as if the details were changed after issue"
    ),
    SWAPPED_PHOTO(
        "Altered DG2",
        "EF.SOD holds the hash of a different portrait, as if the photo were swapped"
    ),
    BROKEN_SIGNATURE(
        "Invalid SOD signature",
        "EF.SOD is signed with a key that doesn't match the Document Signer certificate inside it"
    ),
    EXPIRED_SIGNER(
        "Expired Document Signer",
        "EF.SOD is signed by a Document Signer whose certificate expired before today"
    ),
    UNTRUSTED_SIGNER(
        "Untrusted CSCA",
        "EF.SOD is signed by a Document Signer issued by an unknown CSCA, not the legacy SmartEmu Test CSCA"
    ),
    CLONED_CHIP(
        "Cloned chip",
        "INTERNAL AUTHENTICATE is answered with a key other than the one in DG15, as a copied chip would",
        needsActiveAuthentication = true
    )
}
