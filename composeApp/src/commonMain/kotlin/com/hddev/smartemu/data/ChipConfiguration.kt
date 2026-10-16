package com.hddev.smartemu.data

import com.hddev.smartemu.capture.profile.MeasuredProfile

/**
 * What the emulated chip does, resolved from the document's own settings and its [ChipProfile]: the access control,
 * PACE mapping and Active Authentication switch from [PassportData], the rest from the profile.
 *
 * The cryptography is the profile's as it is, so the chip behaves like the document the profile describes. Only PACE
 * domain parameters or a cipher chosen in developer settings ([PassportData.paceCurve], [PassportData.paceDhGroup],
 * [PassportData.paceCipher]) replace the profile's, and [PassportData.extraPaceSuites] adds suites to it; [adaptations]
 * says so.
 *
 * Every suite does keep to ICAO 9303 part 11: Chip Authentication Mapping runs with ECDH and AES only, and Integrated
 * Mapping on a curve its point encoding works on, so a suite that breaks those rules becomes the nearest one that
 * doesn't, and [adaptations] says so.
 */
data class ChipConfiguration(
    val profile: ChipProfile,
    val accessControl: AccessControl,
    val paceMapping: PaceMapping,
    /** The mappings of the document's own suites, [paceMapping] first. */
    val paceMappings: List<PaceMapping>,
    val pace: PaceSpec,
    /**
     * The PACE suites EF.CardAccess offers, in order: the document's own ([paceMapping] on [pace], then any
     * [ChipProfile.otherPaceMappings] on the same parameters) and any extra ones from developer settings. Empty
     * without PACE.
     */
    val paceSuites: List<PaceSuite>,
    val activeAuthentication: KeySpec?,
    val chipAuthentication: ChipAuthenticationSpec?,
    val terminalAuthentication: Boolean,
    /** The data groups on the chip, in order. */
    val dataGroups: List<Int>,
    val sod: SodSpec,
    val errorResponses: ErrorResponses,
    /** How the chip differs from the profile, and why. */
    val adaptations: List<String>,
    /**
     * What capture mode measured on real chips of this profile's generation, for the emulator to replay: EF.CardAccess,
     * EF.ATR/INFO and EF.DIR as they were, and the status words in [errorResponses]. Null for a chip nobody measured,
     * which behaves exactly as before.
     */
    val measured: MeasuredProfile? = null
) {
    /** The largest READ BINARY answer measured, or null to use the emulator's own limit. */
    val maxReadLength: Int? get() = measured?.observations?.maxReadLength

    val supportsPace: Boolean get() = accessControl.supportsPace

    /** A short summary for the event log, as in "PACE-GM brainpoolP256r1 AES-128, CA, TA". */
    val summary: String
        get() = buildList {
            paceSuites.forEach { add(it.displayName) }
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
    val mapping = paceMapping
    // A profile without PACE has no PACE suite of its own: the default stands in once the document is switched to it
    if (accessControl.supportsPace && !profile.accessControl.supportsPace) {
        adaptations += "The profile's chip has no PACE, so PACE runs on the emulator's stand-in suite " +
            "(${pace.curve.displayName}, ${pace.cipher.displayName}), not one sampled from this document."
    }
    if (accessControl.supportsPace) {
        val dhGroup = paceDhGroup
        if (dhGroup != null) {
            adaptations += "PACE runs Diffie-Hellman in ${dhGroup.displayName} (${dhGroup.description}) rather than " +
                "ECDH on the profile's ${pace.curve.displayName}, as chosen in developer settings."
            pace = pace.copy(dhGroup = dhGroup)
        } else {
            paceCurve?.takeIf { it != pace.curve }?.let { curve ->
                adaptations += "PACE runs on ${curve.displayName} rather than the profile's ${pace.curve.displayName}, " +
                    "as chosen in developer settings."
                pace = pace.copy(curve = curve)
            }
        }
        paceCipher?.takeIf { it != pace.cipher }?.let { cipher ->
            adaptations += "PACE uses ${cipher.displayName} rather than the profile's ${pace.cipher.displayName}, " +
                "as chosen in developer settings."
            pace = pace.copy(cipher = cipher)
        }
    }
    // Every mapping the real chip offers, unless the document's mapping was changed from the profile's
    val mappings = if (mapping == profile.paceMapping) listOf(mapping) + profile.otherPaceMappings else listOf(mapping)
    val ownSuites = mappings.map { PaceSuite(it, pace).conforming(adaptations) }
    val ownSuite = ownSuites.first()
    val paceSuites = if (accessControl.supportsPace) {
        val extras = extraPaceSuites.map { it.conforming(adaptations, extra = true) }
        (if (extraPaceSuitesFirst) extras + ownSuites else ownSuites + extras).distinctBy { it.key }
    } else {
        emptyList()
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
        paceMapping = ownSuite.mapping,
        paceMappings = ownSuites.map { it.mapping }.distinct(),
        pace = ownSuite.spec,
        paceSuites = paceSuites,
        activeAuthentication = activeAuthenticationKey,
        chipAuthentication = profile.chipAuthentication,
        terminalAuthentication = profile.terminalAuthentication,
        dataGroups = dataGroups,
        sod = profile.sod,
        errorResponses = profile.errorResponses.withMeasured(profile.measured),
        adaptations = adaptations.distinct(),
        measured = profile.measured
    )
}

/**
 * This suite as ICAO 9303 part 11 allows it, noting any change in [adaptations]: Chip Authentication Mapping is
 * defined for ECDH with AES only, and Integrated Mapping's point encoding needs a curve whose prime is 3 mod 4.
 */
private fun PaceSuite.conforming(adaptations: MutableList<String>, extra: Boolean = false): PaceSuite {
    val which = if (extra) "The extra suite $displayName" else "PACE"
    var mapping = mapping
    var spec = spec
    if (mapping == PaceMapping.CHIP_AUTHENTICATION && spec.usesDh) {
        adaptations += "$which uses Generic Mapping rather than Chip Authentication Mapping, as ICAO 9303 defines " +
            "CAM for ECDH only."
        mapping = PaceMapping.GENERIC
    }
    if (mapping == PaceMapping.CHIP_AUTHENTICATION && !spec.cipher.isAes) {
        adaptations += if (extra) {
            "$which uses AES-128 rather than 3DES, as ICAO 9303 defines CAM with AES only."
        } else {
            "PACE-CAM uses AES-128 rather than 3DES, as ICAO 9303 defines CAM with AES only."
        }
        spec = spec.copy(cipher = SessionCipher.AES_128)
    }
    if (mapping == PaceMapping.INTEGRATED && !spec.usesDh && !spec.curve.supportsIntegratedMapping) {
        adaptations += "$which uses Generic Mapping rather than Integrated Mapping on ${spec.curve.displayName}: the " +
            "mapping's point encoding needs a curve whose prime is 3 mod 4."
        mapping = PaceMapping.GENERIC
    }
    return PaceSuite(mapping, spec)
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
