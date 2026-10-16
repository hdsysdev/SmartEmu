package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.ProbePack
import com.hddev.smartemu.capture.ProbePacks
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.capture.RedactionLint
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fidelity diff in CI: every capture in resources/capture/golden is merged per generation, the emulator is
 * configured from the profile, the probe packs run against it, and any deviation that docs/fidelity-allowed-deviations.json
 * does not allow fails the build. Real captures dropped in the directory are picked up with no change here. The
 * synthetic ones (see the README there) prove the pipeline only.
 */
class GoldenCaptureDiffTest {
    private val directory = File("src/androidUnitTest/resources/capture/golden")
    private val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    private val captures: List<Pair<File, CaptureRecord>> by lazy { files.map { it to CaptureJson.decode(it.readText()) } }
    private val allowed = AllowedDeviations.decode(File("../docs/fidelity-allowed-deviations.json").readText())

    /**
     * The curated profile a synthetic capture was run from, named in its file name; real captures name none, so their
     * generation is replayed on the generic profile, and whatever the emulator can't do without a curated base
     * (EF.CardSecurity of a PACE-CAM chip, say) shows as a deviation to fix or to allow.
     */
    private fun linkedProfileId(file: File): String? = Regex("^synthetic-(.+)-\\d+\\.json$").find(file.name)?.groupValues?.get(1)

    private fun baseOf(group: List<Pair<File, CaptureRecord>>): ChipProfile =
        group.firstNotNullOfOrNull { linkedProfileId(it.first) }?.let(ChipProfiles::byId) ?: ChipProfiles.default

    private fun generations(): Map<String, List<Pair<File, CaptureRecord>>> = captures.groupBy { it.second.fingerprint }

    @AfterTest
    fun tearDown() = MeasuredProfileRegistry.clear()

    @Test
    fun `the golden directory holds captures and a README`() {
        assertTrue(File(directory, "README.md").isFile)
        assertTrue(files.size >= 5, "only ${files.size} golden captures")
    }

    @Test
    fun `every golden capture decodes, is consistent and passes the redaction lint`() {
        captures.forEach { (file, record) ->
            assertTrue(CaptureFingerprint.isConsistent(record), "${file.name}: fingerprint")
            assertEquals(emptyList(), RedactionLint.check(file.readText()), file.name)
            assertEquals(null, record.aborted, "${file.name}: an aborted capture is not a golden one")
        }
    }

    @Test
    fun `synthetic captures are labelled synthetic in the file name and in the reader, and only those`() {
        captures.forEach { (file, record) ->
            val synthetic = file.name.startsWith("synthetic-")
            assertEquals(synthetic, record.reader.model.startsWith("SYNTHETIC"), "${file.name}: file name and reader model must agree")
            if (synthetic) assertTrue(record.reader.model.contains("not a phone"), file.name)
        }
        val readme = File(directory, "README.md").readText()
        captures.filter { it.first.name.startsWith("synthetic-") }.forEach { (file, _) ->
            assertTrue(readme.contains(file.name.removeSuffix(".json").replace(Regex("-[123]$"), "")), "${file.name} isn't described in the README")
        }
    }

