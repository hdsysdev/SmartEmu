package com.hddev.smartemu.capture

/**
 * Every published probe pack, so that the ingest lint, the profile diff and the UI can resolve a result to the exact
 * pack definition that produced it. A new version is added to [all]; an old one is never removed or changed, because
 * captures made under it stay valid (see docs/probe-packs.md).
 */
object ProbePacks {
    /** Every published pack and version, oldest first. */
    val all: List<ProbePack> by lazy { listOf(ProbePack.tierAV1, ProbePack.tierA, ProbePack.tierB, ProbePack.tierC) }

    /** The pack with [id] at [version], or null if there is none. */
    fun byId(id: String, version: Int): ProbePack? = all.firstOrNull { it.id == id && it.version == version }

    fun byRef(ref: ProbePackRef): ProbePack? = byId(ref.id, ref.version)

    /** The newest version of the pack called [id], or null. */
    fun latest(id: String): ProbePack? = all.filter { it.id == id }.maxByOrNull { it.version }

    /**
     * The probe a [ProbeResult] came from. A probe id is unique among the packs of one version (a test checks it), so
     * the id and the pack version name one probe. A new version of a pack keeps the ids of the probes it didn't change
     * (tier-a v2 has the ids of v1), which the version tells apart.
     */
    fun probeOf(result: ProbeResult): Probe? = packOf(result)?.probe(result.probeId)

    /** The pack a [ProbeResult] came from, resolved the same way as [probeOf]. */
    fun packOf(result: ProbeResult): ProbePack? =
        all.firstOrNull { pack -> pack.version == result.packVersion && pack.probe(result.probeId) != null }

    /** The step a [StepResult] of [result] came from, or null if the pack no longer (or never) had it. */
    fun stepOf(result: ProbeResult, step: StepResult): ProbeStep? = probeOf(result)?.steps?.firstOrNull { it.id == step.stepId }

    /** Every pack a [CaptureRecord] ran, the main one first; refs that name no published pack are left out. */
    fun packsOf(record: CaptureRecord): List<ProbePack> = (listOf(record.probePack) + record.additionalProbePacks).mapNotNull(::byRef)
}
