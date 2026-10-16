package com.hddev.smartemu.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Keeps the schema from being defined only in Kotlin: the committed golden JSON must decode, and encode back to the
 * same text. A change that renames, drops or retypes a field fails here, and the fix is a schema version bump, a
 * migration note and a new golden file, not an edit of an old one. golden-capture-v1.json is a version 1 record as it
 * was written (its fingerprint is the hash of every observation); golden-capture-v2.json is the same chip under
 * version 2. The v1 file must keep decoding, and upgrade to the v2 record.
 */
class CaptureGoldenFileTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/capture/$name")) { "$name missing" }.readBytes().decodeToString()

    private val v1: String by lazy { resource("golden-capture-v1.json") }
    private val v2: String by lazy { resource("golden-capture-v2.json") }

    @Test
    fun `regenerate the golden files on request`() {
        if (System.getenv("UPDATE_GOLDEN") != "1") return
        File("src/androidUnitTest/resources/capture/golden-capture-v1.json").writeText(CaptureJson.encode(GoldenCapture.recordV1) + "\n")
        File("src/androidUnitTest/resources/capture/golden-capture-v2.json").writeText(CaptureJson.encode(GoldenCapture.record) + "\n")
    }

    @Test
    fun `the golden files are schema versions 1 and 2`() {
        assertEquals(2, CAPTURE_SCHEMA_VERSION)
        assertEquals(1, MIN_CAPTURE_SCHEMA_VERSION)
        assertEquals(1, CaptureJson.decodeAsWritten(v1).schemaVersion)
        assertEquals(2, CaptureJson.decodeAsWritten(v2).schemaVersion)
    }

    @Test
    fun `decode as written to the sample records and encode back identically`() {
        assertEquals(GoldenCapture.recordV1, CaptureJson.decodeAsWritten(v1))
        assertEquals(v1.trimEnd(), CaptureJson.encode(CaptureJson.decodeAsWritten(v1)))
        assertEquals(GoldenCapture.record, CaptureJson.decodeAsWritten(v2))
        assertEquals(v2.trimEnd(), CaptureJson.encode(CaptureJson.decodeAsWritten(v2)))
    }

    @Test
    fun `a version 1 record is upgraded on decode so the generation key and the digest are computed and nothing else moves`() {
        val upgraded = CaptureJson.decode(v1)
        assertEquals(GoldenCapture.record, upgraded)
        assertEquals(2, upgraded.schemaVersion)
        val old = CaptureJson.decodeAsWritten(v1)
        // The v1 fingerprint hashed every observation; the key that replaces it hashes the chip's own files and select only
        assertNotEquals(old.fingerprint, upgraded.fingerprint)
        assertEquals(CaptureFingerprint.of(old), upgraded.fingerprint)
        assertEquals(CaptureFingerprint.digestOf(old), upgraded.observationDigest)
        assertEquals(old.files, upgraded.files)
        assertEquals(old.probes, upgraded.probes)
        assertEquals(old.reader, upgraded.reader)
        assertEquals(RedactionManifest.entries, upgraded.redactions)
        assertTrue(CaptureFingerprint.isConsistent(upgraded))
        assertTrue(CaptureFingerprint.isConsistent(old), "the record as written is consistent under its own version")
    }

    @Test
    fun `a schema version this build can't read is refused`() {
        assertFailsWith<SerializationException> { CaptureJson.decode(v2.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 3")) }
        assertFailsWith<SerializationException> { CaptureJson.decode(v2.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 0")) }
    }

    @Test
    fun `the files name the fields of their schema version`() {
        val common = setOf(
            "schemaVersion", "captureId", "captureMonth", "source", "probePack", "additionalProbePacks", "reader", "link", "files",
            "application", "bacCommands", "probes", "aborted", "fingerprint", "redactions"
        )
        val root1 = Json.parseToJsonElement(v1).jsonObject
        val root2 = Json.parseToJsonElement(v2).jsonObject
        assertEquals(common, root1.keys)
        assertEquals(common + "observationDigest", root2.keys)
        assertEquals(setOf("model", "androidVersion", "nfcChipset", "controllerMaxTransceiveLength", "controllerExtendedLengthSupported"), root1.getValue("reader").jsonObject.keys)
        assertEquals(setOf("cardAccess", "atrInfo", "dir"), root1.getValue("files").jsonObject.keys)
        assertEquals(setOf("selectStatusWord", "contentsHex", "size"), root1.getValue("files").jsonObject.getValue("cardAccess").jsonObject.keys)
    }

    @Test
    fun `an unknown field is refused, not dropped`() {
        listOf(v1, v2).forEach { text ->
            val withExtra = text.replaceFirst("{", "{\n    \"mrz\": \"x\",")
            assertFailsWith<SerializationException> { CaptureJson.decode(withExtra) }
        }
    }

    @Test
    fun `a record without the fields added after the first draft still decodes`() {
        val minimal = """
            {"schemaVersion": 1, "captureId": "3b0d6c2e-7a41-4d8e-9c55-0f7e1a2b3c4d", "captureMonth": "2026-09",
             "source": "PCSC", "probePack": {"id": "tier-a", "version": 1},
             "reader": {"model": "ACR122U", "androidVersion": "n/a"}, "link": {"technology": "NFC_B"}}
        """.trimIndent()
        val record = CaptureJson.decode(minimal)
        assertEquals(CaptureSource.PCSC, record.source)
        assertEquals(UnauthenticatedFiles(), record.files)
        assertTrue(record.probes.isEmpty())
    }

    @Test
    fun `the golden files pass the redaction lint`() {
        assertEquals(emptyList(), RedactionLint.check(v1))
        assertEquals(emptyList(), RedactionLint.check(v2))
    }
}
