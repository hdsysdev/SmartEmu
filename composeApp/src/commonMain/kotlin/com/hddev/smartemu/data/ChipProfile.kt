package com.hddev.smartemu.data

import com.hddev.smartemu.capture.profile.FieldKeys
import com.hddev.smartemu.capture.profile.MeasuredProfile
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
    /**
     * Mappings the real chip also offers in EF.CardAccess on the same domain parameters and cipher, after
     * [paceMapping], so a reader can choose among them as it would on the document.
     */
    val otherPaceMappings: List<PaceMapping> = emptyList(),
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
    val evidence: List<ProfileEvidence> = emptyList(),
    /**
     * What capture mode measured on real chips of this generation, merged and checked (see [MeasuredProfile]), or null
     * where no chip has been captured. The emulator replays it; with none, the chip behaves as the fields above say.
     */
    val measured: MeasuredProfile? = null
) {
    /**
     * The aspect's provenance. [Provenance.MEASURED] only where [measured] holds fields for the aspect and every one of
     * them passed the hold-out rule: MEASURED is per field, so a single unverified or disagreeing field keeps the
     * aspect at its curated provenance.
     */
    fun provenanceOf(aspect: ProfileAspect): Provenance =
        if (measured?.isMeasured(aspect) == true) Provenance.MEASURED else provenance[aspect] ?: Provenance.ASSUMED

    /** Whether the profile rests on a real chip someone else read and published ([ProfileEvidence.observedOnRealChip]). */
    val sampledFromRealChip: Boolean get() = evidence.any { it.observedOnRealChip }

    /** This profile with [measured] data attached (or removed, with null); everything else stays as it is. */
    fun withMeasured(measured: MeasuredProfile?, id: String = this.id): ChipProfile = copyWith(id = id, measured = measured)

    /**
     * A copy with some details changed; the profile isn't a data class, so a plain `copy` isn't there. Only what
     * measured replay changes is offered.
     */
    fun copyWith(
        id: String = this.id,
        accessControl: AccessControl = this.accessControl,
        errorResponses: ErrorResponses = this.errorResponses,
        measured: MeasuredProfile? = this.measured
    ): ChipProfile = ChipProfile(
        id = id, title = title, shortTitle = shortTitle, country = country, documentType = documentType,
        summary = summary, accessControl = accessControl, paceMapping = paceMapping,
        otherPaceMappings = otherPaceMappings, pace = pace, activeAuthentication = activeAuthentication,
        chipAuthentication = chipAuthentication, terminalAuthentication = terminalAuthentication,
        dataGroups = dataGroups, sod = sod, mrz = mrz, errorResponses = errorResponses, validity = validity,
        sample = sample, provenance = provenance, sources = sources, notes = notes, evidence = evidence,
        measured = measured
    )

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
    MEASURED(
        "Measured",
        "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures."
    ),
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
    val provenance: Provenance = Provenance.REPORTED,
    /** True where the claim was read off a real document's chip by someone else and published, such as a sample table. */
    val observedOnRealChip: Boolean = false
)

/**
 * The elliptic curves ICAO 9303 part 11 standardises for PACE, with their standardised domain parameter identifiers,
 * in identifier order; [DhGroup] has the Diffie-Hellman ones. Profiles don't run PACE on the 192-bit curves (see
 * [usableForPace]), but the developer setting [AppSettings.paceCurve] can put PACE on any of them, to test how a
 * reader copes.
 */
enum class EcCurve(val displayName: String, val paceParameterId: Int, val bits: Int, val isNist: Boolean) {
    /** For Chip Authentication, as on the sampled Russian passport; PACE only with [AppSettings.paceCurve]. */
    NIST_P192("NIST P-192", 8, 192, true),
    BRAINPOOL_P192R1("brainpoolP192r1", 9, 192, false),
    NIST_P224("NIST P-224", 10, 224, true),
    BRAINPOOL_P224R1("brainpoolP224r1", 11, 224, false),
    NIST_P256("NIST P-256", 12, 256, true),
    BRAINPOOL_P256R1("brainpoolP256r1", 13, 256, false),
    BRAINPOOL_P320R1("brainpoolP320r1", 14, 320, false),
    NIST_P384("NIST P-384", 15, 384, true),
    BRAINPOOL_P384R1("brainpoolP384r1", 16, 384, false),
    BRAINPOOL_P512R1("brainpoolP512r1", 17, 512, false),
    NIST_P521("NIST P-521", 18, 521, true);

