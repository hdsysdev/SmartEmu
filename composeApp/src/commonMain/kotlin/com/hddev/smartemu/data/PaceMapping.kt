package com.hddev.smartemu.data

/**
 * How the chip maps the PACE nonce to ephemeral domain parameters, ICAO 9303 part 11 section 4.4.3.3.
 * EF.CardAccess advertises the chosen mapping, and the chip accepts only the suites it advertises. Many readers run
 * Generic and Chip Authentication Mapping only: Integrated Mapping tests readers that support it, or a reader's
 * refusal.
 */
enum class PaceMapping(
    val displayName: String,
    val abbreviation: String,
    val description: String
) {
    /** Generic Mapping: a Diffie-Hellman key exchange on the static domain parameters. */
    GENERIC("Generic Mapping", "GM", "A Diffie-Hellman exchange on the static curve; the most widely supported"),

    /** Integrated Mapping: the nonces, s from the chip and t from the reader, map straight onto the group. */
    INTEGRATED(
        "Integrated Mapping",
        "IM",
        "A pseudo-random function of the chip's and the reader's nonces picks the generator, with no extra key exchange"
    ),

    /** Chip Authentication Mapping: Generic Mapping that also authenticates the chip, with a key in EF.CardSecurity. */
    CHIP_AUTHENTICATION(
        "Chip Authentication Mapping",
        "CAM",
        "Generic Mapping that also proves the chip is genuine, with a key published in EF.CardSecurity"
    )
}
