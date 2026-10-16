package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.Provenance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How well a document class is established, strongest first; see [CoverageMatrix]. */
@Serializable
enum class CoverageStatus(val label: String) {
    /** At least one field earned MEASURED through the hold-out rule. */
    MEASURED("Measured"),

    /** Captured from real chips, but nothing has earned MEASURED yet (n=1: nothing can be held out). */
    CAPTURED("Captured"),

    /** The curated profile's best aspect is required by a standard or regulation. */
    SPECIFICATION("Specification"),

    /** The curated profile's best aspect is described in public sources, not checked against a chip. */
    REPORTED("Reported"),

    /** Everything is an emulator default. */
    ASSUMED("Assumed");

    /** True where a real chip of this class has been captured. */
    val hasCaptures: Boolean get() = this == MEASURED || this == CAPTURED
}

/** One row of the coverage matrix: a curated document class, and what is known of it. */
@Serializable
data class CoverageRow(
    val profileId: String,
    /** ICAO 9303 code of the issuing state, or "ANY" for a stand-in that fits any state of a group. */
    val country: String,
    /** The kind of document and its generation, as the curated profile titles it. */
    val document: String,
    val status: CoverageStatus,
    /** The status as it is written, "Captured (n=1)" for a single capture. */
    val statusLabel: String,
    /** Distinct captures merged into the linked measured profiles; 0 where none. */
    val n: Int,
    /** How many of the curated profile's aspects have each provenance (MEASURED counted where a field earned it). */
    val provenance: Map<String, Int>,
    /** Fewer than three captures: the evidence is thin. Never set where there are none. */
    val thin: Boolean,
    /** 1 for the document to capture first. Rows with no captures come first, the least established among them first. */
    val captureRank: Int
)

/** A measured profile that no curated profile claims: a generation seen on a real chip that has no row of its own yet. */
@Serializable
data class UnlinkedGeneration(val profileId: String, val generation: String, val n: Int, val statusLabel: String)

@Serializable
data class CoverageReport(val rows: List<CoverageRow>, val unlinked: List<UnlinkedGeneration>)

/**
 * The coverage matrix: country by document by status by number of captures, from the curated profiles and the measured
 * ones that were merged, so that maintainers see which documents to capture next. Pure: it reads counts and random
 * capture ids' totals only, never a per-capture record, so the page can't expose anything restricted.
 *
 * Status is the strongest evidence any aspect of the document has: Measured (a field passed the hold-out rule),
 * Captured (a real chip was captured, nothing verified yet; with n shown), then, by the curated profile's own
 * provenance, Specification, Reported, Assumed. A measured profile links to a curated one by
 * [MeasuredProfile.chipProfileId].
 */
object CoverageMatrix {
    const val JSON_FILE = "docs/coverage-matrix.json"
    const val DOC_FILE = "docs/coverage-matrix.md"

    /** Markers around the generated part of the page, so that its explanatory header stays hand-written. */
    const val BEGIN_MARKER = "<!-- BEGIN GENERATED: coverage matrix (CoverageMatrix.render; regenerate, do not edit between the markers) -->"
    const val END_MARKER = "<!-- END GENERATED: coverage matrix -->"

    /** Fewer captures than this and a row is marked thin. */
    const val THIN_BELOW = 3

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun report(profiles: List<MeasuredProfile>, curatedProfiles: List<ChipProfile>): CoverageReport {
        val documents = curatedProfiles.filter { it.country != null || it.documentType != null }
        val byCurated = profiles.filter { m -> m.chipProfileId != null }.groupBy { it.chipProfileId }
        val rows = documents.map { curated ->
            val linked = byCurated[curated.id].orEmpty()
            val n = linked.flatMap { it.captureIds }.distinct().size
            val measured = linked.any { it.measuredFields.isNotEmpty() }
            val mix = provenanceMix(curated, linked)
            val status = when {
                measured -> CoverageStatus.MEASURED
                n > 0 -> CoverageStatus.CAPTURED
                else -> curatedStatus(curated)
            }
            CoverageRow(
                profileId = curated.id,
                country = curated.country ?: "ANY",
                document = documentName(curated),
                status = status,
                statusLabel = if (status.hasCaptures) "${status.label} (n=$n)" else status.label,
                n = n,
                provenance = mix,
                thin = n in 1 until THIN_BELOW,
                captureRank = 0
            )
        }
        val ranked = rows
            // No captures first, the weakest evidence first among them; then the thinnest captured ones; ties by name
            .sortedWith(
                compareBy<CoverageRow>({ it.status.hasCaptures }, { -it.status.ordinal }, { it.n }, { it.country }, { it.profileId })
            )
            .mapIndexed { index, row -> row.copy(captureRank = index + 1) }
        val known = documents.map { it.id }.toSet()
        val unlinked = profiles.filter { it.chipProfileId == null || it.chipProfileId !in known }
            .sortedWith(compareBy({ it.id }, { it.generation }))
            .map { UnlinkedGeneration(it.id, it.generation, it.sampleCount, FidelityBadge.of(it).label) }
        return CoverageReport(ranked, unlinked)
    }

