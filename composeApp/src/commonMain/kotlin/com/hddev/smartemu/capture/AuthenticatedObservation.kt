package com.hddev.smartemu.capture

import kotlinx.serialization.Serializable

/** What kind of credentials a capture used; the values never reach a record, only this. */
@Serializable
enum class CredentialKind { MRZ, CAN }

/** How the reader got into the chip. */
@Serializable
enum class AccessMethod { PACE, BAC }

/** The mapping of a PACE suite, from its OID: generic, integrated or chip authentication. */
@Serializable
enum class PaceMappingType { GM, IM, CAM }

/** Why a run stopped trying to open the chip, when it did. */
@Serializable
enum class AccessStop {
    /**
     * A key was refused (6300, 63Cx), or an attempt failed in any way (6982, 6988, 6A80, no answer) after authentication
     * began: the same key isn't tried again, in another suite or in BAC.
     */
    KEY_REJECTED,

    /** 6983, 6984: the access method is blocked. */
    BLOCKED,

    /** 6985 after a failure: the chip may be counting. */
    CONDITIONS_NOT_SATISFIED,

    /** The CAN is tried once, and that attempt failed. */
    CAN_SINGLE_ATTEMPT_FAILED,

    /** The person declined to try the CAN. */
    DECLINED_BY_USER,

    /** A CAN opens a chip through PACE, and the chip offers none. */
    CAN_NEEDS_PACE
}

/** One attempt to open the chip: a PACE suite from EF.CardAccess, or BAC. */
@Serializable
data class AccessAttempt(
    val method: AccessMethod,
    /** The suite's protocol OID (public: it is in EF.CardAccess); null for BAC. */
    val oid: String? = null,
    val mapping: PaceMappingType? = null,
    /** "AES-128", "AES-192", "AES-256" or "3DES". */
    val cipher: String? = null,
    val parameterId: Int? = null,
    val succeeded: Boolean,
    /** The status word of the step that failed; null on success or where no answer came. */
    val statusWord: Int? = null,
    val statusClass: StatusClass,
    /** For a Chip Authentication Mapping suite that succeeded: whether the chip's key from EF.CardSecurity fits the mapping. */
    val camVerified: Boolean? = null
)

/** EF.COM: the versions it names and the data groups it lists. */
@Serializable
data class ComObservation(
    val ldsVersion: String? = null,
    val unicodeVersion: String? = null,
    /** The data group numbers EF.COM lists, ascending. */
    val dataGroups: List<Int> = emptyList(),
    val size: SizeRange? = null
)

/** One data group: whether the chip offers it, how it answered, and how big it is, rounded. Never its contents. */
@Serializable
data class DataGroupObservation(
    val number: Int,
    val inCom: Boolean,
    val inSod: Boolean,
    val selectStatusWord: Int? = null,
    /** The answer to the READ BINARY of the first bytes: 6982 for DG3 and DG4 without Terminal Authentication. */
    val readStatusWord: Int? = null,
    val size: SizeRange? = null
)

/** The signing certificate in EF.SOD, reduced to what the issuer's choices show and nothing about the certificate itself. */
@Serializable
data class SignerCertificateObservation(
    val signatureAlgorithm: String? = null,
    val keyType: String? = null,
    val keyBits: Int? = null,
    val curve: String? = null,
    /** The country of the certificate's issuer (the CSCA), two letters. */
    val issuerCountry: String? = null,
    /** The length of the validity period in whole months. */
    val validityMonths: Int? = null
)

/** EF.SOD without the data group hash values: the algorithms, the versions and the signer's certificate. */
@Serializable
data class SodObservation(
    val size: SizeRange? = null,
    val digestAlgorithm: String? = null,
    val signatureAlgorithm: String? = null,
    val ldsVersion: String? = null,
    val unicodeVersion: String? = null,
    /** The data group numbers EF.SOD holds a hash of; the hashes themselves are not kept. */
    val hashedDataGroups: List<Int> = emptyList(),
    val certificateCount: Int = 0,
    val signer: SignerCertificateObservation? = null
)

