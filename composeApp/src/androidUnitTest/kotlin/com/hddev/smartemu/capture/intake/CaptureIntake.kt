package com.hddev.smartemu.capture.intake

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.capture.RedactionLint
import com.hddev.smartemu.capture.profile.AllowedDeviations
import com.hddev.smartemu.capture.profile.ChipProfileEvidenceDoc
import com.hddev.smartemu.capture.profile.CoverageMatrix
import com.hddev.smartemu.capture.profile.DiffReport
import com.hddev.smartemu.capture.profile.FidelityBadge
import com.hddev.smartemu.capture.profile.FidelityDiff
import com.hddev.smartemu.capture.profile.HoldOutEvaluator
import com.hddev.smartemu.capture.profile.MeasuredEmulator
import com.hddev.smartemu.capture.profile.MeasuredProfile
import com.hddev.smartemu.capture.profile.MeasuredProfileJson
import com.hddev.smartemu.capture.profile.ProfileMerger
import com.hddev.smartemu.capture.profile.ProfileRepository
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import kotlinx.datetime.LocalDate
import kotlinx.serialization.descriptors.SerialDescriptor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** What the maintainer's intake is asked to do; see [CaptureIntake]. */
class IntakeOptions(
    /** The folder the contributors' capture files were collected in (a Drive or Slack download). */
    val inputDir: File,
    /** The restricted per-capture store, outside the repository working tree. */
    val storeDir: File,
    /** The repository root: `profiles/` and `docs/` are under it. */
    val repoDir: File,
    /** False (the default) is a dry run: everything is checked and reported, nothing is written or moved. */
    val apply: Boolean = false,
    /** Generation (fingerprint) to the curated profile id its profile links to. */
    val links: Map<String, String> = emptyMap(),
    val today: LocalDate = FidelityDiff.todayUtc()
) {
    val profilesDir: File get() = File(repoDir, "profiles")
    val pendingDir: File get() = File(profilesDir, "pending")
    val docsDir: File get() = File(repoDir, "docs")
}

/** One reason a file was rejected. The text is fixed wording and a path through the schema, never the file's own text. */
data class RejectionReason(val rule: String, val path: String, val detail: String) {
    override fun toString(): String = if (path.isEmpty()) "$rule: $detail" else "$rule $path: $detail"
}

class Rejection(val file: String, val reasons: List<RejectionReason>)

class Acceptance(val file: String, val record: CaptureRecord, val alreadyStored: Boolean)

/** Where a generation's profile goes: the shared set, or held back for a steward. */
enum class Placement { SHARED, PENDING }

class GenerationOutcome(
    val profile: MeasuredProfile,
    val placement: Placement,
    val file: File,
    /** The profile file differs from what was on disk (or there was none). */
    val changed: Boolean,
    /** Hold-out summary, or why there is none. */
    val holdOut: String,
    /** The fidelity diff of the merged profile against the emulator replaying it, or null if it could not run. */
    val diff: DiffReport?,
    val diffNote: String?
) {
    val badge: FidelityBadge get() = FidelityBadge.of(profile)
}

class DocChange(val file: File, val changed: Boolean)

class IntakeResult(
    val accepted: List<Acceptance>,
    val rejected: List<Rejection>,
    val generations: List<GenerationOutcome>,
    val docs: List<DocChange>,
    val profileLoadErrors: List<String>,
    val applied: Boolean,
    val report: String
) {
    /** True if a diff shows a deviation the allowed-deviation file doesn't allow, or a run could not complete. */
    val hasDeviations: Boolean get() = generations.any { it.diff?.isClean == false }

    /** True if no file was rejected and no diff shows a deviation. */
    val clean: Boolean get() = rejected.isEmpty() && !hasDeviations

    /** The last line of the report's verdict: DEVIATIONS (blocks a commit), REJECTIONS (files were quarantined) or CLEAN. */
    val status: String get() = when {
        hasDeviations -> "DEVIATIONS"
        rejected.isNotEmpty() -> "REJECTIONS"
        else -> "CLEAN"
    }
}

/** The inputs of an intake can't be used (a store inside the repository, a missing folder). */
class IntakeSetupException(message: String) : Exception(message)

