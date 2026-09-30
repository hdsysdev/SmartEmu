package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate

/**
 * How a real kind of document's chip behaves, as far as it's known: its access control and the cryptography behind
 * it, its anti-copy and fingerprint protection, the files it holds, how it's signed, its MRZ conventions and how it
 * answers some wrong commands. The document's own settings in [PassportData] (access control, PACE mapping, Active
 * Authentication) start from the profile's and can be changed; the rest comes from the profile, resolved by
 * [PassportData.chipConfiguration].
 *
 * Each [ProfileAspect] has a [Provenance], so that nobody mistakes a stand-in for a measured fact.
 */
class ChipProfile(
    val id: String,
    /** For lists: country, document and generation, as in "Germany · passport (2017 design)". */
    val title: String,
    /** The document in a sentence, as in "a German passport". */
    val shortTitle: String,
    /** ICAO 9303 code of the issuing state, or null for a profile any state could issue. */
    val country: String?,
    /** The kind of document, or null for a profile that fits any. */
    val documentType: DocumentType?,
    val summary: String,
    val accessControl: AccessControl,
    val paceMapping: PaceMapping = PaceMapping.GENERIC,
    val pace: PaceSpec = PaceSpec(),
    /** The Active Authentication key, or null if the document has none. */
    val activeAuthentication: KeySpec? = null,
    /** EAC Chip Authentication, or null if the document has none. */
    val chipAuthentication: ChipAuthenticationSpec? = null,
    /**
     * Whether the chip holds fingerprints (DG3) that only a terminal authenticated with EAC Terminal Authentication
     * can read, and the EF.CVCA that names the terminal certificates it accepts.
     */
    val terminalAuthentication: Boolean = false,
    /** The optional data groups the chip holds, other than those that follow from the features above. */
    val dataGroups: Set<Int> = emptySet(),
    val sod: SodSpec = SodSpec(),
    val mrz: MrzRules = MrzRules(),
    val errorResponses: ErrorResponses = ErrorResponses(),
    val validity: Validity = Validity(),
    /** A specimen holder, for the ready-made document made from this profile. */
    val sample: ProfileSample? = null,
    val provenance: Map<ProfileAspect, Provenance> = emptyMap(),
    /** Where the reported facts come from. */
    val sources: List<String> = emptyList(),
    /** What the emulation leaves out or can't copy. */
    val notes: List<String> = emptyList(),
    /** Specific claims, scoped to this generation, with direct primary-source links and section/page locators. */
    val evidence: List<ProfileEvidence> = emptyList()
) {
    fun provenanceOf(aspect: ProfileAspect): Provenance = provenance[aspect] ?: Provenance.ASSUMED

    /** Whether the profile fits a document of this type. */
    fun fits(type: DocumentType): Boolean = documentType == null || documentType == type
}

/** The parts of a profile, each with its own [Provenance]. */
enum class ProfileAspect(val displayName: String) {
    ACCESS_CONTROL("Access control"),
    PACE_CRYPTOGRAPHY("PACE cryptography"),
    ACTIVE_AUTHENTICATION("Active Authentication"),
    EXTENDED_ACCESS_CONTROL("Chip and Terminal Authentication (EAC)"),
    DATA_GROUPS("Data groups"),
    SIGNATURE("Document signature (SOD)"),
    MRZ("MRZ and validity"),
    ERROR_RESPONSES("Error responses")
}

/** Where a claim about a real document comes from, strongest first. */
enum class Provenance(val displayName: String, val description: String) {
    SPECIFICATION("Specification", "Required by a standard or regulation that applies to this document"),
    REPORTED("Reported", "Described in public sources, but not checked against a real chip"),
    ASSUMED("ASSUMED", "Emulator default, including disabled features: not established for this generation")
}

/**
 * A narrower claim than an aspect badge. For example BAC support can be reported while the absence of PACE is
 * unknown, or EAC support can be specified while its curve is assumed. A null [aspect] describes the generation.
 * An aspect containing any unverified choice keeps its ASSUMED badge even when some of its claims have evidence.
 */
data class ProfileEvidence(
    val claim: String,
    val sourceTitle: String,
    val url: String,
    val locator: String,
    val aspect: ProfileAspect? = null,
    val provenance: Provenance = Provenance.REPORTED
)

/**
 * The elliptic curves ICAO 9303 part 11 standardises for PACE, with their standardised domain parameter identifiers.
 */
