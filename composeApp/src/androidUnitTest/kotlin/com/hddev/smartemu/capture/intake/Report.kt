package com.hddev.smartemu.capture.intake

/** The report an intake prints: what was accepted, rejected and written, and how each generation fared. */
internal class Report(
    private val options: IntakeOptions,
    private val accepted: List<Acceptance>,
    private val rejected: List<Rejection>,
    private val generations: List<GenerationOutcome>,
    private val docs: List<DocChange>,
    private val loadErrors: List<String>,
    private val store: CaptureStore
) {
    fun render(): String = buildString {
        val verb = if (options.apply) "" else "would be "
        appendLine(if (options.apply) "Capture intake (APPLIED)" else "Capture intake (DRY RUN: nothing is written or moved; add --apply)")
        appendLine("Inbox: ${options.inputDir}")
        appendLine("Store: ${options.storeDir} (restricted; per-capture records are never committed)")
        appendLine()
        val fresh = accepted.filter { !it.alreadyStored }
        appendLine("Accepted: ${fresh.size} ${verb}stored" + if (accepted.size > fresh.size) ", ${accepted.size - fresh.size} already in the store" else "")
        fresh.forEach { appendLine("  ${it.file}: capture ${it.record.captureId}, generation ${it.record.fingerprint}") }
        accepted.filter { it.alreadyStored }.forEach { appendLine("  ${it.file}: capture ${it.record.captureId} already stored, not counted twice") }
        appendLine("Rejected: ${rejected.size}" + if (rejected.isNotEmpty()) " (${verb}quarantined in ${options.inputDir}/${CaptureIntake.REJECTED_DIR}/ with a .reason.txt)" else "")
        rejected.forEach { rejection ->
            appendLine("  ${rejection.file}")
            rejection.reasons.forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("Profiles by generation (${generations.size}; store holds ${store.count()} captures${if (options.apply) "" else " before this run"}):")
        generations.forEach { g ->
            val p = g.profile
            val where = when (g.placement) {
                Placement.SHARED -> "profiles/${p.id}.json"
                Placement.PENDING -> "profiles/pending/${p.id}.json (HELD BACK: one capture, not shared until a second capture or steward review)"
            }
            val change = if (g.changed) "${verb}written" else "unchanged"
            appendLine("  ${p.id}: generation ${p.generation}, n=${p.sampleCount}, ${g.badge.label}")
            appendLine("    $where: $change")
            appendLine("    hold-out: ${g.holdOut}")
            if (p.disagreements.isNotEmpty()) appendLine("    disagreements flagged: ${p.disagreements.map { it.field }}")
            if (g.diff == null) {
                appendLine("    fidelity diff: not run (${g.diffNote})")
            } else {
                appendLine("    fidelity diff: ${if (g.diff.isClean) "clean" else "DEVIATIONS"}: ${g.diff.matches.size} matched, ${g.diff.deviations.size} deviations")
                if (!g.diff.isClean) g.diff.toTable().lines().forEach { appendLine("      $it") }
            }
        }
        appendLine()
        appendLine("Generated docs from the shared set (profiles/*.json, not pending):")
        docs.forEach { appendLine("  ${it.file.relativeTo(options.repoDir)}: ${if (it.changed) "${verb}updated" else "unchanged"}") }
        if (loadErrors.isNotEmpty()) {
            appendLine()
            appendLine("Profile files that did not load:")
            loadErrors.forEach { appendLine("  $it") }
        }
        appendLine()
        val held = generations.count { it.placement == Placement.PENDING }
        appendLine("Summary: ${fresh.size} accepted, ${rejected.size} rejected, ${generations.size} generations, $held held back in profiles/pending/.")
        if (!options.apply) appendLine("Nothing was written. Re-run with --apply to write.")
    }
}
