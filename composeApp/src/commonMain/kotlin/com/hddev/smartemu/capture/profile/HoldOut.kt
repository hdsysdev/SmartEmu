package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.CaptureRecord

/** The outcome of checking a profile against a capture it was not built from. */
data class HoldOutAward(
    /** The fields that earned MEASURED: the profile predicted the held-out capture and so did the replay. */
    val awarded: Set<String>,
    /** The comparable fields that didn't, with what each side had. */
    val rejected: List<FieldDiff>,
    /** Why nothing was awarded, where nothing was. */
    val note: String? = null,
    /**
     * Awarded fields of probe steps that were withheld from the replay table in some fold and that the emulator's own
     * generic rules answered correctly anyway: compared and matching, but the replay adds nothing to them.
     */
    val matchesDefault: Set<String> = emptySet(),
    /** Awarded fields of withheld steps the generic rules got wrong: the replay table is what makes the emulator match them. */
    val needsReplay: Set<String> = emptySet(),
    /** How many folds of the step hold-out ran. */
    val folds: Int = 0
)

/** What the emulator answered to a probe run with some steps withheld from its replay table. */
class WithheldReplay(
    /** The field keys (of the withheld steps, and the BAC answer if it was withheld) this run is evidence about. */
    val keys: Set<String>,
    val replay: CaptureRecord
)

/**
 * The hold-out rule. A profile is built from captures 1..N-1 and checked on capture N, which it was not built from: a
 * field earns MEASURED only if the captures it was built from agreed on it, capture N has the same value, and an
 * emulator replaying the profile answers that value.
 *
 * What that does and does not show is written down in docs/fidelity-diff.md. In short: it shows that the chip answered
 * the same in an independent capture (so the profile isn't one capture's accident, nor one phone's) and that the emulator
 * reproduces the profile's claim. It cannot show how the chip answers a command no probe sent. Because the replay is a
 * lookup of probe steps, it can't predict a step it has no entry for; the step hold-out (a deterministic subset of steps
 * is withheld from the table in each of [FOLDS] folds) therefore doesn't test prediction: it tells which fields the
 * emulator's own generic rules already get right ([HoldOutAward.matchesDefault]) and which only the replay does.
 *
 * This is the pure half, comparing records; [HoldOutEvaluator] runs the emulator to get the replays.
 */
object HoldOut {
    /** The number of folds the steps are split into; each step is withheld in exactly one. */
    const val FOLDS = 4

    /** The fold (0 until [FOLDS]) a step, or a key that stands for one, belongs to: a stable hash, so the split never moves. */
    fun foldOf(id: String): Int {
        var hash = 0x811C9DC5L
        id.encodeToByteArray().forEach { hash = ((hash xor (it.toLong() and 0xFF)) * 0x01000193L) and 0xFFFFFFFFL }
        return (hash % FOLDS).toInt()
    }

    /** The probe steps of [profile] that fold [fold] withholds, and the non-step keys that go with them. */
    fun withheldIn(profile: MeasuredProfile, fold: Int): Pair<Set<String>, Set<String>> {
        val o = profile.observations
        val steps = (o.statusWords.keys + o.responseSizes.keys).filter { foldOf(it) == fold }.toSet()
        val keys = if (o.bacExternalAuthenticate != null && foldOf(FieldKeys.BAC_EXTERNAL_AUTHENTICATE) == fold) {
            setOf(FieldKeys.BAC_EXTERNAL_AUTHENTICATE)
        } else emptySet()
        return steps to keys
    }

    /**
     * Fields of [heldOut] that earn MEASURED: comparable ones that [built] has with the same value and that [replay]
     * (the emulator, configured from [built], run through the same probe pack) reproduces. Nothing is awarded if
     * [heldOut] is one of the captures [built] was made from, or is another generation, or [built] has too few
     * captures to hold one out. [withheld] are the runs with steps left out of the table, which only classify the
     * awarded fields.
     */
    fun award(
        built: MeasuredProfile,
        heldOut: CaptureRecord,
        replay: CaptureRecord,
        withheld: List<WithheldReplay> = emptyList()
    ): HoldOutAward {
        if (heldOut.captureId in built.captureIds) {
            return HoldOutAward(emptySet(), emptyList(), "The held-out capture ${heldOut.captureId} is one the profile was built from: nothing is earned")
        }
        if (built.sampleCount < 1) return HoldOutAward(emptySet(), emptyList(), "The profile has no captures")
        if (heldOut.fingerprint != built.generation) {
            return HoldOutAward(emptySet(), emptyList(), "The held-out capture is another generation (${heldOut.fingerprint}, not ${built.generation})")
        }
        val want = FieldValues.fromRecord(heldOut)
        val profile = FieldValues.fromProfile(built)
        val replayed = FieldValues.fromRecord(replay)
        val awarded = linkedSetOf<String>()
        val rejected = mutableListOf<FieldDiff>()
        for ((key, expected) in want.values) {
            val (fidelity, _) = FieldCatalog.fidelityOf(key)
            if (fidelity == FieldFidelity.NOT_COMPARABLE) continue
            val step = FieldKeys.stepOf(key)
            val unstable = step != null && (step in replayed.unstable || step in want.unstable)
            val predicted = profile.values[key]
            val got = replayed.values[key]
            if (!unstable && predicted == expected && got == expected) {
                awarded += key
            } else {
                rejected += FieldDiff(
                    key, fidelity, expected, got, DiffOutcome.DEVIATION,
                    if (predicted != expected) "The profile had ${predicted ?: "no value"}" else "The replay did not reproduce it"
                )
            }
        }
        val matchesDefault = linkedSetOf<String>()
        val needsReplay = linkedSetOf<String>()
        withheld.forEach { run ->
            val answered = FieldValues.fromRecord(run.replay)
            run.keys.forEach { key ->
                if (key !in awarded) return@forEach
                if (answered.values[key] == want.values[key]) matchesDefault += key else needsReplay += key
            }
        }
        // A field one fold says the generic rules answer and no other says they don't
        needsReplay.forEach { matchesDefault -= it }
        return HoldOutAward(awarded, rejected, null, matchesDefault, needsReplay, withheld.size)
    }

    /**
     * [full] (the profile of all N captures) with the fields [award] earned marked MEASURED, on the evidence of all N
     * captures and the held-out one. Fields the full merge disagrees on are not marked. Fewer than two captures marks
     * nothing: such a profile stays at its curated provenance and says it needs more captures.
     */
    fun apply(full: MeasuredProfile, award: HoldOutAward, heldOutCaptureId: String, heldOutFromDifferentReader: Boolean = false): MeasuredProfile {
        if (full.sampleCount < ProfileMerger.MIN_CAPTURES_FOR_MEASURED || award.awarded.isEmpty()) return full
        val evidence = MeasuredEvidence(full.captureIds, full.sampleCount, full.probePacks, heldOutCaptureId, heldOutFromDifferentReader)
        return full.withMeasuredFields(award.awarded, evidence, award.matchesDefault)
    }
}
