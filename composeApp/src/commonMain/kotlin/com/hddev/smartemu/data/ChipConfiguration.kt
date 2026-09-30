package com.hddev.smartemu.data

/**
 * What the emulated chip does, resolved from the document's own settings and its [ChipProfile]: the access control,
 * PACE mapping and Active Authentication switch from [PassportData], the rest from the profile.
 *
 * Unless [exactCryptography], the cryptography is adapted to what the r2w nfc-library can read: it runs PACE on NIST
 * curves only, so a Brainpool PACE curve becomes the NIST curve of the same strength, and [adaptations] says so. The
 * library doesn't verify signatures, Active Authentication or Chip Authentication, so those stay as the profile has
 * them either way.
 */
data class ChipConfiguration(
    val profile: ChipProfile,
    val accessControl: AccessControl,
    val paceMapping: PaceMapping,
    val pace: PaceSpec,
    val activeAuthentication: KeySpec?,
    val chipAuthentication: ChipAuthenticationSpec?,
    val terminalAuthentication: Boolean,
    /** The data groups on the chip, in order. */
    val dataGroups: List<Int>,
    val sod: SodSpec,
    val errorResponses: ErrorResponses,
    val exactCryptography: Boolean,
    /** How the chip differs from the profile, and why. */
    val adaptations: List<String>
) {
    val supportsPace: Boolean get() = accessControl.supportsPace

    /** A short summary for the event log, as in "PACE-GM brainpoolP256r1 AES-128, CA, TA". */
    val summary: String
        get() = buildList {
            if (supportsPace) add("PACE-${paceMapping.abbreviation} ${pace.curve.displayName} ${pace.cipher.displayName}")
            if (accessControl.supportsBac) add("BAC")
            activeAuthentication?.let { add("AA ${it.displayName}") }
            chipAuthentication?.let { add("CA ${it.curve.displayName} ${it.cipher.displayName}") }
            if (terminalAuthentication) add("TA")
        }.joinToString()
}

/**
 * Resolves what the chip does from these details and their profile; see [ChipConfiguration].
 */
fun PassportData.chipConfiguration(): ChipConfiguration {
    val profile = chipProfile
    val adaptations = mutableListOf<String>()

    var pace = profile.pace
    // ICAO 9303-11 defines Chip Authentication Mapping with AES only
    if (paceMapping == PaceMapping.CHIP_AUTHENTICATION && !pace.cipher.isAes) {
        pace = pace.copy(cipher = SessionCipher.AES_128)
        adaptations += "PACE-CAM uses AES-128 rather than 3DES, as ICAO 9303 defines CAM with AES only."
    }
    if (accessControl.supportsPace && !exactCryptography && !pace.curve.isNist) {
        val nist = pace.curve.nistEquivalent
        adaptations += "PACE runs on ${nist.displayName} rather than ${pace.curve.displayName}: the r2w nfc-library " +
            "supports NIST curves only. Turn on exact cryptography in developer settings for the preset curve."
        pace = pace.copy(curve = nist)
    }

    val activeAuthenticationKey = if (activeAuthentication) {
        profile.activeAuthentication ?: KeySpec.Ec(EcCurve.NIST_P256)
    } else {
        null
    }

    val dataGroups = buildList {
        add(1)
        add(2)
        if (profile.terminalAuthentication) add(3)
        addAll(profile.dataGroups.filter { it == 11 || it == 12 }.sorted())
        // DG14 holds a signed copy of EF.CardAccess's PACEInfo, as ICAO 9303-11 asks, the Chip Authentication key,
        // and for ECDSA the Active Authentication algorithm; RSA Active Authentication needs no entry
        val needsDg14 = accessControl.supportsPace || profile.chipAuthentication != null ||
            activeAuthenticationKey is KeySpec.Ec
        if (needsDg14) add(14)
        if (activeAuthenticationKey != null) add(15)
    }

    return ChipConfiguration(
        profile = profile,
        accessControl = accessControl,
        paceMapping = paceMapping,
        pace = pace,
        activeAuthentication = activeAuthenticationKey,
        chipAuthentication = profile.chipAuthentication,
        terminalAuthentication = profile.terminalAuthentication,
        dataGroups = dataGroups,
        sod = profile.sod,
        errorResponses = profile.errorResponses,
        exactCryptography = exactCryptography,
        adaptations = adaptations
    )
}

/**
 * These details on a document of [profile]'s kind: the profile's access control, PACE mapping and Active
 * Authentication, and its document type and country where it has them. A fault that needs Active Authentication goes
 * if the profile has none. The holder's details stay.
 */
fun PassportData.withChipProfile(profile: ChipProfile): PassportData {
    val activeAuthentication = profile.activeAuthentication != null
    return copy(
        chipProfileId = profile.id,
        accessControl = profile.accessControl,
        paceMapping = profile.paceMapping,
        activeAuthentication = activeAuthentication,
        chipFault = if (!activeAuthentication && chipFault.needsActiveAuthentication) ChipFault.NONE else chipFault,
        documentType = profile.documentType ?: documentType,
        issuingCountry = profile.country ?: issuingCountry,
        nationality = profile.country ?: nationality
    )
}
