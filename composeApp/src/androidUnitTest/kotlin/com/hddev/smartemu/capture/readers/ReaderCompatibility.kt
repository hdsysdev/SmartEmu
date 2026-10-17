package com.hddev.smartemu.capture.readers

import com.hddev.smartemu.PassportHceService
import com.hddev.smartemu.capture.profile.MeasuredEmulator
import com.hddev.smartemu.capture.profile.MeasuredProfile
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.DhGroup
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.SessionCipher
import com.hddev.smartemu.data.withChipProfile
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.CardServiceProtocolException

/**
 * Something a reader can't do, with where that is written down. A step that fails is called `reader unsupported` only if
 * a limit [appliesTo] the profile (and suite) AND [matchesFailure] the very exception the reader threw, so a passing run is
 * never relabelled and a failure of another kind stays a `fail`. A limit with no [source] isn't allowed.
 */
class ReaderLimit(
    /** The feature the reader lacks, as the matrix names it. */
    val feature: String,
    /** The reader's code or documentation that says so. Mandatory. */
    val source: String,
    val appliesTo: (JmrtdReader.Context) -> Boolean,
    val matchesFailure: (Throwable) -> Boolean
) {
    init {
        require(source.isNotBlank()) { "A reader limit without a source is a way to hide a failure: $feature" }
    }

    fun explains(context: JmrtdReader.Context, failure: Throwable): Boolean = appliesTo(context) && matchesFailure(failure)
}

/** What each reader can't do, kept beside the reader and reviewed when it updates. Rendered into docs/reader-compatibility.md. */
object KnownReaderLimits {
    val jmrtd: List<ReaderLimit> = listOf(
        ReaderLimit(
            feature = "PACE Integrated Mapping with AES-192",
            source = "JMRTD 0.8.8 PACEProtocol generates Integrated Mapping's nonce t as long as the encrypted nonce s (32 bytes for AES-192, " +
                "where ICAO 9303 part 11 wants 24); reproduced by PaceIntegrationTest \"JMRTD's integrated mapping with AES-192 is refused " +
                "for its overlong nonce t\", where a reader that follows ICAO 9303 completes the same suite on the same emulator",
            appliesTo = { context ->
                val oid = context.suite?.objectIdentifier
                oid != null && org.jmrtd.lds.PACEInfo.toMappingType(oid) == org.jmrtd.lds.PACEInfo.MappingType.IM &&
                    org.jmrtd.lds.PACEInfo.toCipherAlgorithm(oid) == "AES" && org.jmrtd.lds.PACEInfo.toKeyLength(oid) == 192
            },
            // The chip refused the reader's mapping-nonce step with 6A80, which JMRTD raises as a protocol exception
            matchesFailure = { e -> e is CardServiceProtocolException && (e.sw == 0x6A80 || (e.cause as? CardServiceException)?.sw == 0x6A80) }
        )
    )
}

/** The runs of every profile and every PACE variant, as the generator produces them and the baseline pins them. */
class ReaderResults(
    val profiles: List<ProfileRun>,
    /** Title of each profile, by id, for the table. */
    val titles: Map<String, String>,
    val variants: List<VariantRun>
)

/** The PACE variants the developer settings can ask for, one run per domain parameters, mapping and cipher. */
class VariantRun(val domain: String, val mapping: PaceMapping, val cipher: SessionCipher, val offered: String?, val outcome: StepOutcome) {
    /** The emulator can't offer every combination (Integrated Mapping on P-224, PACE-CAM on DH) and offers another suite. */
    val adapted: Boolean get() = offered != null && (!offered.startsWith("PACE-${mapping.abbreviation} ") || !offered.endsWith(cipher.displayName))
}

@Serializable
data class ReaderBaseline(
    val schemaVersion: Int = 1,
    val reader: String,
    /** Profile id to step to outcome (`pass`, `fail: ..`, `reader unsupported: ..`, `n/a`). */
    val profiles: Map<String, Map<String, String>>,
    /** "domain / mapping" to cipher to outcome. */
    val variants: Map<String, Map<String, String>>
)