@Serializable
enum class SecurityInfoKind {
    PACE,
    PACE_DOMAIN_PARAMETERS,
    CHIP_AUTHENTICATION,
    CHIP_AUTHENTICATION_PUBLIC_KEY,
    TERMINAL_AUTHENTICATION,
    ACTIVE_AUTHENTICATION,
    EF_DIR,
    OTHER
}

/** One SecurityInfo as a structure: its protocol, version and parameter or key identifier, with a key's type and size but never its bytes. */
@Serializable
data class SecurityInfoObservation(
    val kind: SecurityInfoKind,
    val oid: String,
    val version: Int? = null,
    /** The PACE domain parameter identifier, or the Chip Authentication key identifier. */
    val parameterId: Int? = null,
    val keyType: String? = null,
    val keyBits: Int? = null,
    val curve: String? = null
)

/** DG14 or EF.CardSecurity: the SecurityInfos in the chip's order. */
@Serializable
data class SecurityInfosObservation(
    val size: SizeRange? = null,
    val infos: List<SecurityInfoObservation> = emptyList(),
    /** True if a PACE suite with Chip Authentication Mapping is among them. */
    val paceCamPresent: Boolean = false
)

/** DG15: the kind and size of the Active Authentication key, not the key. */
@Serializable
data class ActiveAuthenticationKeyObservation(
    val size: SizeRange? = null,
    val keyType: String? = null,
    val keyBits: Int? = null,
    val curve: String? = null
)

/** EF.CVCA: that it is there, how big it is, and the country of the certification authority it names. */
@Serializable
data class CvcaObservation(
    val size: SizeRange? = null,
    val carCountry: String? = null,
    val hasAlternateReference: Boolean = false
)

/** The outcome of Active Authentication or Chip Authentication. */
@Serializable
data class AuthenticationResult(
    val succeeded: Boolean,
    /** The algorithm in use, for instance "ECDSA-SHA256", "RSA-ISO9796-2-SHA1" or "ECDH-AES-128". */
    val algorithm: String? = null,
    /** The status word of the failing command, if one came. */
    val statusWord: Int? = null
)

/**
 * What an authenticated read showed. Everything here is structure: algorithms, sizes in buckets, status words, the
 * presence of files. There is no field for the MRZ, the CAN, any DG's contents, a data group hash, or a key's bytes.
 */
@Serializable
data class AuthenticatedObservation(
    /** The kind of credentials, never their values. */
    val credential: CredentialKind,
    /** Every attempt to open the chip, in the order made. */
    val access: List<AccessAttempt> = emptyList(),
    val accessStopped: AccessStop? = null,
    /** The index in [access] of the attempt whose session the files were read in; null if none opened. */
    val readUnder: Int? = null,
    val com: ComObservation? = null,
    val dataGroups: List<DataGroupObservation> = emptyList(),
    val sod: SodObservation? = null,
    val dg14: SecurityInfosObservation? = null,
    val dg15: ActiveAuthenticationKeyObservation? = null,
    val cardSecurity: SecurityInfosObservation? = null,
    val cvca: CvcaObservation? = null,
    /** Null if the chip offers no Active Authentication key. */
    val activeAuthentication: AuthenticationResult? = null,
    /** Null if the chip offers no Chip Authentication. */
    val chipAuthentication: AuthenticationResult? = null
)

/** Text forms of an [AuthenticatedObservation]. */
object AuthenticatedObservations {
    private val compact = kotlinx.serialization.json.Json { encodeDefaults = true }

    /** Every field, compact and in a fixed order: what the capture fingerprint hashes, so any structural difference shows. */
    fun structuralText(observation: AuthenticatedObservation): String =
        compact.encodeToString(AuthenticatedObservation.serializer(), observation)
}
