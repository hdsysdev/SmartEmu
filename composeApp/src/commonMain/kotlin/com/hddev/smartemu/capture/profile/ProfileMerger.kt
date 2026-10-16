package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.LinkObservation

/**
 * Merges captures of one generation into a [MeasuredProfile]: a field is merged only where every capture that has
 * it agrees, and a field they disagree on is flagged and left out, never averaged. Timing is the one thing
 * aggregated (minimum, median, maximum), and is informational.
 *
 * Reader-side confounders (the controller's maximum transceive length and extended-length support) are not merged
 * at all: they describe the phone. A disagreement whose values split along reader models is marked
 * [Disagreement.acrossDevices], because it points at the phone more likely than at the chip.
 */
object ProfileMerger {
    /** Below this many captures nothing can be held out, so no field can earn MEASURED. */
    const val MIN_CAPTURES_FOR_MEASURED = 2

    /**
     * One profile per generation: [captures] grouped by their fingerprint, in the order each generation first appears.
     * [idFor] names the profile of a generation; by default "generation-" and the fingerprint.
     */
    fun mergeAll(
        captures: List<CaptureRecord>,
        idFor: (generation: String) -> String = { "generation-$it" }
    ): List<MeasuredProfile> =
        captures.groupBy { it.fingerprint }.map { (generation, group) -> merge(group, idFor(generation)) }

    /**
     * Merges [captures], which should share a fingerprint. If they don't, the profile is the first capture's
     * generation and the fields the others differ on are flagged like any disagreement. A capture seen twice (same
     * capture id) counts once.
     */
    fun merge(
        captures: List<CaptureRecord>,
        id: String? = null,
        chipProfileId: String? = null
    ): MeasuredProfile {
        val distinct = captures.distinctBy { it.captureId }
        require(distinct.isNotEmpty()) { "Nothing to merge" }
        val generation = distinct.first().fingerprint
        val flats = distinct.map { it to FieldValues.fromRecord(it) }

        val agreed = linkedMapOf<String, String>()
        val sources = linkedMapOf<String, List<String>>()
        val disagreements = mutableListOf<Disagreement>()

        if (distinct.any { it.fingerprint != generation }) {
            disagreements += disagreement(
                "generation", distinct.groupBy { it.fingerprint }, "The captures have different fingerprints: they are not one generation"
            )
        }
        if (distinct.any { it.probePack != distinct.first().probePack }) {
            disagreements += disagreement(
                "probePack", distinct.groupBy { "${it.probePack.id}/v${it.probePack.version}" }, "The captures ran different probe packs"
            )
        }

        // A step whose repeats disagree inside a capture has no value to merge: it is flagged, and its fields stay out
        val unstableSteps = linkedSetOf<String>().apply { flats.forEach { (_, flat) -> addAll(flat.unstable) } }
        for (step in unstableSteps) {
            val stable = flats.filter { (_, flat) -> step !in flat.unstable }
            val groups = linkedMapOf<String, List<CaptureRecord>>()
            stable.groupBy({ it.second.values[FieldKeys.stepStatus(step)] ?: "(no answer)" }, { it.first }).forEach { (value, records) -> groups[value] = records }
            groups["(unstable)"] = flats.filter { (_, flat) -> step in flat.unstable }.map { it.first }
            disagreements += disagreement(
                FieldKeys.stepStatus(step), groups, "Repeating the command gave different status words within one capture"
            )
        }

        val keys = linkedSetOf<String>().apply { flats.forEach { (_, flat) -> addAll(flat.values.keys) } }
        for (key in keys) {
            if (FieldKeys.stepOf(key)?.let { it in unstableSteps } == true) continue
            val holders = flats.filter { key in it.second.values }
            val byValue = holders.groupBy({ it.second.values.getValue(key) }, { it.first })
            if (byValue.size == 1) {
                agreed[key] = byValue.keys.first()
                sources[key] = holders.map { it.first.captureId }
            } else {
                disagreements += disagreement(key, byValue, "The captures answered differently")
            }
        }

        val timing = mergeTiming(flats.map { it.second.timing })
        val link = mergeLink(distinct, flats.map { it.second.link }, disagreements)

        val observations = FieldValues.observations(agreed, timing, link)
        val sampleCount = distinct.size
        val fields = buildList {
            agreed.forEach { (key, _) ->
                val (fidelity, reason) = FieldCatalog.fidelityOf(key)
                add(MeasuredField(key, fidelity, reason, FieldCatalog.aspectOf(key)?.name, captureIds = sources.getValue(key)))
            }
            timing.keys.forEach { step ->
                val key = FieldKeys.stepTiming(step)
                val (fidelity, reason) = FieldCatalog.fidelityOf(key)
                add(MeasuredField(key, fidelity, reason, null, captureIds = distinct.map { it.captureId }))
            }
            if (link != null) listOf(FieldKeys.LINK_TECHNOLOGY, FieldKeys.LINK_HISTORICAL_BYTES, FieldKeys.LINK_UID_RANDOM).forEach { key ->
                val (fidelity, reason) = FieldCatalog.fidelityOf(key)
                add(MeasuredField(key, fidelity, reason, null, captureIds = distinct.map { it.captureId }))
            }
        }
        val packs = distinct.flatMap { it.additionalProbePacks }.distinct().filter { it != distinct.first().probePack }
        return MeasuredProfile(
            id = id ?: "generation-$generation",
            chipProfileId = chipProfileId,
            generation = generation,
            probePack = distinct.first().probePack,
            additionalProbePacks = packs,
            sampleCount = sampleCount,
            captureIds = distinct.map { it.captureId },
            // The plan holds n=1 captures of rare classes for review; a capture can't say how rare its class is, so
            // every single-capture profile is held until a steward has looked
            needsReview = sampleCount < MIN_CAPTURES_FOR_MEASURED,
            needsMoreCaptures = sampleCount < MIN_CAPTURES_FOR_MEASURED,
            observations = observations,
            fields = fields,
            disagreements = disagreements
        )
    }

