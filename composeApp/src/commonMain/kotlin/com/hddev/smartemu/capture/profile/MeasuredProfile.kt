package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.NotEmulatable
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.capture.ProbePackRef
import com.hddev.smartemu.capture.SizeRange
import com.hddev.smartemu.capture.TimingSummary
import com.hddev.smartemu.data.ProfileAspect
import kotlinx.serialization.Serializable

/** Bumped on any change to [MeasuredProfile]'s JSON; the migration policy is that of docs/capture-schema.md. */
const val MEASURED_PROFILE_SCHEMA_VERSION = 1

/** The fidelity contract, word for word, wherever MEASURED is explained to a person. */
object FidelityContract {
    const val STATEMENT: String =
        "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, " +
            "from n captures."

    /** The same promise for a profile's own numbers, as the badge's detail line has it. */
    fun describe(packVersion: Int, captures: Int): String =
        "Indistinguishable from the real chip at APDU level on probe pack v$packVersion, on fields marked compared, " +
            "from $captures captures."
}

/**
 * How the fidelity diff treats a field of a profile.
 */
@Serializable
enum class FieldFidelity {
    /** Matched exactly. */
    COMPARED,

    /** Random or cryptographic: matched by shape (length bucket, tags, algorithm), never by value. */
    STRUCTURAL,

    /** Can't be checked; the field says why in its reason. */
    NOT_COMPARABLE
}

/**
 * Where a MEASURED field comes from: the captures it was built from, how many, the probe packs it was compared on and,
 * if it earned MEASURED, the capture that was held out.
 */
@Serializable
data class MeasuredEvidence(
    val captureIds: List<String>,
    val sampleCount: Int,
    val probePacks: List<ProbePackRef>,
    /** The capture the profile was checked against without having been built from it (the hold-out rule). */
    val heldOutCaptureId: String? = null,
    /**
     * True if no capture the profile was built from came from the held-out capture's reader model: a different phone
     * read the chip, so a reader-side quirk can't have made the two agree.
     */
    val heldOutFromDifferentReader: Boolean = false
)

/**
 * The bookkeeping of one field of a profile: how it is diffed, and whether it earned MEASURED. The value itself is in
 * [MeasuredProfile.observations]. Provenance is per field, never per profile.
 */
@Serializable
data class MeasuredField(
    /** See [FieldKeys]. */
    val key: String,
    val fidelity: FieldFidelity,
    /** For [FieldFidelity.NOT_COMPARABLE], why. */
    val reason: String? = null,
    /** The [ProfileAspect] this field is evidence for, by name, or null. */
    val aspect: String? = null,
    /** True only once the hold-out rule passed for this field with at least two captures. */
    val measured: Boolean = false,
    /** Set exactly when [measured] is. */
    val evidence: MeasuredEvidence? = null,
    /** The captures this field's value was read from (a step the phone couldn't send is missing from some). */
    val captureIds: List<String> = emptyList(),
    /**
     * True if, with this field's step withheld from the replay table, the emulator's own generic rules gave the
     * held-out capture's value anyway: the field is compared and matches, but the replay adds nothing to it. Only set
     * on a field that earned MEASURED.
     */
    val matchesDefault: Boolean = false
)

/** One value a field had in some captures, and which captures and devices had it. */
@Serializable
data class DisagreementValue(
    val value: String,
    val captureIds: List<String>,
    /** The reader models of those captures: a split along devices points at the phone rather than the chip. */
    val readerModels: List<String> = emptyList()
)

/**
 * A field the captures didn't agree on. It is flagged, never averaged, and is left out of the profile's
 * observations, so the emulator keeps its own behaviour for it and it can't earn MEASURED.
 */
@Serializable
data class Disagreement(
    val field: String,
    val values: List<DisagreementValue>,
    val reason: String,
    /** True if the values split along reader models: a likely reader-side confounder, not a chip fact. */
    val acrossDevices: Boolean = false
)

