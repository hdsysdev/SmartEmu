package com.hddev.smartemu.capture.auth

import com.hddev.smartemu.capture.AccessMethod
import com.hddev.smartemu.capture.AuthenticationResult
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.ObservedPaceSuite
import com.hddev.smartemu.capture.PaceMappingType
import com.hddev.smartemu.capture.ProfileRecovery
import com.hddev.smartemu.capture.ProfileRecovery.Visibility
import com.hddev.smartemu.data.ProfileAspect

/** What an authenticated capture says about a chip, as the facts a profile holds. */
data class AuthenticatedFacts(
    /** The PACE suites that opened the chip, in the order tried (EF.CardAccess's order). */
    val acceptedPaceSuites: List<ObservedPaceSuite>,
    val paceSuitesRefused: List<ObservedPaceSuite>,
    /** Whether BAC opened the chip with the MRZ. */
    val bacAccepted: Boolean,
    /** The data groups the chip lets be selected, in number order. */
    val dataGroupsPresent: List<Int>,
    val sodDigestAlgorithm: String?,
    val signerKeyType: String?,
    val signerKeyBits: Int?,
    val activeAuthentication: AuthenticationResult?,
    val aaKeyType: String?,
    val aaKeyBits: Int?,
    val chipAuthentication: AuthenticationResult?,
    /** The answer to reading DG3 without Terminal Authentication, if the chip has a DG3. */
    val dg3ReadStatusWord: Int?,
    val hasCvca: Boolean,
    val camVerified: Boolean
)

/**
 * Recovers a profile's facts from an authenticated capture, and says which aspects of a profile that capture can see
 * and why not the rest. The unauthenticated version is [ProfileRecovery]; this one adds what lives behind PACE or BAC.
 */
object AuthenticatedProfileRecovery {

    /** What an authenticated capture can say about each aspect of a profile, and what it still can't. */
    val observability: Map<ProfileAspect, Pair<Visibility, String>> = mapOf(
        ProfileAspect.ACCESS_CONTROL to (Visibility.OBSERVED to
            "Each PACE suite and BAC is tried with the real key, so which of them open the chip is measured"),
        ProfileAspect.PACE_CRYPTOGRAPHY to (Visibility.OBSERVED to
            "The suites in EF.CardAccess, and that each one completes; for CAM, whether the chip's key fits the mapping"),
        ProfileAspect.ERROR_RESPONSES to (Visibility.PARTLY to
            "The answer to DG3 and DG4 without Terminal Authentication, and to a refused key; not the profile's whole table"),
        ProfileAspect.ACTIVE_AUTHENTICATION to (Visibility.OBSERVED to
            "Whether INTERNAL AUTHENTICATE verifies against DG15, with its algorithm and the key's type and size; never the key"),
        ProfileAspect.EXTENDED_ACCESS_CONTROL to (Visibility.PARTLY to
            "Chip Authentication runs and its algorithm is kept; Terminal Authentication is seen only as the DG3 refusal and the CVCA's country, not its certificate chain"),
        ProfileAspect.DATA_GROUPS to (Visibility.OBSERVED to
            "EF.COM, EF.SOD and a header read of each data group give which exist and their sizes in 256-byte buckets, not exact sizes or contents"),
        ProfileAspect.SIGNATURE to (Visibility.OBSERVED to
            "Digest and signature algorithms, and the signer certificate's key type, size, curve, issuer country and validity length; not the hashes or the certificate itself"),
        ProfileAspect.MRZ to (Visibility.NOT_OBSERVABLE to
            "The MRZ conventions are in DG1, which is never read beyond its length, and the MRZ is never stored")
    )

    val unobservable: Set<ProfileAspect> = observability.filterValues { it.first == Visibility.NOT_OBSERVABLE }.keys

    fun recover(record: CaptureRecord): AuthenticatedFacts {
        val auth = checkNotNull(record.authenticated) { "The capture has no authenticated part" }
        fun suite(a: com.hddev.smartemu.capture.AccessAttempt) = ObservedPaceSuite(
            mapping = when (a.mapping) {
                PaceMappingType.GM -> "GM"
                PaceMappingType.IM -> "IM"
                PaceMappingType.CAM -> "CAM"
                null -> "?"
            },
            cipher = a.cipher ?: "?",
            parameterId = a.parameterId ?: -1
        )
        val pace = auth.access.filter { it.method == AccessMethod.PACE }
        return AuthenticatedFacts(
            acceptedPaceSuites = pace.filter { it.succeeded }.map(::suite),
            paceSuitesRefused = pace.filter { !it.succeeded }.map(::suite),
            bacAccepted = auth.access.any { it.method == AccessMethod.BAC && it.succeeded },
            dataGroupsPresent = auth.dataGroups.filter { it.selectStatusWord == 0x9000 }.map { it.number }.sorted(),
            sodDigestAlgorithm = auth.sod?.digestAlgorithm,
            signerKeyType = auth.sod?.signer?.keyType,
            signerKeyBits = auth.sod?.signer?.keyBits,
            activeAuthentication = auth.activeAuthentication,
            aaKeyType = auth.dg15?.keyType,
            aaKeyBits = auth.dg15?.keyBits,
            chipAuthentication = auth.chipAuthentication,
            dg3ReadStatusWord = auth.dataGroups.firstOrNull { it.number == 3 && it.selectStatusWord == 0x9000 }?.readStatusWord,
            hasCvca = auth.cvca != null,
            camVerified = pace.filter { it.mapping == PaceMappingType.CAM }.let { cam -> cam.isNotEmpty() && cam.all { it.camVerified == true } }
        )
    }
}