    private fun disagreement(field: String, groups: Map<String, List<CaptureRecord>>, reason: String): Disagreement {
        val values = groups.map { (value, records) ->
            DisagreementValue(value, records.map { it.captureId }, records.map { it.reader.model }.distinct().sorted())
        }
        // Across devices: no reader model has captures of more than one value
        val modelsPerValue = values.map { it.readerModels.toSet() }
        val acrossDevices = values.size > 1 && modelsPerValue.indices.all { i ->
            modelsPerValue.indices.all { j -> i == j || modelsPerValue[i].none { it in modelsPerValue[j] } }
        } && modelsPerValue.flatten().toSet().size > 1
        return Disagreement(field, values, reason, acrossDevices)
    }

    private fun mergeTiming(timings: List<Map<String, TimingAggregate>>): Map<String, TimingAggregate> {
        val steps = linkedSetOf<String>().apply { timings.forEach { addAll(it.keys) } }
        return steps.associateWith { step ->
            val all = timings.mapNotNull { it[step] }
            TimingAggregate(
                minMs = all.minOf { it.minMs },
                medianMs = all.map { it.medianMs }.sorted().let { it[it.size / 2] },
                maxMs = all.maxOf { it.maxMs },
                samples = all.sumOf { it.samples }
            )
        }
    }

    /** The link layer if the captures agree on it; flagged otherwise. Only informational either way. */
    private fun mergeLink(records: List<CaptureRecord>, links: List<LinkObservation?>, disagreements: MutableList<Disagreement>): LinkSummary? {
        val summaries = records.zip(links).mapNotNull { (record, link) -> link?.let { record to LinkSummary(it.technology, it.historicalBytes, it.uidRandom) } }
        if (summaries.isEmpty()) return null
        val byValue = summaries.groupBy({ it.second }, { it.first })
        if (byValue.size == 1) return byValue.keys.first()
        disagreements += disagreement(
            "link", byValue.mapKeys { (summary, _) -> "${summary.technology}/${summary.historicalBytes}/${summary.uidRandom}" },
            "The link layer differed between captures (informational: not emulatable)"
        )
        return null
    }
}