/** Timing over repeats and captures: informational, never diffed. */
@Serializable
data class TimingAggregate(val minMs: Long, val medianMs: Long, val maxMs: Long, val samples: Int)

/** What the link layer showed. Not emulatable on Android HCE: recorded and labelled, never claimed. */
@Serializable
data class LinkSummary(
    val technology: NfcTechnology,
    val historicalBytes: String? = null,
    val uidRandom: Boolean? = null
)

/**
 * What the captures agreed on, as the emulator replays it. Reader-side confounders (the controller's maximum
 * transceive length, its extended-length support) are not here and are never merged as chip facts.
 */
@Serializable
data class MeasuredObservations(
    /** EF.CardAccess exactly as the chip wrote it, hex, in the file's order; null if no capture had one. */
    val cardAccessHex: String? = null,
    /** EF.ATR/INFO (2F01) as read, hex. */
    val atrInfoHex: String? = null,
    /** EF.DIR (2F00) as read, hex. */
    val dirHex: String? = null,
    /** The status word of SELECT of each public file, by "cardAccess", "atrInfo" and "dir". */
    val fileSelectStatusWords: Map<String, Int> = emptyMap(),
    /** The size bucket of each public file, same keys. */
    val fileSizeBuckets: Map<String, SizeRange> = emptyMap(),
    val applicationSelectStatusWord: Int? = null,
    val applicationResponseSize: SizeRange? = null,
    /** GET CHALLENGE with Le 8 before authentication. */
    val bacGetChallenge: Int? = null,
    /** EXTERNAL AUTHENTICATE with no challenge before it. */
    val bacExternalAuthenticate: Int? = null,
    /** The status word of each probe step, by "probeId/stepId". */
    val statusWords: Map<String, Int> = emptyMap(),
    /** The response size bucket of each probe step, same keys. */
    val responseSizes: Map<String, SizeRange> = emptyMap(),
    /**
     * The largest probed Le the chip answered in full in one READ BINARY (a lower bound: the exact limit lies between
     * it and the next probed Le). Null if EF.CardAccess was too short to show one.
     */
    val maxReadLength: Int? = null,
    /** Per step, informational. */
    val timing: Map<String, TimingAggregate> = emptyMap(),
    /** Informational: the link layer is not emulatable. Null if the captures disagree. */
    val link: LinkSummary? = null,
    /**
     * What authenticated captures agreed on (EF.COM, the data groups, EF.SOD's algorithms and signer, DG14, DG15, the
     * result of AA and CA), as "auth:..." keys and structural text. Behind authentication, so the unauthenticated
     * probe packs can't check it: it is informational and never compared or MEASURED. Empty without authenticated captures.
     */
    val authenticated: Map<String, String> = emptyMap()
)

/**
 * The profile of one generation: what captures of one kind of chip agreed on, with the fields they disagreed on
 * flagged, and, per field, whether it earned MEASURED. JSON in `profiles/<id>.json`; see docs/measured-profile.schema.json.
 * Built by [ProfileMerger], checked by the fidelity diff and the hold-out rule.
 *
 * Only counts and random capture ids are here: nothing that identifies a holder, a device or a single chip.
 */