enum class EcCurve(val displayName: String, val paceParameterId: Int, val bits: Int, val isNist: Boolean) {
    NIST_P256("NIST P-256", 12, 256, true),
    NIST_P384("NIST P-384", 15, 384, true),
    NIST_P521("NIST P-521", 18, 521, true),
    BRAINPOOL_P256R1("brainpoolP256r1", 13, 256, false),
    BRAINPOOL_P320R1("brainpoolP320r1", 14, 320, false),
    BRAINPOOL_P384R1("brainpoolP384r1", 16, 384, false),
    BRAINPOOL_P512R1("brainpoolP512r1", 17, 512, false);

    /** The NIST curve at least as strong, which stands in for a Brainpool curve when cryptography is adapted. */
    val nistEquivalent: EcCurve
        get() = when {
            isNist -> this
            bits <= 256 -> NIST_P256
            bits <= 384 -> NIST_P384
            else -> NIST_P521
        }

    /** Bytes in a coordinate, and in each half of a plain ECDSA signature. */
    val fieldSize: Int get() = (bits + 7) / 8

    /** The SHA-2 digest that matches the curve's strength, for ECDSA signatures. */
    val digestAlgorithm: String
        get() = when {
            bits <= 256 -> "SHA-256"
            bits <= 384 -> "SHA-384"
            else -> "SHA-512"
        }
}

/** The cipher secure messaging runs with after PACE or Chip Authentication. */
enum class SessionCipher(val displayName: String, val keyLength: Int) {
    TDES("3DES", 128),
    AES_128("AES-128", 128),
    AES_192("AES-192", 192),
    AES_256("AES-256", 256);

    val isAes: Boolean get() = this != TDES
}

/** A key pair's type, for Active Authentication and for the Document Signer. */
sealed interface KeySpec {
    val displayName: String

    data class Ec(val curve: EcCurve) : KeySpec {
        override val displayName: String get() = "ECDSA ${curve.displayName}"
    }

    data class Rsa(val bits: Int) : KeySpec {
        override val displayName: String get() = "RSA-$bits"
    }
}

/** The curve and cipher PACE runs on; the mapping is the document's own [PassportData.paceMapping]. */
data class PaceSpec(
    val curve: EcCurve = EcCurve.NIST_P256,
    val cipher: SessionCipher = SessionCipher.AES_128
)

/**
 * EAC Chip Authentication version 1 (ICAO 9303 part 11 section 6.2, BSI TR-03110): an ECDH key pair in DG14, and
 * the cipher secure messaging restarts with once the reader has agreed a key with it.
 */
data class ChipAuthenticationSpec(
    val curve: EcCurve,
    val cipher: SessionCipher
)

/**
 * How EF.SOD is signed: its digest algorithm and the Document Signer's key. [passportSigner] and [cardSigner] name
 * the Document Signers in the repository's test-pki directory that stand in for the issuer's.
 */
data class SodSpec(
    val digestAlgorithm: String = "SHA-256",
    val signerKey: KeySpec = KeySpec.Ec(EcCurve.NIST_P256),
    val passportSigner: String = "smartemu-test-ds",
    val cardSigner: String = "smartemu-test-ds-id",
    /** The CSCA that issued them, for display. */
    val csca: String = "SmartEmu Test CSCA"
)

/**
 * The issuer's MRZ conventions. [documentCode] replaces the ICAO default for the document type, as the Dutch ID
 * card's "I" does "ID". With [personalNumberInMrz], the personal number goes in the optional data, as some states do.
 */
data class MrzRules(
    val documentCode: String? = null,
    val personalNumberInMrz: Boolean = false,
    /** How the issuer forms document numbers, for display, or null if nothing is known. */
    val documentNumberFormat: String? = null
)

/**
 * The status words the chip answers some commands it doesn't expect with, where issuers' chips differ. The ISO
 * 7816-4 defaults stand for a chip nobody has measured.
 */
data class ErrorResponses(
    /** EXTERNAL AUTHENTICATE with no GET CHALLENGE before it. */
    val outOfSequence: Int = 0x6985
)

/** How long the issuer makes the document valid for. */
data class Validity(
    val adultYears: Int = 10,
    val childYears: Int = 5,
    /** The age from which a holder gets the adult validity. */
    val adultFromAge: Int = 18
) {
    val displayText: String get() = "$adultYears years from age $adultFromAge, $childYears years before"
}

/**
 * A specimen holder for the profile's ready-made document, which is issued a year before the day it's made for, even
 * for a generation long gone, so that readers accept it.
 */
data class ProfileSample(
    val firstName: String,
    val lastName: String,
    val gender: String,
    val dateOfBirth: LocalDate,
    val documentNumber: String,
    val personalNumber: String = "",
    val placeOfBirth: String = "",
    val issuingAuthority: String = ""
)