    /**
     * Whether a profile may run PACE on this curve. The 192-bit curves are too weak for the key exchange that protects
     * the whole session, so a profile uses them for Chip Authentication at most.
     */
    val usableForPace: Boolean get() = bits > 192

    /**
     * Whether PACE can use Integrated Mapping on this curve. Its point encoding takes a square root as a power, which
     * needs the prime p to be 3 mod 4; NIST P-224's prime is 1 mod 4.
     */
    val supportsIntegratedMapping: Boolean get() = this != NIST_P224

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

/**
 * The Diffie-Hellman groups ICAO 9303 part 11 standardises for PACE, from RFC 5114, with their standardised domain
 * parameter identifiers. No profile runs PACE on them, but the developer setting [AppSettings.paceDhGroup] can.
 */
enum class DhGroup(val displayName: String, val paceParameterId: Int, val bits: Int, val subgroupBits: Int) {
    MODP_1024_160("DH 1024/160", 0, 1024, 160),
    MODP_2048_224("DH 2048/224", 1, 2048, 224),
    MODP_2048_256("DH 2048/256", 2, 2048, 256);

    /** As in "1024-bit MODP group with a 160-bit subgroup". */
    val description: String get() = "$bits-bit MODP group with a $subgroupBits-bit subgroup"
}

/**
 * The domain parameters and cipher PACE runs on; the mapping is the document's own [PassportData.paceMapping]. PACE
 * runs Diffie-Hellman in [dhGroup] if there is one, and then [curve] plays no part; otherwise ECDH on [curve].
 */
data class PaceSpec(
    val curve: EcCurve = EcCurve.NIST_P256,
    val cipher: SessionCipher = SessionCipher.AES_128,
    val dhGroup: DhGroup? = null
) {
    val usesDh: Boolean get() = dhGroup != null

    /** The standardised domain parameter identifier, as in EF.CardAccess's PACEInfo and MSE:Set AT's DO'84. */
    val parameterId: Int get() = dhGroup?.paceParameterId ?: curve.paceParameterId

    /** As in "brainpoolP256r1" or "DH 2048/224". */
    val domainName: String get() = dhGroup?.displayName ?: curve.displayName
}

/** A PACE protocol the chip offers in EF.CardAccess: a mapping on the domain parameters and with the cipher of [spec]. */
data class PaceSuite(val mapping: PaceMapping, val spec: PaceSpec) {
    /** As in "PACE-IM brainpoolP256r1 AES-128". */
    val displayName: String get() = "PACE-${mapping.abbreviation} ${spec.domainName} ${spec.cipher.displayName}"

    /** What tells suites apart on the chip: the protocol identifier follows from the first three. */
    internal val key: List<Any> get() = listOf(mapping, spec.usesDh, spec.cipher, spec.parameterId)
}

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
    val outOfSequence: Int = 0x6985,
    /**
     * The status word a measured chip answered a probe step with, by "probeId/stepId" (for instance
     * "read-binary-wrong-length/le-01"), for the commands and states of the probe pack. A step with no entry is
     * answered as the emulator always has, and [outOfSequence] stays the default for the out-of-sequence EXTERNAL
     * AUTHENTICATE. Empty for a chip nobody has measured.
     */
    val byProbe: Map<String, Int> = emptyMap()
) {
    /** The status word measured for the step with this key, or null if there is none. */
    fun forProbe(key: String): Int? = byProbe[key]

    /**
     * These responses overlaid by what [measured] saw, which is the stronger claim: its status words replace the
     * ones of [byProbe], and a chip that answered GET CHALLENGE (so does BAC) gives [outOfSequence] its answer to
     * EXTERNAL AUTHENTICATE with no challenge.
     *
     * Only for fields that earned MEASURED (passed the hold-out rule): a status word the captures showed but nothing
     * confirmed is not installed, so the emulator keeps its own answer to that step. Without a MEASURED mark nothing is.
     */
    fun withMeasured(measured: MeasuredProfile?): ErrorResponses {
        if (measured == null) return this
        val observed = measured.observations
        val proven = measured.measuredFields.mapTo(HashSet()) { it.key }
        val statusWords = observed.statusWords.filterKeys { FieldKeys.stepStatus(it) in proven }
        val outOfSequence = observed.bacExternalAuthenticate
            ?.takeIf { observed.bacGetChallenge == 0x9000 && FieldKeys.BAC_EXTERNAL_AUTHENTICATE in proven } ?: outOfSequence
        return copy(outOfSequence = outOfSequence, byProbe = byProbe + statusWords)
    }
}

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
