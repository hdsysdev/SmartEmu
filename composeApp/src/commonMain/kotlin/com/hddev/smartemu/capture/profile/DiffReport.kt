package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.ProbePackRef
import kotlinx.datetime.LocalDate

enum class DiffOutcome(val label: String) {
    MATCH("match"),
    DEVIATION("DEVIATION"),
    ALLOWED("allowed"),
    NOT_COMPARABLE("not comparable")
}

/** One field of the diff: what the profile has, what the emulator did, and the verdict. */
data class FieldDiff(
    val key: String,
    val fidelity: FieldFidelity,
    val expected: String?,
    val actual: String?,
    val outcome: DiffOutcome,
    /** Why a field is not comparable, or the allowed-deviation's reason. */
    val note: String? = null
)

/** The result of running a probe pack against the emulator and comparing it to a profile. */
class DiffReport(
    val profileId: String,
    val probePacks: List<ProbePackRef>,
    val entries: List<FieldDiff>,
    /** The allowed deviations that were used, for the report to list. */
    val allowedUsed: List<AllowedDeviation> = emptyList(),
    /** Allowed deviations that have expired and so allowed nothing. */
    val expiredAllowances: List<AllowedDeviation> = emptyList(),
    /** Set where the run itself failed (the tag was lost, the run was refused), so nothing was compared. */
    val failure: String? = null
) {
    val deviations: List<FieldDiff> get() = entries.filter { it.outcome == DiffOutcome.DEVIATION }

    val matches: List<FieldDiff> get() = entries.filter { it.outcome == DiffOutcome.MATCH }

    /** True if the run completed and every comparable field matched or is an allowed deviation. */
    val isClean: Boolean get() = failure == null && deviations.isEmpty()

    /** The keys of fields that matched, which is all the hold-out rule needs of a replay. */
    val matchedKeys: Set<String> get() = matches.map { it.key }.toSet()

    /** A table for a CI log; fixed columns, one row per field, deviations first. */
    fun toTable(): String {
        val rows = entries.sortedWith(compareBy({ if (it.outcome == DiffOutcome.DEVIATION) 0 else 1 }, { it.key }))
        val header = listOf("FIELD", "KIND", "PROFILE", "EMULATOR", "RESULT", "NOTE")
        val body = rows.map {
            listOf(it.key, it.fidelity.name.lowercase().replace('_', ' '), shorten(it.expected), shorten(it.actual), it.outcome.label, it.note.orEmpty())
        }
        val widths = header.indices.map { i -> (listOf(header[i]) + body.map { it[i] }).maxOf { it.length } }
        fun line(cells: List<String>) = cells.indices.joinToString(" | ") { cells[it].padEnd(widths[it]) }.trimEnd()
        return buildString {
            appendLine("Fidelity diff: $profileId on ${probePacks.joinToString { "${it.id} v${it.version}" }}")
            failure?.let { appendLine("RUN FAILED: $it") }
            appendLine(line(header))
            appendLine(widths.joinToString("-+-") { "-".repeat(it) })
            body.forEach { appendLine(line(it)) }
            appendLine(
                "${matches.size} matched, ${deviations.size} deviations, " +
                    "${entries.count { it.outcome == DiffOutcome.ALLOWED }} allowed, " +
                    "${entries.count { it.outcome == DiffOutcome.NOT_COMPARABLE }} not comparable"
            )
            if (allowedUsed.isNotEmpty()) {
                appendLine("Allowed deviations used:")
                allowedUsed.forEach { appendLine("  ${it.field}: ${it.reason} (owner ${it.owner}, expires ${it.expires ?: "never"})") }
            }
            if (expiredAllowances.isNotEmpty()) {
                appendLine("Expired allowed deviations (no longer honoured):")
                expiredAllowances.forEach { appendLine("  ${it.field}: ${it.reason} (expired ${it.expires})") }
            }
        }
    }

    private fun shorten(value: String?): String = when {
        value == null -> "-"
        value.length > 24 -> value.take(10) + "..." + value.takeLast(8) + " (${value.length / 2}B)"
        else -> value
    }
}

/** Compares a profile's fields with what the emulator did, field by field. */
object FieldDiffer {
    fun compare(
        profileId: String,
        probePacks: List<ProbePackRef>,
        expected: FlatFields,
        actual: FlatFields,
        allowed: AllowedDeviations = AllowedDeviations.none,
        today: LocalDate
    ): DiffReport {
        val entries = mutableListOf<FieldDiff>()
        val used = linkedSetOf<AllowedDeviation>()
        for ((key, want) in expected.values) {
            val (fidelity, reason) = FieldCatalog.fidelityOf(key)
            if (fidelity == FieldFidelity.NOT_COMPARABLE) {
                // Authenticated structure is many keys with one reason: one row says it
                if (!key.startsWith(FieldCatalog.AUTH_PREFIX)) entries += FieldDiff(key, fidelity, want, actual.values[key], DiffOutcome.NOT_COMPARABLE, reason)
                continue
            }
            val step = FieldKeys.stepOf(key)
            val got = if (step != null && step in actual.unstable) "(unstable)" else actual.values[key]
            if (got == want) {
                entries += FieldDiff(key, fidelity, want, got, DiffOutcome.MATCH, if (fidelity == FieldFidelity.STRUCTURAL) reason else null)
                continue
            }
            val allowance = allowed.find(profileId, key, today)
            if (allowance != null) {
                used += allowance
                entries += FieldDiff(key, fidelity, want, got, DiffOutcome.ALLOWED, allowance.reason)
            } else {
                entries += FieldDiff(key, fidelity, want, got ?: "(no answer)", DiffOutcome.DEVIATION)
            }
        }
        if (expected.timing.isNotEmpty()) {
            entries += FieldDiff("step:*:timing", FieldFidelity.NOT_COMPARABLE, null, null, DiffOutcome.NOT_COMPARABLE, NotComparableReasons.TIMING)
        }
        if (expected.values.keys.any { it.startsWith(FieldCatalog.AUTH_PREFIX) }) {
            entries += FieldDiff("auth:*", FieldFidelity.NOT_COMPARABLE, null, null, DiffOutcome.NOT_COMPARABLE, NotComparableReasons.AUTHENTICATED)
        }
        if (expected.link != null) {
            entries += FieldDiff("link:*", FieldFidelity.NOT_COMPARABLE, null, null, DiffOutcome.NOT_COMPARABLE, NotComparableReasons.LINK)
        }
        return DiffReport(profileId, probePacks, entries, used.toList(), allowed.expired(today))
    }
}