/**
 * The maintainer's intake of contributed capture files. Runs the one redaction lint ([RedactionLint]), the strict
 * decoder ([CaptureJson.decode]) and the fingerprint check ([CaptureFingerprint.isConsistent]) the app and the docs
 * describe, so there is no second implementation of any rule. Then:
 *
 *  - a file that fails is rejected with its violation list (fixed wording and schema paths, never the file's text) and,
 *    on apply, quarantined in `rejected/` of the inbox with a `.reason.txt`;
 *  - a file that passes goes into the restricted per-capture store, `<store>/<generation>/<capture id>.json`, which is
 *    never inside the repository and never committed;
 *  - the stored captures are merged per generation ([ProfileMerger]) and checked by the hold-out rule and the fidelity
 *    diff. A generation of two captures or more goes to `profiles/<id>.json`. A single capture stays in
 *    `profiles/pending/` (not part of the shared set, not committed) until a second capture arrives or a steward moves
 *    it into `profiles/` (docs/profile-steward.md);
 *  - docs/chip-profile-evidence.measured.md, docs/coverage-matrix.md and docs/coverage-matrix.json are regenerated from the
 *    shared set.
 */
class CaptureIntake(private val options: IntakeOptions) {

    fun run(): IntakeResult {
        checkSetup()
        val store = CaptureStore(options.storeDir)
        val (accepted, rejected) = validateInbox(store)

        if (options.apply) {
            accepted.filter { !it.alreadyStored }.forEach { store.add(it.record) }
            rejected.forEach(::quarantine)
        }

        val records = (store.all() + accepted.filter { !it.alreadyStored }.map { it.record }).distinctBy { it.captureId }
        val shared = ProfileRepository(options.profilesDir).load()
        val pending = ProfileRepository(options.pendingDir).load()
        val loadErrors = (shared.errors + pending.errors).map { "${it.file}: ${it.message}" }
        val generations = mergeGenerations(records, shared.profiles, pending.profiles)

        if (options.apply) writeProfiles(generations)

        val finalShared = sharedSet(generations, shared.profiles)
        val docs = regenerateDocs(finalShared)
        val report = Report(options, accepted, rejected, generations, docs, loadErrors, store).render()
        return IntakeResult(accepted, rejected, generations, docs, loadErrors, options.apply, report)
    }

    private fun checkSetup() {
        if (!options.inputDir.isDirectory) throw IntakeSetupException("The inbox ${options.inputDir} is not a directory")
        if (!options.repoDir.isDirectory) throw IntakeSetupException("The repository ${options.repoDir} is not a directory")
        val store = options.storeDir.canonicalFile
        val repo = options.repoDir.canonicalFile
        if (store == repo || store.startsWith(repo)) {
            throw IntakeSetupException("The store ${options.storeDir} is inside the repository ${options.repoDir}: per-capture records are never kept there")
        }
        if (options.inputDir.canonicalFile.let { it == store || store.startsWith(it) || it.startsWith(store) }) {
            throw IntakeSetupException("The store and the inbox must be separate folders")
        }
    }

    // --- validation -----------------------------------------------------------------------------------------------

