package com.hddev.smartemu.data

/**
 * How the chip maps the PACE nonce to ephemeral domain parameters, ICAO 9303 part 11 section 4.4.3.3.
 * EF.CardAccess advertises the chosen mapping, and the chip accepts only that one. There is no Integrated Mapping:
 * the r2w nfc-library reader always maps the nonce generically.
 */
enum class PaceMapping(
    val displayName: String,
    val abbreviation: String,
    val description: String
) {
    /** Generic Mapping: a Diffie-Hellman key exchange on the static domain parameters. */
    GENERIC("Generic Mapping", "GM", "A Diffie-Hellman exchange on the static curve; the most widely supported"),

    /** Chip Authentication Mapping: Generic Mapping that also authenticates the chip, with a key in EF.CardSecurity. */
    CHIP_AUTHENTICATION(
        "Chip Authentication Mapping",
        "CAM",
        "Generic Mapping that also proves the chip is genuine, with a key published in EF.CardSecurity"
    )
}
