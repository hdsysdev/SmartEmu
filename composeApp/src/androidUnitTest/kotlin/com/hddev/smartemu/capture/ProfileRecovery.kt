package com.hddev.smartemu.capture

import com.hddev.smartemu.data.ProfileAspect
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo

/** A PACE suite as EF.CardAccess names it: the mapping, the cipher and the domain parameter identifier. */
data class ObservedPaceSuite(val mapping: String, val cipher: String, val parameterId: Int)

/**
 * What a profile's facts look like once recovered from an unauthenticated [CaptureRecord]: the observable ones only.
 * There is no field for Active Authentication, Chip Authentication, data groups or the signature, so a recovered
 * profile cannot claim them, whatever the chip has.
 */
data class RecoveredFacts(
    /** The suites EF.CardAccess offers, in its order; empty if the chip has no EF.CardAccess. */
    val paceSuites: List<ObservedPaceSuite>,
    /** Whether GET CHALLENGE was answered with a challenge: the chip accepts BAC. */
    val answersBacChallenge: Boolean,
    /** The answer to EXTERNAL AUTHENTICATE with no challenge, or null where the chip doesn't do BAC and so shows nothing of it. */
    val outOfSequence: Int?
)

/** Recovers a profile's observable facts from a capture, and says which of its aspects a capture can't see. */
object ProfileRecovery {

    enum class Visibility { OBSERVED, PARTLY, NOT_OBSERVABLE }

    /** What an unauthenticated capture can say about each aspect of a profile, and why not. */
    val observability: Map<ProfileAspect, Pair<Visibility, String>> = mapOf(
        ProfileAspect.ACCESS_CONTROL to (Visibility.OBSERVED to
            "EF.CardAccess says PACE is offered; a GET CHALLENGE answered with a challenge says BAC is"),
        ProfileAspect.PACE_CRYPTOGRAPHY to (Visibility.OBSERVED to
            "The suites in EF.CardAccess: mapping, cipher and domain parameters, in the chip's order"),
        ProfileAspect.ERROR_RESPONSES to (Visibility.PARTLY to
            "The out-of-sequence status word shows only on a chip that does BAC; a PACE-only chip answers the command as unknown"),
        ProfileAspect.ACTIVE_AUTHENTICATION to (Visibility.NOT_OBSERVABLE to
            "DG15 and INTERNAL AUTHENTICATE need an authenticated session"),
        ProfileAspect.EXTENDED_ACCESS_CONTROL to (Visibility.NOT_OBSERVABLE to
            "Chip Authentication keys are in DG14 and Terminal Authentication's CVCA in EF.CVCA, both behind authentication"),
        ProfileAspect.DATA_GROUPS to (Visibility.NOT_OBSERVABLE to
            "EF.COM lists the data groups, behind authentication"),
        ProfileAspect.SIGNATURE to (Visibility.NOT_OBSERVABLE to
            "EF.SOD is behind authentication"),
        ProfileAspect.MRZ to (Visibility.NOT_OBSERVABLE to
            "The MRZ conventions show in DG1, behind authentication, and the MRZ is never stored")
    )

    val unobservable: Set<ProfileAspect> =
        observability.filterValues { it.first == Visibility.NOT_OBSERVABLE }.keys

    fun recover(record: CaptureRecord): RecoveredFacts {
        val answersBac = record.bacCommands?.getChallenge == 0x9000
        return RecoveredFacts(
            paceSuites = record.files.cardAccess?.contentsHex?.let { paceSuites(Hex.decode(it)) } ?: emptyList(),
            answersBacChallenge = answersBac,
            outOfSequence = if (answersBac) record.bacCommands?.externalAuthenticate else null
        )
    }

    /** EF.CardAccess's PACEInfos in the file's order, which JMRTD's CardAccessFile, holding them in a set, loses. */
    fun paceSuites(cardAccess: ByteArray): List<ObservedPaceSuite> =
        ASN1Set.getInstance(cardAccess).map { ASN1Sequence.getInstance(it) }
            .filter { ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id.startsWith(SecurityInfo.ID_PACE) }
            .map {
                val oid = ASN1ObjectIdentifier.getInstance(it.getObjectAt(0)).id
                ObservedPaceSuite(
                    mapping = PACEInfo.toMappingType(oid).name,
                    cipher = when (PACEInfo.toCipherAlgorithm(oid)) {
                        "AES" -> "AES-${PACEInfo.toKeyLength(oid)}"
                        else -> "3DES"
                    },
                    parameterId = ASN1Integer.getInstance(it.getObjectAt(2)).intValueExact()
                )
            }
}
