package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.ApduTransport
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CaptureResult
import com.hddev.smartemu.capture.CaptureRunner
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePackRef
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The fidelity diff: runs the probe pack against an emulated chip and compares what it answered with a measured
 * profile, field by field. A field is compared exactly, or, for random and cryptographic ones, by shape; a field
 * that can't be checked (timing, the link layer) is listed with its reason and never counted as a match. Deviations
 * the reviewed allowed-deviation file lists are reported as allowed.
 *
 * The JVM diff bypasses Android's HCE stack, so it is necessary and not sufficient: a second-phone run is the release
 * gate (see docs/capture-mode-plan.md, Phase 4).
 */
object FidelityDiff {
    /**
     * Runs [pack] (and [additionalPacks]) against [emulator], which must already be configured from [profile] (see
     * [MeasuredChipProfile]), and diffs the result. A run that doesn't complete compares nothing and says why.
     */
    fun run(
        profile: MeasuredProfile,
        emulator: ApduTransport,
        pack: ProbePack = ProbePack.tierA,
        additionalPacks: List<ProbePack> = emptyList(),
        allowed: AllowedDeviations = AllowedDeviations.none,
        today: LocalDate = todayUtc()
    ): DiffReport {
        val refs = listOf(pack.ref) + additionalPacks.map(ProbePack::ref)
        val runner = CaptureRunner(
            pack = pack, additionalPacks = additionalPacks,
            // The transport is an emulator: no counter of a real card can be touched
            acknowledgeSacrificialCard = true
        )
        return when (val result = runner.run(emulator)) {
            is CaptureResult.Complete -> compare(profile, result.record, refs, allowed, today)
            is CaptureResult.Aborted -> DiffReport(profile.id, refs, emptyList(), failure = "The run was aborted: ${result.reason}")
        }
    }

    /** Diffs a record of the emulator's run against [profile]. */
    fun compare(
        profile: MeasuredProfile,
        replay: CaptureRecord,
        packs: List<ProbePackRef> = listOf(replay.probePack),
        allowed: AllowedDeviations = AllowedDeviations.none,
        today: LocalDate = todayUtc()
    ): DiffReport =
        FieldDiffer.compare(profile.id, packs, FieldValues.fromProfile(profile), FieldValues.fromRecord(replay), allowed, today)

    fun todayUtc(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
}
