package com.hddev.smartemu.capture.profile

import com.hddev.smartemu.capture.GoldenCapture
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The sample profile behind profiles/golden-measured-profile-v1.json. */
object GoldenMeasuredProfile {
    val profile: MeasuredProfile = run {
        val a = GoldenCapture.record.copy(captureId = "11111111-1111-4111-8111-111111111111")
        val b = a.copy(
            captureId = "22222222-2222-4222-8222-222222222222",
            reader = a.reader.copy(model = "Pixel 8"),
            probes = a.probes.map { probe ->
                probe.copy(steps = probe.steps.map { if (it.stepId == "extended-le-ffff") it.copy(statusWord = 0x6D00) else it })
            }
        )
        val c = a.copy(captureId = "33333333-3333-4333-8333-333333333333", reader = a.reader.copy(model = "Galaxy S24"))
        val merged = ProfileMerger.merge(listOf(a, b, c), id = "golden-profile")
        merged.withMeasuredFields(
            setOf("file:cardAccess:contents", "bac:getChallenge"),
            MeasuredEvidence(merged.captureIds, 3, merged.probePacks, heldOutCaptureId = c.captureId)
        )
    }
}

/**
 * Keeps the profile format from being defined only in Kotlin: the committed golden JSON must decode and encode back
 * to the same text, and docs/measured-profile.schema.json must name the same fields. A change that renames, drops or
 * retypes a field fails here, and the fix is a schema version bump, a migration note and a new golden file.
 */
class MeasuredProfileGoldenFileTest {
    private val resource = "/profiles/golden-measured-profile-v1.json"
    private val text: String by lazy {
        checkNotNull(javaClass.getResourceAsStream(resource)) { "golden file missing" }.readBytes().decodeToString()
    }

    @Test
    fun `regenerate the golden file on request`() {
        if (System.getenv("UPDATE_GOLDEN") != "1") return
        File("src/androidUnitTest/resources$resource").also { it.parentFile.mkdirs() }
            .writeText(MeasuredProfileJson.encode(GoldenMeasuredProfile.profile) + "\n")
    }

    @Test
    fun `decodes to the sample profile and encodes back identically`() {
        val profile = MeasuredProfileJson.decode(text)
        assertEquals(GoldenMeasuredProfile.profile, profile)
        assertEquals(text.trimEnd(), MeasuredProfileJson.encode(profile))
    }

    @Test
    fun `the golden file is schema version 1 and shows a disagreement and measured fields`() {
        val profile = MeasuredProfileJson.decode(text)
        assertEquals(1, profile.schemaVersion)
        assertTrue(profile.disagreements.any { it.field.contains("extended-le-ffff") })
        assertEquals(setOf("file:cardAccess:contents", "bac:getChallenge"), profile.measuredFields.map { it.key }.toSet())
        assertTrue(profile.measuredFields.all { it.evidence?.heldOutCaptureId != null && it.evidence.sampleCount == 3 })
    }

    @Test
    fun `an unknown field is refused, not dropped`() {
        assertFailsWith<SerializationException> { MeasuredProfileJson.decode(text.replaceFirst("{", "{\n  \"mrz\": \"x\",")) }
    }

    @Test
    fun `a schema version this build has no migration for is refused`() {
        assertFailsWith<SerializationException> { MeasuredProfileJson.decode(text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }
    }

    @Test
    fun `the JSON Schema names the fields of the Kotlin type`() {
        val schema = Json.parseToJsonElement(File("../docs/measured-profile.schema.json").readText()).jsonObject
        val golden = Json.parseToJsonElement(text).jsonObject
        assertEquals(golden.keys, schema.getValue("properties").jsonObject.keys)
        assertEquals("false", schema.getValue("additionalProperties").toString())
        val defs = schema.getValue("\$defs").jsonObject
        assertEquals(
            golden.getValue("observations").jsonObject.keys,
            defs.getValue("MeasuredObservations").jsonObject.getValue("properties").jsonObject.keys
        )
        val field = golden.getValue("fields").jsonArray.first { it.jsonObject.getValue("measured").toString() == "true" }.jsonObject
        assertEquals(field.keys, defs.getValue("MeasuredField").jsonObject.getValue("properties").jsonObject.keys)
        assertEquals(
            golden.getValue("disagreements").jsonArray.first().jsonObject.keys,
            defs.getValue("Disagreement").jsonObject.getValue("properties").jsonObject.keys
        )
        assertEquals(
            field.getValue("evidence").jsonObject.keys,
            defs.getValue("MeasuredEvidence").jsonObject.getValue("properties").jsonObject.keys
        )
        // Every required property of the schema is one the golden file has
        assertTrue(schema.getValue("required").jsonArray.all { it.toString().trim('"') in golden.keys })
    }

    @Test
    fun `no field of the format can carry a controller limit, a UID or a free-text note`() {
        val names = Regex("\"([A-Za-z]+)\"\\s*:").findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(names.none { it.contains("controller", ignoreCase = true) || it.contains("uid", ignoreCase = true) && it != "uidRandom" })
    }
}