/** Runs every profile and variant through the reader; the one place that knows how the emulator is configured for it. */
object ReaderCompatibilityRunner {
    const val READER = "JMRTD 0.8.8 + SCUBA 0.0.21 + Bouncy Castle 1.85"

    private val today = LocalDate(2026, 9, 30)

    private val holder = PassportData(
        passportNumber = "SPEC24681", dateOfBirth = LocalDate(1986, 3, 14), expiryDate = LocalDate(2035, 3, 13),
        issuingCountry = "SWE", nationality = "SWE", firstName = "Anna Maria", lastName = "Eriksson", gender = "F"
    )

    /** The profile's ready-made document, or the specimen holder on a document of its kind. */
    private fun documentFor(profile: ChipProfile): PassportData =
        PassportPresets.all.firstOrNull { it.id == "profile-${profile.id}" }?.build?.invoke(today) ?: holder.withChipProfile(profile)

    fun run(measured: List<MeasuredProfile> = emptyList(), curated: List<ChipProfile> = ChipProfiles.all): ReaderResults {
        val runs = mutableListOf<ProfileRun>()
        val titles = mutableMapOf<String, String>()
        for (profile in curated) {
            val hce = PassportHceService().also { it.onCreate() }
            try {
                runs += JmrtdReader(hce).run(profile, documentFor(profile))
                titles[profile.id] = profile.title
            } finally {
                PassportHceService.setSharedPassportData(null)
                hce.onDestroy()
            }
        }
        for (m in measured) {
            val base = curated.firstOrNull { it.id == m.chipProfileId } ?: ChipProfiles.default
            MeasuredEmulator(m, base).use { emulator ->
                val id = "measured:${m.id}"
                runs += JmrtdReader(emulator.service).run(emulator.profile, MeasuredEmulator.sampleDocument.withChipProfile(emulator.profile), id)
                titles[id] = "Measured, ${m.sampleCount} captures" + (m.chipProfileId?.let { ", linked to $it" } ?: "")
            }
        }
        return ReaderResults(runs, titles, runVariants())
    }

    private fun runVariants(): List<VariantRun> {
        val document = PassportData(
            passportNumber = "SPECI2021", dateOfBirth = LocalDate(1965, 3, 12), expiryDate = LocalDate(2035, 10, 1),
            issuingCountry = "NLD", nationality = "NLD", firstName = "Willeke Liselotte", lastName = "De Bruijn", gender = "F",
            accessControl = AccessControl.BAC_AND_PACE
        )
        val domains: List<Pair<String, (PassportData) -> PassportData>> =
            EcCurve.entries.map { c -> c.displayName to { d: PassportData -> d.copy(paceCurve = c) } } +
                DhGroup.entries.map { g -> g.displayName to { d: PassportData -> d.copy(paceDhGroup = g) } }
        val hce = PassportHceService().also { it.onCreate() }
        val reader = JmrtdReader(hce)
        try {
            return domains.flatMap { (name, configure) ->
                PaceMapping.entries.flatMap { mapping ->
                    SessionCipher.entries.map { cipher ->
                        val data = configure(document.copy(paceMapping = mapping, paceCipher = cipher))
                        val result = reader.paceOfFirstSuite(ChipProfiles.default, data)
                        hce.onDeactivated(android.nfc.cardemulation.HostApduService.DEACTIVATION_LINK_LOSS)
                        VariantRun(name, mapping, cipher, result?.first, result?.second ?: StepOutcome(StepKind.FAIL, "EF.CardAccess offers no PACE suite"))
                    }
                }
            }
        } finally {
            PassportHceService.setSharedPassportData(null)
            hce.onDestroy()
        }
    }
}

/** The page's generated section, the baseline, and the regression check. */
object ReaderMatrix {
    const val DOC_FILE = "docs/reader-compatibility.md"
    const val BASELINE_FILE = "docs/reader-compatibility.json"
    const val BEGIN_MARKER = "<!-- BEGIN GENERATED: reader matrix (ReaderMatrix.render; regenerate, do not edit between the markers) -->"
    const val END_MARKER = "<!-- END GENERATED: reader matrix -->"

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