    private fun validateInbox(store: CaptureStore): Pair<List<Acceptance>, List<Rejection>> {
        val files = options.inputDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }
        val accepted = mutableListOf<Acceptance>()
        val rejected = mutableListOf<Rejection>()
        val seen = mutableMapOf<String, String>()
        for (file in files) {
            if (file.name.endsWith(REASON_SUFFIX)) continue
            val reasons = mutableListOf<RejectionReason>()
            val record = validate(file, reasons)
            if (record != null) {
                val stored = store.textOf(record)
                val encoded = CaptureJson.encode(record)
                when {
                    stored != null && stored != encoded + "\n" ->
                        reasons += RejectionReason("DUPLICATE_ID", "captureId", "A capture with this id is already stored with other contents")
                    seen[record.captureId]?.let { it != encoded } == true ->
                        reasons += RejectionReason("DUPLICATE_ID", "captureId", "Another file in this inbox has this id with other contents")
                    else -> {
                        seen[record.captureId] = encoded
                        // The same capture twice (same id, same contents) is not a second capture
                        val alreadyThere = stored != null || accepted.any { it.record.captureId == record.captureId }
                        accepted += Acceptance(file.name, record, alreadyStored = alreadyThere)
                    }
                }
            }
            if (reasons.isNotEmpty()) rejected += Rejection(file.name, reasons)
        }
        return accepted to rejected
    }

    /** The record if [file] may be ingested; otherwise null, with the reasons added to [reasons]. */
    private fun validate(file: File, reasons: MutableList<RejectionReason>): CaptureRecord? {
        if (!file.name.endsWith(".json")) {
            reasons += RejectionReason("NOT_JSON_FILE", "", "Only .json capture files are ingested")
            return null
        }
        if (file.length() > MAX_BYTES) {
            reasons += RejectionReason("TOO_LARGE", "", "More than $MAX_BYTES bytes: a capture is far smaller")
            return null
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            reasons += RejectionReason("UNREADABLE", "", "The file could not be read as UTF-8 text")
            return null
        }
        val violations = RedactionLint.check(text)
        violations.forEach { reasons += RejectionReason(it.rule.name, SafePaths.sanitise(it.path), it.detail) }
        if (violations.isNotEmpty()) return null
        // The lint decoded it already; decoding again is the strict gate the stored form comes from
        val record = try {
            CaptureJson.decode(text)
        } catch (e: Exception) {
            reasons += RejectionReason("SCHEMA_MISMATCH", "", "Does not decode strictly as a capture record")
            return null
        }
        if (!CaptureFingerprint.isConsistent(record)) {
            reasons += RejectionReason("FINGERPRINT_MISMATCH", "fingerprint", "Empty, or not the one the contents give")
        }
        if (record.aborted != null) {
            reasons += RejectionReason("ABORTED_CAPTURE", "aborted", "The run did not complete; capture again")
        }
        val unknownPacks = (listOf(record.probePack) + record.additionalProbePacks).filter { ProbePacks.byRef(it) == null }
        if (unknownPacks.isNotEmpty()) {
            reasons += RejectionReason("UNKNOWN_PROBE_PACK", "probePack", "Names a probe pack or version that is not published")
        }
        return record.takeIf { reasons.isEmpty() }
    }

    private fun quarantine(rejection: Rejection) {
        val directory = File(options.inputDir, REJECTED_DIR).also { it.mkdirs() }
        val source = File(options.inputDir, rejection.file)
        var target = File(directory, rejection.file)
        var counter = 1
        while (target.exists()) target = File(directory, "${rejection.file}.${counter++}")
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File(directory, target.name + REASON_SUFFIX).writeText(
            buildString {
                appendLine("Rejected by capture intake. The file was not stored and is not merged into any profile.")
                appendLine("The file may still hold personal data: do not forward it, and delete it once the contributor has been told.")
                appendLine("Do not edit it to make it pass: ask the contributor to capture again.")
                appendLine()
                rejection.reasons.forEach { appendLine(it.toString()) }
            }
        )
    }

    // --- merge ----------------------------------------------------------------------------------------------------

    private fun mergeGenerations(
        records: List<CaptureRecord>,
        shared: List<MeasuredProfile>,
        pending: List<MeasuredProfile>
    ): List<GenerationOutcome> {
        val allowed = options.docsDir.resolve("fidelity-allowed-deviations.json").takeIf { it.isFile }
            ?.let { AllowedDeviations.decode(it.readText()) } ?: AllowedDeviations.none
        return records.groupBy { it.fingerprint }.toSortedMap().map { (generation, group) ->
            val ordered = group.sortedWith(compareBy({ it.captureMonth }, { it.captureId }))
            val existingShared = shared.firstOrNull { it.generation == generation }
            val existingPending = pending.firstOrNull { it.generation == generation }
            val id = existingShared?.id ?: existingPending?.id ?: "generation-$generation"
            val chipProfileId = options.links[generation] ?: existingShared?.chipProfileId ?: existingPending?.chipProfileId
            val base: ChipProfile = chipProfileId?.let { wanted -> ChipProfiles.all.firstOrNull { it.id == wanted } } ?: ChipProfiles.default

            val open = mutableListOf<MeasuredEmulator>()
            val result = try {
                HoldOutEvaluator.evaluate(
                    ordered, emulatorFor = { MeasuredEmulator(it, base).also(open::add).transport() },
                    id = id, chipProfileId = chipProfileId, allowed = allowed, today = options.today
                )
            } finally {
                open.forEach { it.close() }
            }
            val profile = result.profile
            val placement = if (profile.sampleCount >= ProfileMerger.MIN_CAPTURES_FOR_MEASURED || existingShared != null) Placement.SHARED else Placement.PENDING
            val file = File(if (placement == Placement.SHARED) options.profilesDir else options.pendingDir, "$id.json")
            val text = MeasuredProfileJson.encode(profile) + "\n"
            val changed = !file.isFile || file.readText() != text

            val holdOut = when {
                result.heldOutCaptureId == null -> result.award.note ?: "n=1: nothing can be held out"
                else -> "held out ${result.heldOutCaptureId}: ${result.award.awarded.size} fields MEASURED, ${result.award.rejected.size} rejected" +
                    (result.award.note?.let { " ($it)" } ?: "")
            }
            val packs = ProbePacks.packsOf(ordered.first())
            val (diff, diffNote) = if (packs.isEmpty()) {
                null to "its probe pack is not published, so the diff did not run"
            } else {
                MeasuredEmulator.with(profile, base) {
                    FidelityDiff.run(profile, it.transport(), packs.first(), packs.drop(1), allowed, options.today)
                } to null
            }
            GenerationOutcome(profile, placement, file, changed, holdOut, diff, diffNote)
        }
    }

    private fun writeProfiles(generations: List<GenerationOutcome>) {
        generations.forEach { outcome ->
            val repository = ProfileRepository(outcome.file.parentFile)
            repository.save(outcome.profile)
            // A profile released from pending, or one a second capture lifted into the shared set, leaves pending
            if (outcome.placement == Placement.SHARED) File(options.pendingDir, "${outcome.profile.id}.json").delete()
        }
    }

    /** The shared set after this run: what `profiles/` holds, with each generation this run touched as it now stands. */
    private fun sharedSet(generations: List<GenerationOutcome>, shared: List<MeasuredProfile>): List<MeasuredProfile> {
        val updated = generations.filter { it.placement == Placement.SHARED }.associateBy { it.profile.generation }
        val kept = shared.filter { it.generation !in updated }
        return (kept + updated.values.map { it.profile }).sortedBy { it.id }
    }

    // --- generated docs -------------------------------------------------------------------------------------------

    private fun regenerateDocs(shared: List<MeasuredProfile>): List<DocChange> {
        val curated = ChipProfiles.all
        val evidence = File(options.repoDir, ChipProfileEvidenceDoc.FILE)
        val page = File(options.repoDir, CoverageMatrix.DOC_FILE)
        val json = File(options.repoDir, CoverageMatrix.JSON_FILE)
        val renders = listOf(
            evidence to ChipProfileEvidenceDoc.render(shared),
            page to CoverageMatrix.inject(page.takeIf { it.isFile }?.readText() ?: "# Coverage matrix\n", CoverageMatrix.render(shared, curated)),
            json to CoverageMatrix.renderJson(shared, curated)
        )
        return renders.map { (file, text) ->
            val changed = !file.isFile || file.readText() != text
            if (options.apply && changed) {
                file.parentFile.mkdirs()
                file.writeText(text)
            }
            DocChange(file, changed)
        }
    }

    companion object {
        const val REJECTED_DIR = "rejected"
        const val REASON_SUFFIX = ".reason.txt"
        const val MAX_BYTES = 1_000_000L
    }
}

