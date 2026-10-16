package com.hddev.smartemu.capture.intake

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.profile.ChipProfileEvidenceDoc
import com.hddev.smartemu.capture.profile.CoverageMatrix
import com.hddev.smartemu.capture.profile.ProfileRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The maintainer's intake, on the synthetic golden captures: de-passport-2017 has two captures of one generation and a
 * third (a reader that can't send extended-length commands) of another, so both the merged and the single-capture cases
 * have inputs. Every test uses its own inbox, store and repository folders under a temp directory.
 */
class CaptureIntakeTest {
    private val golden = File("src/androidUnitTest/resources/capture/golden")
    private val root: File = Files.createTempDirectory("intake-test").toFile()
    private val inbox = File(root, "inbox").also { it.mkdirs() }
    private val store = File(root, "captures-store")
    private val repo = File(root, "repo").also { File(it, "profiles").mkdirs(); File(it, "docs").mkdirs() }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun goldenText(name: String): String = File(golden, name).readText()

    private fun recordOf(name: String): CaptureRecord = CaptureJson.decode(goldenText(name))

    private fun submit(name: String, text: String = goldenText(name)) = File(inbox, name).also { it.writeText(text) }

    private fun intake(apply: Boolean, links: Map<String, String> = emptyMap()) =
        CaptureIntake(IntakeOptions(inbox, store, repo, apply, links)).run()

    private val generationAB get() = recordOf("synthetic-de-passport-2017-1.json").fingerprint
    private val generationC get() = recordOf("synthetic-de-passport-2017-3.json").fingerprint

    private fun storedFiles(): List<File> = store.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `the golden captures of one generation are accepted, stored and merged into profiles`() {
        submit("synthetic-de-passport-2017-1.json")
        submit("synthetic-de-passport-2017-2.json")

        val result = intake(apply = true, links = mapOf(generationAB to "de-passport-2017"))

        assertEquals(2, result.accepted.size)
        assertTrue(result.rejected.isEmpty())
        assertEquals(2, storedFiles().size)
        storedFiles().forEach {
            assertEquals(generationAB, it.parentFile.name)
            assertEquals(it.name.removeSuffix(".json"), CaptureJson.decode(it.readText()).captureId)
        }
        val profile = ProfileRepository(File(repo, "profiles")).profiles().single()
        assertEquals("generation-$generationAB", profile.id)
        assertEquals("de-passport-2017", profile.chipProfileId)
        assertEquals(2, profile.sampleCount)
        assertFalse(profile.needsReview)
        assertTrue(profile.measuredFields.isNotEmpty(), "two captures hold one out: some field is measured")
        assertTrue(File(repo, "profiles/pending").list().isNullOrEmpty())
        // What the intake writes into profiles/ is what CI's profile lint accepts
        assertEquals(emptyList(), com.hddev.smartemu.capture.profile.ProfileLint.check(File(repo, "profiles/${profile.id}.json").readText()))
        assertTrue(result.generations.single().diff!!.isClean, result.generations.single().diff!!.toTable())
        assertTrue(result.clean)
        assertTrue(result.report.contains("Accepted: 2"))
    }

    @Test
    fun `the generated docs are rewritten from the shared profiles`() {
        submit("synthetic-de-passport-2017-1.json")
        submit("synthetic-de-passport-2017-2.json")

        intake(apply = true, links = mapOf(generationAB to "de-passport-2017"))

        val evidence = File(repo, ChipProfileEvidenceDoc.FILE).readText()
        assertTrue(evidence.contains("`generation-$generationAB`"))
        val page = File(repo, CoverageMatrix.DOC_FILE).readText()
        assertTrue(page.contains("| - | DEU | passport (2017 design) | `de-passport-2017` | Measured (n=2) (thin) | 2 |"), page)
        assertTrue(File(repo, CoverageMatrix.JSON_FILE).readText().contains("\"profileId\": \"de-passport-2017\""))
    }

    @Test
    fun `a dry run reports and writes nothing`() {
        val file = submit("synthetic-de-passport-2017-1.json")
        val bad = submit("bad.json", goldenText("synthetic-de-passport-2017-2.json").replace("\"source\"", "\"extra\": 1, \"source\""))

        val result = intake(apply = false)

        assertEquals(1, result.accepted.size)
        assertEquals(1, result.rejected.size)
        assertFalse(store.exists())
        assertTrue(File(repo, "profiles").list().isNullOrEmpty())
        assertTrue(File(repo, "docs").list().isNullOrEmpty())
        assertTrue(file.isFile && bad.isFile, "nothing is moved in a dry run")
        assertFalse(File(inbox, "rejected").exists())
        assertTrue(result.report.startsWith("Capture intake (DRY RUN"))
    }

    @Test
    fun `a file with an MRZ line in any string is rejected, quarantined, and the report never repeats it`() {
        val mrz = "P<D<<MUSTERMANN<<ERIKA<<<<<<<<<<<<<<<<<<<<<<<<"
        val text = goldenText("synthetic-de-passport-2017-1.json").replace("SYNTHETIC reader A (JVM emulator not a phone)", mrz)
        val file = submit("erika.json", text)

        val result = intake(apply = true)

        val rejection = result.rejected.single()
        assertTrue(rejection.reasons.any { it.rule == "MRZ_PATTERN" && it.path == "reader.model" }, rejection.reasons.toString())
        assertTrue(result.accepted.isEmpty())
        assertFalse(storedFiles().isNotEmpty())
        assertFalse(file.exists())
        assertTrue(File(inbox, "rejected/erika.json").isFile)
        val reason = File(inbox, "rejected/erika.json.reason.txt").readText()
        assertTrue(reason.contains("MRZ_PATTERN reader.model"))
        listOf("MUSTERMANN", "ERIKA", "P<D<<").forEach {
            assertFalse(result.report.contains(it), "the report repeats $it")
            assertFalse(reason.contains(it), "the reason file repeats $it")
        }
        assertFalse(result.clean)
    }

    @Test
    fun `a file with a field outside the schema is rejected, even when the key is itself the leak`() {
        val withField = goldenText("synthetic-de-passport-2017-1.json").replace("\"source\"", "\"holderName\": \"ERIKA MUSTERMANN\", \"source\"")
        val withLeakyKey = goldenText("synthetic-de-passport-2017-2.json").replace("\"source\"", "\"ERIKAMUSTERMANN\": \"x\", \"source\"")
        submit("a.json", withField)
        submit("b.json", withLeakyKey)

        val result = intake(apply = true)

        assertEquals(listOf("a.json", "b.json"), result.rejected.map { it.file })
        result.rejected.forEach { r -> assertTrue(r.reasons.any { it.rule == "SCHEMA_MISMATCH" }, r.reasons.toString()) }
        listOf("ERIKA", "MUSTERMANN", "holderName").forEach { assertFalse(result.report.contains(it), "the report repeats $it") }
        assertTrue(File(inbox, "rejected/a.json.reason.txt").isFile)
        assertTrue(File(inbox, "rejected/b.json.reason.txt").isFile)
        assertEquals(0, storedFiles().size)
    }

    @Test
    fun `an unknown schema version, a bad fingerprint, a non-JSON file and a non-capture are rejected with their own reasons`() {
        val tampered = recordOf("synthetic-de-passport-2017-1.json").let { it.copy(fingerprint = "0000000000000000") }
        submit("tampered.json", CaptureJson.encode(tampered))
        submit("v9.json", goldenText("synthetic-de-passport-2017-2.json").replace(Regex("\"schemaVersion\": \\d+"), "\"schemaVersion\": 9"))
        submit("notes.txt", "hello")
        submit("garbage.json", "{ not json")

        val result = intake(apply = false)

        val byFile = result.rejected.associate { it.file to it.reasons.map { r -> r.rule } }
        assertTrue("FINGERPRINT_MISMATCH" in byFile.getValue("tampered.json"))
        assertTrue("SCHEMA_VERSION" in byFile.getValue("v9.json"))
        assertEquals(listOf("NOT_JSON_FILE"), byFile.getValue("notes.txt"))
        assertTrue("MALFORMED_JSON" in byFile.getValue("garbage.json"))
    }

    @Test
    fun `an aborted capture is not ingested`() {
        val base = recordOf("synthetic-de-passport-2017-1.json")
        val aborted = base.copy(aborted = com.hddev.smartemu.capture.AbortReason.TAG_LOST).let { it.copy(fingerprint = CaptureFingerprint.of(it)) }
        submit("aborted.json", CaptureJson.encode(aborted))

        val result = intake(apply = false)

        assertEquals("ABORTED_CAPTURE", result.rejected.single().reasons.single().rule)
    }

    @Test
    fun `a single capture is held in pending, outside the shared set, until a second one arrives`() {
        submit("synthetic-de-passport-2017-3.json")

        val first = intake(apply = true)

        val outcome = first.generations.single()
        assertEquals(Placement.PENDING, outcome.placement)
        assertTrue(outcome.profile.needsReview)
        assertTrue(outcome.profile.needsMoreCaptures)
        assertTrue(outcome.profile.measuredFields.isEmpty())
        val pendingFile = File(repo, "profiles/pending/generation-$generationC.json")
        assertTrue(pendingFile.isFile)
        assertTrue(ProfileRepository(File(repo, "profiles")).profiles().isEmpty(), "pending is not in the shared set")
        assertTrue(ProfileRepository(File(repo, "profiles/pending")).profiles().single().needsReview)
        // Not in the generated docs either
        assertTrue(File(repo, ChipProfileEvidenceDoc.FILE).readText().contains("No measured profile has been merged yet"))
        assertTrue(File(repo, CoverageMatrix.DOC_FILE).readText().contains("0 with captures"))
        assertTrue(first.report.contains("HELD BACK"))

        // A second capture of the same generation: the profile moves into the shared set and leaves pending
        val second = CaptureJson.encode(recordOf("synthetic-de-passport-2017-3.json").copy(captureId = "5d3a7c1e-9b42-4f0a-8e61-2c7d9a4b6f10"))
        File(inbox, "synthetic-de-passport-2017-3.json").delete()
        submit("second.json", second)
        val next = intake(apply = true)

        assertEquals(Placement.SHARED, next.generations.single().placement)
        assertEquals(2, next.generations.single().profile.sampleCount)
        assertFalse(pendingFile.exists())
        assertEquals(2, ProfileRepository(File(repo, "profiles")).profiles().single().sampleCount)
        assertTrue(File(repo, CoverageMatrix.DOC_FILE).readText().contains("generation-$generationC"))
    }

    @Test
    fun `a steward-released single-capture profile stays shared`() {
        submit("synthetic-de-passport-2017-3.json")
        intake(apply = true)
        val pending = File(repo, "profiles/pending/generation-$generationC.json")
        pending.copyTo(File(repo, "profiles/generation-$generationC.json"))
        pending.delete()

        val result = intake(apply = true)

        assertEquals(Placement.SHARED, result.generations.single().placement)
        assertTrue(File(repo, "profiles/pending").list().isNullOrEmpty())
    }

    @Test
    fun `the same capture sent twice counts once, and a second run changes nothing`() {
        submit("synthetic-de-passport-2017-1.json")
        submit("copy.json", goldenText("synthetic-de-passport-2017-1.json"))
        submit("synthetic-de-passport-2017-2.json")

        val first = intake(apply = true)
        assertEquals(2, storedFiles().size)
        assertEquals(2, first.generations.single().profile.sampleCount)

        val second = intake(apply = true)
        assertTrue(second.accepted.all { it.alreadyStored })
        assertEquals(2, storedFiles().size)
        assertTrue(second.generations.none { it.changed }, "unchanged on a second run")
        assertTrue(second.docs.none { it.changed })
    }

    @Test
    fun `a store inside the repository is refused`() {
        submit("synthetic-de-passport-2017-1.json")
        val inside = File(repo, "captures-store")

        val failure = assertFailsWith<IntakeSetupException> {
            CaptureIntake(IntakeOptions(inbox, inside, repo, apply = true)).run()
        }
        assertTrue(failure.message!!.contains("inside the repository"))
        assertFalse(inside.exists())
    }

    @Test
    fun `the arguments are parsed, and an unknown one is refused`() {
        val options = IntakeMain.parse(listOf("--input", "in", "--store", "st", "--repo", "r", "--apply", "--link", "abcd=de-passport-2017"))
        assertEquals(File("in"), options.inputDir)
        assertTrue(options.apply)
        assertEquals(mapOf("ABCD" to "de-passport-2017"), options.links)
        assertFailsWith<IllegalArgumentException> { IntakeMain.parse(listOf("--input", "in", "--store", "s", "--repo", "r", "--push")) }
        assertFailsWith<IllegalArgumentException> { IntakeMain.parse(listOf("--input", "in")) }
    }
}