    private fun variantKey(run: VariantRun) = "${run.domain} / ${run.mapping.abbreviation}"

    fun baselineOf(results: ReaderResults): ReaderBaseline = ReaderBaseline(
        reader = ReaderCompatibilityRunner.READER,
        profiles = results.profiles.sortedBy { it.profileId }.associate { run -> run.profileId to run.steps.mapValues { it.value.text } },
        variants = results.variants.groupBy { variantKey(it) }.toSortedMap().mapValues { (_, runs) -> runs.associate { it.cipher.name to it.outcome.text } }
    )

    fun encodeBaseline(baseline: ReaderBaseline): String = json.encodeToString(ReaderBaseline.serializer(), baseline) + "\n"

    fun decodeBaseline(text: String): ReaderBaseline = json.decodeFromString(ReaderBaseline.serializer(), text)

    /**
     * What got worse than [baseline]: a step that passed and no longer does, a profile or variant that was pinned and was
     * not run, and a profile or variant the baseline doesn't know (so it can't be pinned). Empty means no regression.
     * A step that failed or was unsupported in the baseline may change either way.
     */
    fun regressions(baseline: ReaderBaseline, results: ReaderResults): List<String> {
        val problems = mutableListOf<String>()
        val current = baselineOf(results)
        baseline.profiles.forEach { (id, steps) ->
            val now = current.profiles[id]
            if (now == null) {
                problems += "$id: pinned in the baseline but not run (a removed profile: update the baseline)"
                return@forEach
            }
            steps.filterValues { it == StepKind.PASS.word }.keys.forEach { step ->
                val outcome = now[step]
                if (outcome != StepKind.PASS.word) problems += "$id: step '$step' passed before and now is ${outcome ?: "missing"}"
            }
        }
        baseline.variants.forEach { (key, ciphers) ->
            val now = current.variants[key]
            ciphers.filterValues { it == StepKind.PASS.word }.keys.forEach { cipher ->
                val outcome = now?.get(cipher)
                if (outcome != StepKind.PASS.word) problems += "PACE variant $key $cipher passed before and now is ${outcome ?: "missing"}"
            }
        }
        // A failure is a finding to triage: one the baseline doesn't already record is new
        current.profiles.forEach { (id, steps) ->
            steps.filterValues { it.startsWith(StepKind.FAIL.word) }.forEach { (step, outcome) ->
                if (baseline.profiles[id]?.get(step) != outcome) problems += "$id: new failure in step '$step': $outcome"
            }
        }
        (current.profiles.keys - baseline.profiles.keys).forEach { problems += "$it: not in the baseline (a new profile is pinned by updating it)" }
        (current.variants.keys - baseline.variants.keys).forEach { problems += "PACE variant $it: not in the baseline" }
        return problems
    }