@Serializable
data class MeasuredProfile(
    val schemaVersion: Int = MEASURED_PROFILE_SCHEMA_VERSION,
    /** The file name without ".json": by convention country, document and year, as the Kotlin profile ids are. */
    val id: String,
    /** The id of the curated [com.hddev.smartemu.data.ChipProfile] this generation links to, or null if none does. */
    val chipProfileId: String? = null,
    /** The generation: the [com.hddev.smartemu.capture.CaptureFingerprint] its captures share. */
    val generation: String,
    val probePack: ProbePackRef,
    val additionalProbePacks: List<ProbePackRef> = emptyList(),
    /** How many distinct captures the profile merges. */
    val sampleCount: Int,
    val captureIds: List<String>,
    /** True for a profile of one capture: held back from sharing until a steward looks at it, as the plan asks. */
    val needsReview: Boolean = false,
    /** True below two captures: nothing can be held out, so no field can earn MEASURED. */
    val needsMoreCaptures: Boolean = false,
    val observations: MeasuredObservations,
    val fields: List<MeasuredField> = emptyList(),
    val disagreements: List<Disagreement> = emptyList(),
    /** What the emulator can't copy whatever was measured; see [NotEmulatable]. */
    val notEmulatable: List<String> = NotEmulatable.properties
) {
    /** The probe packs the fields were measured on, the main one first. */
    val probePacks: List<ProbePackRef> get() = listOf(probePack) + additionalProbePacks

    fun field(key: String): MeasuredField? = fields.firstOrNull { it.key == key }

    /** The fields that earned MEASURED. */
    val measuredFields: List<MeasuredField> get() = fields.filter { it.measured }

    /** The fields the diff can check: compared or structural. */
    val checkableFields: List<MeasuredField> get() = fields.filter { it.fidelity != FieldFidelity.NOT_COMPARABLE }

    /**
     * Whether the aspect counts as MEASURED: it has checkable fields, every one of them earned MEASURED, and no
     * disagreement touches it.
     */
    fun isMeasured(aspect: ProfileAspect): Boolean {
        val own = checkableFields.filter { it.aspect == aspect.name }
        if (own.isEmpty() || !own.all { it.measured }) return false
        return disagreements.none { FieldCatalog.aspectOf(it.field) == aspect }
    }

    /**
     * This profile with [keys] marked MEASURED on [evidence]; fields that disagree or can't be checked are never marked.
     * Those of [keys] also in [matchesDefault] are marked as matching the emulator's own behaviour.
     */
    fun withMeasuredFields(keys: Set<String>, evidence: MeasuredEvidence, matchesDefault: Set<String> = emptySet()): MeasuredProfile = copy(
        fields = fields.map {
            if (it.key in keys && it.fidelity != FieldFidelity.NOT_COMPARABLE) {
                it.copy(measured = true, evidence = evidence, matchesDefault = it.key in matchesDefault)
            } else it
        }
    )

    /** The profile with every MEASURED mark removed, as a rebuilt profile starts. */
    fun withoutMeasured(): MeasuredProfile = copy(fields = fields.map { it.copy(measured = false, evidence = null, matchesDefault = false) })

    /**
     * The profile as the emulator is configured for the fidelity diff and the hold-out: every checkable field replayed,
     * as if it had earned MEASURED. This is a candidate, never saved and never shown: the emulator in the app replays
     * only what really earned it (see [com.hddev.smartemu.data.ErrorResponses.withMeasured]).
     */
    fun forEvaluation(): MeasuredProfile {
        val evidence = MeasuredEvidence(captureIds, sampleCount, probePacks)
        return copy(fields = fields.map { if (it.fidelity != FieldFidelity.NOT_COMPARABLE) it.copy(measured = true, evidence = evidence) else it })
    }

    /** The profile without what it says about the probe steps [steps] (by "probeId/stepId") and about [alsoKeys]: the replay table with them withheld. */
    fun withheld(steps: Set<String>, alsoKeys: Set<String> = emptySet()): MeasuredProfile {
        val o = observations
        return copy(
            observations = o.copy(
                statusWords = o.statusWords - steps,
                responseSizes = o.responseSizes - steps,
                bacExternalAuthenticate = if (FieldKeys.BAC_EXTERNAL_AUTHENTICATE in alsoKeys) null else o.bacExternalAuthenticate
            ),
            fields = fields.filterNot { f -> FieldKeys.stepOf(f.key)?.let { it in steps } == true || f.key in alsoKeys }
        )
    }
}

internal fun TimingSummary.toAggregate() = TimingAggregate(minMs, medianMs, maxMs, samples)