    @Test
    fun `no non-allowed deviation between the emulator and the profile of any generation`() {
        val today = FidelityDiff.todayUtc()
        val problems = mutableListOf<String>()
        generations().forEach { (generation, group) ->
            val profile = ProfileMerger.merge(group.map { it.second }, id = "golden-$generation", chipProfileId = group.firstNotNullOfOrNull { linkedProfileId(it.first) })
            val packs = ProbePacks.packsOf(group.first().second)
            if (packs.isEmpty()) {
                problems += "${profile.id}: probe pack ${profile.probePack} is not a published pack"
                return@forEach
            }
            val report = MeasuredEmulator.with(profile, baseOf(group)) {
                FidelityDiff.run(profile, it.transport(), packs.first(), packs.drop(1), allowed, today)
            }
            println(report.toTable())
            if (!report.isClean) problems += report.toTable()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n\n"))
    }

    /**
     * The hold-out over every generation. The synthetic captures are the emulator's own output (see the README), so for
     * them MEASURED means the pipeline works and the emulator agrees with itself: it says nothing about any real chip.
     * That circularity is asserted, not hidden: on a synthetic generation the emulator's generic rules already answer
     * every withheld step ([HoldOutAward.needsReplay] is empty), whereas on a real chip they would not. Only a generation
     * made of real captures counts as evidence, and there is none in the repository yet.
     */
    @Test
    fun `hold-out over every generation with two captures or more, and nothing earned by one`() {
        val today = FidelityDiff.todayUtc()
        val problems = mutableListOf<String>()
        var syntheticMeasuredGenerations = 0
        var realMeasuredGenerations = 0
        generations().forEach { (generation, group) ->
            val open = mutableListOf<MeasuredEmulator>()
            val records = group.map { it.second }
            val result = try {
                HoldOutEvaluator.evaluate(
                    records, emulatorFor = { MeasuredEmulator(it, baseOf(group)).also(open::add).transport() },
                    id = "golden-$generation", chipProfileId = group.firstNotNullOfOrNull { linkedProfileId(it.first) }, allowed = allowed, today = today
                )
            } finally {
                open.forEach { it.close() }
            }
            if (records.distinctBy { it.captureId }.size < 2) {
                assertTrue(result.profile.measuredFields.isEmpty(), "generation $generation has one capture and must earn nothing")
                assertTrue(result.profile.needsMoreCaptures)
                return@forEach
            }
            val unallowed = result.award.rejected.filter { allowed.find(result.profile.id, it.key, today) == null }
            if (unallowed.isNotEmpty() || result.replay?.isClean != true) {
                problems += "generation $generation: rejected ${unallowed.map { it.key }}\n${result.replay?.toTable()}"
            }
            val synthetic = group.all { it.first.name.startsWith("synthetic-") }
            if (result.profile.measuredFields.isNotEmpty()) if (synthetic) syntheticMeasuredGenerations++ else realMeasuredGenerations++
            if (synthetic) {
                assertEquals(emptySet(), result.award.needsReplay, "generation $generation: a synthetic capture is the emulator's own answer, so the generic rules already give it")
                assertTrue(result.award.matchesDefault.isNotEmpty(), "generation $generation: the step hold-out ran")
            }
            println(
                "generation $generation (${if (synthetic) "SYNTHETIC" else "real"}): ${records.size} captures, ${result.profile.measuredFields.size} fields MEASURED " +
                    "(${result.award.matchesDefault.size} match the emulator default), held out ${result.heldOutCaptureId}"
            )
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n\n"))
        // The pipeline reaches MEASURED on the synthetic generations; that is a statement about the code, not about a chip
        assertTrue(syntheticMeasuredGenerations >= 1, "no synthetic generation has a MEASURED field that passed the hold-out rule")
        println("MEASURED generations: $syntheticMeasuredGenerations synthetic (pipeline only), $realMeasuredGenerations real")
    }

    @Test
    fun `the synthetic captures of one chip are one generation whatever the reader`() {
        val synthetic = captures.filter { it.first.name.startsWith("synthetic-") }
        // One generation per curated profile: three readers (one of them without extended length) and the same chip
        val perChip = synthetic.groupBy { linkedProfileId(it.first) }
        assertEquals(3, perChip.size)
        perChip.forEach { (chip, group) ->
            assertEquals(1, group.map { it.second.fingerprint }.distinct().size, "$chip: the readers split one chip into several generations")
        }
        val dePassport = perChip.getValue("de-passport-2017").map { it.second }
        assertEquals(3, dePassport.size)
        assertTrue(dePassport.map { it.observationDigest }.distinct().size >= 2, "a reader without extended length observed differently, yet is the same generation")
    }

    @Test
    fun `the packs the golden captures ran are published ones`() {
        captures.forEach { (file, record) -> assertTrue(ProbePacks.byRef(record.probePack) != null, "${file.name}: ${record.probePack}") }
        assertEquals(ProbePack.tierA.ref, captures.first().second.probePack)
    }
}