    /** The generated part of docs/reader-compatibility.md. */
    fun render(results: ReaderResults): String = buildString {
        val notes = linkedMapOf<String, Int>()
        fun cell(outcome: StepOutcome): String {
            val note = outcome.evidence?.let { notes.getOrPut(it) { notes.size + 1 } }
            return when (outcome.kind) {
                StepKind.PASS -> "pass"
                StepKind.NOT_APPLICABLE -> "n/a"
                StepKind.UNSUPPORTED -> "reader unsupported: ${outcome.detail}" + (note?.let { " [$it]" } ?: "")
                StepKind.FAIL -> "**fail: ${outcome.detail}**"
            }
        }
        appendLine(BEGIN_MARKER)
        appendLine("<!-- Written by ReaderCompatibilityTest from ${ReaderCompatibilityRunner.READER}; regenerate with UPDATE_GENERATED_DOCS=1 -->")
        appendLine()
        appendLine("**GENERATED. Do not edit between the markers.** Reader: ${ReaderCompatibilityRunner.READER}. Pinned in [reader-compatibility.json](reader-compatibility.json).")
        appendLine()
        appendLine("### Known limits of this reader")
        appendLine()
        appendLine("A step is labelled `reader unsupported` only where one of these limits explains the exception the reader actually threw; each names its source. Anything else that breaks is a `fail`.")
        appendLine()
        KnownReaderLimits.jmrtd.forEach { appendLine("- **${it.feature}**. Source: ${it.source}.") }
        appendLine()
        appendLine("### Profiles")
        appendLine()
        appendLine("Each profile's chip is unlocked with every access method it offers (BAC, and each PACE suite of EF.CardAccess in order, with the MRZ as the password). With the first that works the reader reads EF.COM, EF.SOD and DG1, checks passive authentication against the test CSCA, and runs Chip Authentication and Active Authentication where the profile has them.")
        appendLine()
        appendLine("| Profile | BAC | PACE suites | EF.COM | EF.SOD | DG1 | Passive auth | Chip Auth | Active Auth | JMRTD+SCUBA+BC total |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        results.profiles.sortedBy { it.profileId }.forEach { run ->
            val pace = run.steps.filterKeys { it.startsWith(Steps.PACE_PREFIX) }
            val paceCell = if (pace.isEmpty()) "n/a" else pace.entries.joinToString("<br>") { (key, outcome) -> "${key.removePrefix(Steps.PACE_PREFIX)}: ${cell(outcome)}" }
            val all = run.steps.values
            val total = when {
                all.any { it.kind == StepKind.FAIL } -> "fail"
                all.any { it.kind == StepKind.UNSUPPORTED } -> "pass, with reader-unsupported suites"
                else -> "pass"
            }
            val title = results.titles[run.profileId]?.let { " ($it)" }.orEmpty()
            appendLine(
                "| `${run.profileId}`$title | ${cell(run.steps[Steps.BAC] ?: StepOutcome.notApplicable)} | $paceCell | " +
                    Steps.columns.drop(1).joinToString(" | ") { cell(run.steps[it] ?: StepOutcome.notApplicable) } + " | $total |"
            )
        }
        appendLine()
        appendLine("### PACE variants the developer settings can ask for")
        appendLine()
        appendLine("The emulator can be set to any ICAO PACE domain parameters, mapping and cipher. The reader runs PACE with the MRZ on the first suite EF.CardAccess then offers, and reads DG1. `pass` means all four ciphers pass. Where the emulator can't offer the requested combination it offers another suite (Integrated Mapping on NIST P-224, PACE-CAM on a DH group, PACE-CAM with 3DES), and the cell says which: that is the emulator's choice, not the reader's.")
        appendLine()
        appendLine("| Domain parameters | GM | IM | CAM |")
        appendLine("|---|---|---|---|")
        results.variants.groupBy { it.domain }.forEach { (domain, runs) ->
            val cells = PaceMapping.entries.map { mapping ->
                val ofMapping = runs.filter { it.mapping == mapping }
                val lines = ofMapping.flatMap { run ->
                    buildList {
                        if (run.adapted) add("${run.cipher.displayName}: emulator offers ${run.offered}")
                        if (run.outcome.kind != StepKind.PASS) add("${run.cipher.displayName}: ${cell(run.outcome)}")
                    }
                }
                if (lines.isEmpty()) "pass" else lines.joinToString("<br>")
            }
            appendLine("| $domain | ${cells.joinToString(" | ")} |")
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("### Evidence for each `reader unsupported`")
            appendLine()
            notes.forEach { (text, number) -> appendLine("$number. The exception the reader threw: `$text`") }
        }
        appendLine()
        appendLine(END_MARKER)
    }

    /** [page] with the generated section replaced, or appended; the hand-written header and rules stay. */
    fun inject(page: String, generated: String): String {
        val start = page.indexOf(BEGIN_MARKER)
        val stop = page.indexOf(END_MARKER)
        return if (start >= 0 && stop > start) page.substring(0, start) + generated.trimEnd('\n') + page.substring(stop + END_MARKER.length)
        else page.trimEnd('\n') + "\n\n" + generated
    }
}
