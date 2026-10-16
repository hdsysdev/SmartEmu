package com.hddev.smartemu.capture

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/capture-record.schema.json is a published contract, so it is tested: every record the project holds validates
 * against it, a record outside it does not, and its properties are the Kotlin type's, field for field. A field added
 * to [CaptureRecord] and not to the schema (or the reverse) fails here.
 */
class CaptureSchemaFileTest {
    private val schemaText = File("../docs/capture-record.schema.json").readText()
    private val schema = Json.parseToJsonElement(schemaText).jsonObject
    private val validator = JsonSchemaValidator(schema)
    private val defs = schema.getValue("\$defs").jsonObject

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/capture/$name")) { "$name missing" }.readBytes().decodeToString()

    private fun errors(text: String) = validator.validate(Json.parseToJsonElement(text))

    private val syntheticDirectory = File("src/androidUnitTest/resources/capture/golden")

    @Test
    fun `the golden captures of both versions validate`() {
        assertEquals(emptyList(), errors(resource("golden-capture-v1.json")))
        assertEquals(emptyList(), errors(resource("golden-capture-v2.json")))
    }

    @Test
    fun `every synthetic golden capture validates`() {
        val files = syntheticDirectory.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 5, "only ${files.size} golden captures")
        files.forEach { assertEquals(emptyList(), errors(it.readText()), it.name) }
    }

    @Test
    fun `a record with every optional field filled validates`() {
        val full = GoldenCapture.record.copy(
            additionalProbePacks = listOf(ProbePackRef("tier-b", 1)),
            link = GoldenCapture.record.link.copy(historicalBytesLength = 12),
            aborted = AbortReason.TAG_LOST,
            authenticated = AuthenticatedObservation(
                credential = CredentialKind.CAN,
                access = listOf(
                    AccessAttempt(AccessMethod.PACE, "0.4.0.127.0.7.2.2.4.2.4", PaceMappingType.GM, "AES-128", 13, true, null, StatusClass.OK),
                    AccessAttempt(AccessMethod.BAC, succeeded = false, statusWord = 0x6300, statusClass = StatusClass.KEY_REJECTED)
                ),
                accessStopped = AccessStop.KEY_REJECTED,
                readUnder = 0,
                com = ComObservation("0107", "040000", listOf(1, 2), Redaction.sizeRange(30)),
                dataGroups = listOf(DataGroupObservation(1, true, true, 0x9000, 0x9000, Redaction.sizeRange(100))),
                sod = SodObservation(
                    Redaction.sizeRange(2000), "SHA-256", "ECDSA-SHA256", "0107", "040000", listOf(1, 2), 1,
                    SignerCertificateObservation("ECDSA-SHA256", "EC", 256, "brainpoolP256r1", "DE", 120)
                ),
                dg14 = SecurityInfosObservation(Redaction.sizeRange(100), listOf(SecurityInfoObservation(SecurityInfoKind.PACE, "0.4.0.127.0.7.2.2.4.2.4", 2, 13)), true),
                dg15 = ActiveAuthenticationKeyObservation(Redaction.sizeRange(100), "RSA", 2048, null),
                cardSecurity = SecurityInfosObservation(Redaction.sizeRange(100), emptyList(), false),
                cvca = CvcaObservation(Redaction.sizeRange(30), "DE", false),
                activeAuthentication = AuthenticationResult(true, "RSA-ISO9796-2-SHA1", 0x9000),
                chipAuthentication = AuthenticationResult(false, "ECDH-AES-128", 0x6985)
            )
        ).let { it.copy(redactions = RedactionManifest.expected(2, true)) }.let(CaptureFingerprint::seal)
        val text = CaptureJson.encode(full)
        assertEquals(emptyList(), errors(text))
        assertEquals(emptyList(), RedactionLint.check(text))
    }

    @Test
    fun `a record outside the schema does not validate`() {
        val good = resource("golden-capture-v2.json")
        fun bad(from: String, to: String) = errors(good.replaceFirst(from, to))
        assertTrue(bad("{", "{\n \"holder\": \"Anna\",").any { it.contains("holder") })
        assertTrue(bad("\"schemaVersion\": 2", "\"schemaVersion\": 3").isNotEmpty())
        assertTrue(bad("\"source\": \"ANDROID_READER\"", "\"source\": \"USB\"").isNotEmpty())
        assertTrue(bad("\"repeatsAgree\": true", "\"repeatsAgree\": \"yes\"").isNotEmpty())
        assertTrue(errors(good.replace(Regex("\"minBytes\": 0,(\\s+)\"maxBytes\": 15"), "\"minBytes\": 5,$1\"maxBytes\": 20")).isNotEmpty(), "a bucket that isn't one")
        assertTrue(bad("\"model\": \"Pixel 9\"", "\"model\": \"Pixel 9, rooted\"").isNotEmpty(), "a device name with a comma")
        assertTrue(bad("\"samples\": 3", "\"samples\": 500").isNotEmpty())
        assertTrue(errors("{}").any { it.contains("required") })
    }

    // ---- the schema against the Kotlin type ----

    private fun simpleName(d: SerialDescriptor) = d.serialName.substringAfterLast('.').removeSuffix("?")

    /** Every class and enum reachable from [d], by simple name. */
    private fun reachable(d: SerialDescriptor, into: MutableMap<String, SerialDescriptor> = linkedMapOf()): Map<String, SerialDescriptor> {
        when (d.kind) {
            StructureKind.CLASS -> if (into.put(simpleName(d), d) == null) (0 until d.elementsCount).forEach { reachable(d.getElementDescriptor(it), into) }
            StructureKind.LIST -> reachable(d.getElementDescriptor(0), into)
            is SerialKind.ENUM -> into[simpleName(d)] = d
            else -> Unit
        }
        return into
    }

    @Test
    fun `the schema has exactly the properties of the Kotlin type, and requires those without a default`() {
        val classes = reachable(CaptureRecord.serializer().descriptor)
        assertTrue(classes.size > 20, "only ${classes.size} types found")
        val problems = mutableListOf<String>()
        classes.forEach { (name, d) ->
            val node: JsonObject = if (name == "CaptureRecord") schema else defs[name]?.jsonObject ?: run {
                problems += "$name: no \$defs entry"
                return@forEach
            }
            if (d.kind is SerialKind.ENUM) {
                val allowed = node.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
                if (allowed != d.elementNames.toList()) problems += "$name: enum $allowed, Kotlin ${d.elementNames.toList()}"
                return@forEach
            }
            val properties = node.getValue("properties").jsonObject.keys
            if (properties != d.elementNames.toSet()) {
                problems += "$name: schema-only ${properties - d.elementNames.toSet()}, Kotlin-only ${d.elementNames.toSet() - properties}"
            }
            val required = node["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
            val mustRequire = (0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { d.getElementName(it) }.toSet()
            if (!required.containsAll(mustRequire)) problems += "$name: not required in the schema ${mustRequire - required}"
            if (node["additionalProperties"]?.toString() != "false") problems += "$name: additionalProperties is not false"
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `the schema keywords are all ones the test validator checks`() {
        // The validator refuses a keyword it doesn't know, so reaching every definition once proves none is silently ignored
        val instance = Json.parseToJsonElement(resource("golden-capture-v2.json"))
        validator.validate(instance)
        defs.values.forEach { def -> assertTrue(def is JsonObject) }
        fun keywords(e: kotlinx.serialization.json.JsonElement, out: MutableSet<String>) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k != "properties" && k != "\$defs") out += k; if (k == "properties" || k == "\$defs") v.jsonObject.values.forEach { keywords(it, out) } else keywords(v, out) }
                is JsonArray -> e.forEach { keywords(it, out) }
                else -> Unit
            }
        }
        val used = mutableSetOf<String>().also { keywords(schema, it) }
        val known = setOf("\$schema", "\$id", "title", "description", "\$defs", "\$ref", "type", "properties", "required", "additionalProperties", "enum", "const", "items", "pattern", "minimum", "maximum", "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems", "oneOf", "anyOf")
        assertEquals(emptySet(), used - known)
    }
}
