package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.CaptureFingerprint
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.RedactionLint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The redaction lint over everything in the repository that came from captures, which CI runs: every profile in
 * `profiles/` (and `profiles/pending/` where a maintainer has one) with [ProfileLint], and every golden capture with
 * [RedactionLint] and the fingerprint check. Nothing here needs a real capture: the lint is also tried on made-up leaks.
 */
class ProfilesRedactionLintTest {
    private val profilesDir = File("../profiles")
    private val goldenDir = File("src/androidUnitTest/resources/capture/golden")

    private fun jsonFiles(directory: File): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private fun leaves(text: String, replaceWhat: String, with: String): String {
        assertTrue(text.contains(replaceWhat), "test setup: $replaceWhat not in the sample")
        return text.replaceFirst(replaceWhat, with)
    }

    private val sample: String by lazy {
        val captures = jsonFiles(goldenDir).filter { it.name.startsWith("synthetic-de-passport-2017") }.map { CaptureJson.decode(it.readText()) }
        MeasuredProfileJson.encode(HoldOutlessMerge.merge(captures))
    }

    private object HoldOutlessMerge {
        fun merge(captures: List<com.hddev.smartemu.capture.CaptureRecord>) = ProfileMerger.merge(captures.take(2), id = "lint-sample", chipProfileId = "de-passport-2017")
    }

    @Test
    fun `every profile in profiles passes the lint`() {
        val files = jsonFiles(profilesDir) + jsonFiles(File(profilesDir, "pending"))
        files.forEach { assertEquals(emptyList(), ProfileLint.check(it.readText()), it.name) }
        println("profile lint: ${files.size} files under profiles/")
    }

    @Test
    fun `every golden capture passes the redaction lint and has the fingerprint its contents give`() {
        val files = jsonFiles(goldenDir)
        assertTrue(files.isNotEmpty())
        files.forEach {
            assertEquals(emptyList(), RedactionLint.check(it.readText()), it.name)
            assertTrue(CaptureFingerprint.isConsistent(CaptureJson.decode(it.readText())), "${it.name}: fingerprint")
        }
    }

    @Test
    fun `a freshly merged profile and the golden measured profile pass`() {
        assertEquals(emptyList(), ProfileLint.check(sample))
        assertEquals(emptyList(), ProfileLint.check(MeasuredProfileJson.encode(GoldenMeasuredProfile.profile)))
    }

    @Test
    fun `a profile with an MRZ line in any string is rejected, and the violation does not repeat it`() {
        val mrz = "P<D<<MUSTERMANN<<ERIKA<<<<<<<<<<<<<<<<<<<<<<<<"
        val leaky = leaves(sample, "NFC-B: HCE offers NFC-A only", mrz)
        val violations = ProfileLint.check(leaky)
        assertTrue(violations.any { it.rule == RedactionLint.Rule.MRZ_PATTERN && it.path == "notEmulatable[]" }, violations.toString())
        violations.forEach { v -> listOf("MUSTERMANN", "ERIKA", "P<D").forEach { assertFalse((v.path + v.detail).contains(it)) } }
    }

    @Test
    fun `a profile with a field outside the schema, or a per-capture record, is rejected`() {
        val withField = leaves(sample, "\"id\": \"lint-sample\"", "\"id\": \"lint-sample\", \"holderName\": \"ERIKA\"")
        assertTrue(ProfileLint.check(withField).any { it.rule == RedactionLint.Rule.SCHEMA_MISMATCH })

        val record = jsonFiles(goldenDir).first().readText()
        assertTrue(ProfileLint.check(record).any { it.rule == RedactionLint.Rule.SCHEMA_MISMATCH }, "a capture record in profiles/ is refused")
        assertTrue(ProfileLint.check("not json").any { it.rule == RedactionLint.Rule.MALFORMED_JSON })
    }

    @Test
    fun `a capture named by anything but a random UUID, and a long hex string outside the file fields, are rejected`() {
        val sampleId = ProfileMerger.merge(listOf(CaptureJson.decode(jsonFiles(goldenDir).first().readText())), id = "x").captureIds.single()
        val renamed = MeasuredProfileJson.encode(ProfileMerger.merge(listOf(CaptureJson.decode(jsonFiles(goldenDir).first().readText())), id = "x"))
            .replace(sampleId, "ERIKA-MUSTERMANN-passport")
        val violations = ProfileLint.check(renamed)
        assertTrue(violations.any { it.rule == RedactionLint.Rule.BAD_FIELD && it.path.endsWith("captureIds[]") }, violations.toString())

        // A hash-like value where a reason belongs
        val withBlob = leaves(sample, "NFC-B: HCE offers NFC-A only", "9F8E7D6C5B4A39281706F5E4D3C2B1A09F8E7D6C")
        assertTrue(ProfileLint.check(withBlob).any { it.rule == RedactionLint.Rule.HIGH_ENTROPY_BLOB && it.path == "notEmulatable[]" })
    }
}