/**
 * The restricted per-capture store: `<store>/<generation>/<capture id>.json`, readable by the maintainer only. It holds
 * the canonical encoding of each accepted record, never the submitted text.
 */
class CaptureStore(val directory: File) {
    private fun fileOf(record: CaptureRecord) = File(File(directory, record.fingerprint), "${record.captureId}.json")

    /** The stored text of [record]'s capture id (whatever generation it is stored under), or null. */
    fun textOf(record: CaptureRecord): String? = directory.listFiles { f -> f.isDirectory }.orEmpty()
        .map { File(it, "${record.captureId}.json") }.firstOrNull { it.isFile }?.readText()

    fun all(): List<CaptureRecord> = directory.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        .flatMap { generation -> generation.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name } }
        .mapNotNull { runCatching { CaptureJson.decode(it.readText()) }.getOrNull() }

    fun count(): Int = all().size

    fun add(record: CaptureRecord) {
        val file = fileOf(record)
        restricted(file.parentFile)
        file.writeText(CaptureJson.encode(record) + "\n")
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            // A file system with no POSIX permissions: the folder's access list is the control
        }
    }

    private fun restricted(directory: File) {
        val missing = generateSequence(directory) { it.parentFile }.takeWhile { !it.exists() }.toList().reversed()
        directory.mkdirs()
        missing.forEach {
            try {
                Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rwx------"))
            } catch (e: UnsupportedOperationException) {
                // See add()
            }
        }
    }
}

/**
 * Paths in a violation come from the JSON's own keys, which in a leaky submission can be the leak. Only names the
 * capture schema has are kept; anything else is shown as `?`, so a report never repeats a submission's text.
 */
internal object SafePaths {
    private val known: Set<String> by lazy {
        val names = mutableSetOf<String>()
        collect(CaptureRecord.serializer().descriptor, names, mutableSetOf())
        names
    }

    private fun collect(descriptor: SerialDescriptor, names: MutableSet<String>, seen: MutableSet<String>) {
        if (!seen.add(descriptor.serialName)) return
        for (i in 0 until descriptor.elementsCount) {
            names += descriptor.getElementName(i)
            collect(descriptor.getElementDescriptor(i), names, seen)
        }
    }

    fun sanitise(path: String): String = path.split('.').joinToString(".") { segment ->
        val name = segment.replace(Regex("\\[\\d*]"), "")
        val suffix = segment.removePrefix(name)
        if (name.isEmpty() || name in known) segment else "?" + if (suffix.matches(Regex("(\\[\\d*])+"))) suffix else ""
    }
}
