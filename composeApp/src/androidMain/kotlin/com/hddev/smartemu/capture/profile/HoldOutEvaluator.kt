package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePacks
import kotlinx.datetime.LocalDate

/** What the hold-out evaluation decided. */
class HoldOutResult(
    /** The profile of every distinct capture, with the fields that earned MEASURED marked. */
    val profile: MeasuredProfile,
    /** The capture the profile was checked against without having been built from it; null if there was none to hold out. */
    val heldOutCaptureId: String?,
    /** The captures the checked profile was built from. */
    val builtFromCaptureIds: List<String>,
    /** The replay of the built profile against its own fields (profile against emulator); null if nothing was replayed. */
    val replay: DiffReport?,
    val award: HoldOutAward,
    /** True if the held-out capture came from a reader model none of the built-from captures used. */
    val heldOutFromDifferentReader: Boolean = false
) {
    /** The fields that earned MEASURED. */
    val measuredKeys: Set<String> get() = award.awarded
}

/**
 * The hold-out rule, run: given captures of one generation, hold one out (a capture with a different capture id from
 * every one the profile is built from, and, where the captures say which phone read them, one from a reader model the
 * others didn't use), build a profile from the rest, configure an emulator from it, run the probe packs of the held-out
 * capture against that emulator, and award MEASURED only to the fields on which the profile and the replay both match
 * the held-out capture. A profile built from and checked against the same capture earns nothing, and neither does one of a
 * single capture: it stays below MEASURED with a note that it needs more captures.
 *
 * Then, in [HoldOut.FOLDS] more runs, a deterministic quarter of the probe steps at a time is withheld from the replay
 * table, so the emulator answers them by its own generic rules, and what it answers is compared with the held-out
 * capture: the awarded fields it already gets right are marked as matching the default (see [HoldOutAward.matchesDefault]).
 * Those runs classify; they never decide what is awarded.
 */
object HoldOutEvaluator {
    /**
     * [captures] in the order they were made, one generation. [emulatorFor] returns an emulator transport configured
     * from the profile it is given (the profile built from 1..N-1). [id] and [chipProfileId] name the resulting profile.
     */
    fun evaluate(
        captures: List<CaptureRecord>,
        emulatorFor: (MeasuredProfile) -> ApduTransport,
        id: String? = null,
        chipProfileId: String? = null,
        allowed: AllowedDeviations = AllowedDeviations.none,
        today: LocalDate = FidelityDiff.todayUtc()
    ): HoldOutResult {
        // The same capture twice is one capture: it can't be both what a profile is built from and what it is checked on
        val distinct = captures.distinctBy { it.captureId }
        val full = ProfileMerger.merge(distinct, id, chipProfileId)
        if (distinct.size < ProfileMerger.MIN_CAPTURES_FOR_MEASURED) {
            return HoldOutResult(
                full, null, distinct.map { it.captureId }, null,
                HoldOutAward(emptySet(), emptyList(), "Needs more captures: one capture can't be held out, so no field is MEASURED")
            )
        }
        val heldOut = chooseHeldOut(distinct)
        val built = distinct.filter { it.captureId != heldOut.captureId }
        val differentReader = built.none { it.reader.model == heldOut.reader.model }
        val partial = ProfileMerger.merge(built, id, chipProfileId)
        val packs = ProbePacks.packsOf(heldOut).ifEmpty { listOf(ProbePack.tierA) }
        fun replayOf(profile: MeasuredProfile): CaptureResult =
            CaptureRunner(pack = packs.first(), additionalPacks = packs.drop(1), acknowledgeSacrificialCard = true)
                .run(emulatorFor(profile.forEvaluation()))

        val record = when (val result = replayOf(partial)) {
            is CaptureResult.Complete -> result.record
            is CaptureResult.Aborted -> return HoldOutResult(
                full, heldOut.captureId, built.map { it.captureId }, null,
                HoldOutAward(emptySet(), emptyList(), "The replay was aborted: ${result.reason}"), differentReader
            )
        }
        // The step hold-out: each fold answers its withheld steps from the emulator's generic rules
        val wanted = FieldValues.fromRecord(heldOut)
        val withheld = (0 until HoldOut.FOLDS).mapNotNull { fold ->
            val (steps, keys) = HoldOut.withheldIn(partial, fold)
            if (steps.isEmpty() && keys.isEmpty()) return@mapNotNull null
            val run = replayOf(partial.withheld(steps, keys)) as? CaptureResult.Complete ?: return@mapNotNull null
            val fieldKeys = wanted.values.keys.filter { key -> FieldKeys.stepOf(key)?.let { it in steps } == true } + keys
            WithheldReplay(fieldKeys.toSet(), run.record)
        }
        val award = HoldOut.award(partial, heldOut, record, withheld)
        val replay = FidelityDiff.compare(partial, record, packs.map(ProbePack::ref), allowed, today)
        return HoldOutResult(
            HoldOut.apply(full, award, heldOut.captureId, differentReader), heldOut.captureId, built.map { it.captureId }, replay, award, differentReader
        )
    }

    /**
     * The capture to hold out: the last one, or, where the captures name their readers, the last whose reader model none of
     * the others used (a different phone can't share a reader-side quirk). Never one the profile is built from: the
     * built-from captures are all the others.
     */
    internal fun chooseHeldOut(captures: List<CaptureRecord>): CaptureRecord =
        captures.lastOrNull { c -> captures.none { it.captureId != c.captureId && it.reader.model == c.reader.model } } ?: captures.last()
}
