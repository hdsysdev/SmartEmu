package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.NotEmulatable

/**
 * Renders the evidence behind measured profiles as Markdown, from the profiles' JSON and nothing else, so the page
 * can't drift from the data. The output goes to docs/chip-profile-evidence.measured.md; the hand-written
 * docs/chip-profile-evidence.md is not touched.
 */
object ChipProfileEvidenceDoc {
    const val FILE = "docs/chip-profile-evidence.measured.md"

    fun render(profiles: List<MeasuredProfile>): String = buildString {
        val sorted = profiles.sortedWith(compareBy({ it.id }, { it.generation }))
        appendLine("<!--")
        appendLine("GENERATED FILE. Do not edit.")
        appendLine("Written by ChipProfileEvidenceDoc.render from the profiles in profiles/*.json (ChipProfileEvidenceDocTest regenerates it).")
        appendLine("The hand-written evidence for the curated profiles is in chip-profile-evidence.md.")
        appendLine("-->")
        appendLine()
        appendLine("# Measured chip profile evidence")
        appendLine()
        appendLine("> ${FidelityContract.STATEMENT}")
        appendLine()
        appendLine("MEASURED is awarded per field, only after the hold-out rule passed with at least two captures: the profile is built from captures 1..N-1 and the emulator, configured from it, must reproduce capture N. That shows the chip answered the same in an independent capture and that the replay reproduces it; it does not show how the chip answers a command no probe sent. Fields marked \"matches emulator default\" are ones the emulator's own generic rules already answer, so the replay adds nothing to them. See docs/fidelity-diff.md.")
        appendLine()
        appendLine("Not emulatable on Android HCE, captured and labelled but never claimed:")
        appendLine()
        NotEmulatable.properties.forEach { appendLine("- $it") }
        appendLine()
        if (sorted.isEmpty()) {
            appendLine("No measured profile has been merged yet: `profiles/` holds none. Until real captures are merged, every curated profile keeps its provenance from specifications and reports.")
            return@buildString
        }
        appendLine("## Summary")
        appendLine()
        appendLine("| Profile | Generation | Captures (n) | Probe pack | MEASURED fields | Status |")
        appendLine("|---|---|---|---|---|---|")
        sorted.forEach { p ->
            val badge = FidelityBadge.of(p)
            appendLine(
                "| `${p.id}` | `${p.generation}` | ${p.sampleCount} | ${p.probePacks.joinToString(", ") { "${it.id} v${it.version}" }} | " +
                    "${badge.measuredFields} of ${badge.checkableFields} | ${badge.label} |"
            )
        }
        sorted.forEach { p -> section(p) }
    }

    private fun StringBuilder.section(p: MeasuredProfile) {
        val badge = FidelityBadge.of(p)
        appendLine()
        appendLine("## `${p.id}`")
        appendLine()
        appendLine("- Generation (key of what the chip itself fixes): `${p.generation}`")
        p.chipProfileId?.let { appendLine("- Linked curated profile: `$it`") }
        appendLine("- Captures: ${p.sampleCount} (${p.captureIds.joinToString { "`$it`" }})")
        appendLine("- Probe packs: ${p.probePacks.joinToString { "${it.id} v${it.version}" }}")
        appendLine("- Status: ${badge.label}. ${badge.detail}")
        val defaults = p.measuredFields.count { it.matchesDefault }
        if (defaults > 0) appendLine("- Of the MEASURED fields, $defaults match the emulator's own default (the replay adds nothing to them) and ${p.measuredFields.size - defaults} are ones only the replay gets right.")
        if (p.needsReview) appendLine("- Needs review: a single capture is held for a steward before it is shared.")
        if (p.needsMoreCaptures) appendLine("- Needs more captures: nothing can be held out, so no field is MEASURED.")
        val o = p.observations
        appendLine("- EF.CardAccess: ${o.cardAccessHex?.let { "${it.length / 2} bytes, replayed verbatim" } ?: "none"}")
        appendLine("- EF.ATR/INFO: ${o.atrInfoHex?.let { "${it.length / 2} bytes" } ?: "none"}; EF.DIR: ${o.dirHex?.let { "${it.length / 2} bytes" } ?: "none"}")
        appendLine("- Largest READ BINARY answered in full: ${o.maxReadLength?.toString() ?: "not determined (${NotComparableReasons.READ_LENGTH_UNDETERMINED})"}")
        appendLine()
        appendLine("### Fields")
        appendLine()
        appendLine("| Field | Fidelity | Provenance | Captures |")
        appendLine("|---|---|---|---|")
        p.fields.sortedBy { it.key }.forEach { f ->
            val provenance = when {
                f.fidelity == FieldFidelity.NOT_COMPARABLE -> "not comparable: ${f.reason}"
                f.measured -> "MEASURED (n=${f.evidence?.sampleCount}, held out ${f.evidence?.heldOutCaptureId}${if (f.evidence?.heldOutFromDifferentReader == true) ", other reader" else ""})" +
                    if (f.matchesDefault) ", matches emulator default" else ""
                else -> "not verified"
            }
            appendLine("| `${f.key}` | ${f.fidelity.name.lowercase().replace('_', ' ')} | $provenance | ${f.captureIds.size} |")
        }
        if (p.disagreements.isNotEmpty()) {
            appendLine()
            appendLine("### Disagreements")
            appendLine()
            appendLine("Flagged, not averaged, and left out of the profile.")
            appendLine()
            p.disagreements.sortedBy { it.field }.forEach { d ->
                val across = if (d.acrossDevices) " Split across devices." else ""
                appendLine("- `${d.field}`: ${d.reason}.$across Values: ${d.values.joinToString("; ") { "`${it.value}` in ${it.captureIds.size}" }}")
            }
        }
    }
}