    /** The generated section of docs/coverage-matrix.md, between [BEGIN_MARKER] and [END_MARKER]. */
    fun render(profiles: List<MeasuredProfile>, curatedProfiles: List<ChipProfile>): String {
        val report = report(profiles, curatedProfiles)
        return buildString {
            appendLine(BEGIN_MARKER)
            appendLine("<!-- Written by CoverageMatrix.render from ChipProfiles.all and profiles/*.json; CoverageMatrixTest regenerates it. -->")
            appendLine()
            appendLine("**GENERATED. Do not edit this table.**")
            appendLine()
            val captured = report.rows.count { it.status.hasCaptures }
            appendLine("${report.rows.size} documents: $captured with captures, ${report.rows.size - captured} without. Capture the top rows first.")
            appendLine()
            appendLine("| Capture next | Country | Document | Profile | Status | n | Provenance of the aspects |")
            appendLine("|---|---|---|---|---|---|---|")
            report.rows.forEach { row ->
                val thin = if (row.thin) " (thin)" else ""
                val mix = row.provenance.entries.joinToString(", ") { (name, count) -> "$count $name" }
                appendLine(
                    "| ${if (row.status.hasCaptures) "-" else row.captureRank.toString()} | ${row.country} | ${row.document} | " +
                        "`${row.profileId}` | ${row.statusLabel}$thin | ${row.n} | $mix |"
                )
            }
            if (report.unlinked.isNotEmpty()) {
                appendLine()
                appendLine("### Captured generations with no curated profile")
                appendLine()
                appendLine("| Profile | Generation | n | Status |")
                appendLine("|---|---|---|---|")
                report.unlinked.forEach { appendLine("| `${it.profileId}` | `${it.generation}` | ${it.n} | ${it.statusLabel} |") }
            }
            appendLine()
            appendLine(END_MARKER)
        }
    }

    /** The machine-readable form, docs/coverage-matrix.json: the same rows, in the same order. */
    fun renderJson(profiles: List<MeasuredProfile>, curatedProfiles: List<ChipProfile>): String =
        json.encodeToString(CoverageReport.serializer(), report(profiles, curatedProfiles)) + "\n"

    /**
     * [page] with the text between the markers replaced by [generated] (which carries the markers itself). A page with
     * no markers gets the section appended, so a header written by hand is never lost.
     */
    fun inject(page: String, generated: String, begin: String = BEGIN_MARKER, end: String = END_MARKER): String {
        val start = page.indexOf(begin)
        val stop = page.indexOf(end)
        return if (start >= 0 && stop > start) {
            page.substring(0, start) + generated.trimEnd('\n') + page.substring(stop + end.length)
        } else {
            page.trimEnd('\n') + "\n\n" + generated
        }
    }

    /** The title without its "Country · " prefix: the country has a column of its own. */
    private fun documentName(curated: ChipProfile): String = curated.title.substringAfter(" · ", curated.title)

    /** The strongest provenance of the curated profile's aspects, as a status. */
    private fun curatedStatus(curated: ChipProfile): CoverageStatus {
        val values = curated.provenance.values
        return when {
            Provenance.SPECIFICATION in values -> CoverageStatus.SPECIFICATION
            Provenance.REPORTED in values -> CoverageStatus.REPORTED
            else -> CoverageStatus.ASSUMED
        }
    }

    /** Per provenance, how many aspects of the curated profile have it; MEASURED where a linked measured profile earned the aspect. */
    private fun provenanceMix(curated: ChipProfile, linked: List<MeasuredProfile>): Map<String, Int> {
        val counts = ProfileAspect.entries.map { aspect ->
            if (linked.any { it.isMeasured(aspect) }) Provenance.MEASURED else curated.provenanceOf(aspect)
        }.groupingBy { it }.eachCount()
        return Provenance.entries.filter { counts.containsKey(it) }.associate { it.displayName.lowercase() to counts.getValue(it) }
    }
}
