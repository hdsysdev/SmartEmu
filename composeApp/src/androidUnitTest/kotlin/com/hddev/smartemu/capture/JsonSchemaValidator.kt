package com.hddev.smartemu.capture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A minimal JSON Schema validator for docs/capture-record.schema.json, so that the schema file is tested and not only
 * written. It supports the keywords that file uses and refuses to run on any other (an unsupported keyword would be
 * silently ignored, which is how a schema stops checking anything): `$ref` into `#/$defs`, `type` (a name or a list),
 * `properties`, `required`, `additionalProperties: false`, `enum`, `const`, `items`, `pattern`, `minimum`, `maximum`,
 * `multipleOf`, `minLength`, `maxLength`, `maxItems`, `uniqueItems`, `oneOf`, `anyOf`. Annotations (`$schema`, `$id`,
 * `title`, `description`, `$defs`) are skipped.
 */
class JsonSchemaValidator(private val root: JsonObject) {
    private val annotations = setOf("\$schema", "\$id", "title", "description", "\$defs")
    private val supported = setOf(
        "\$ref", "type", "properties", "required", "additionalProperties", "enum", "const", "items", "pattern", "minimum",
        "maximum", "multipleOf", "minLength", "maxLength", "maxItems", "uniqueItems", "oneOf", "anyOf"
    ) + annotations

    fun validate(instance: JsonElement): List<String> = mutableListOf<String>().also { check(root, instance, "$", it) }

    private fun check(schema: JsonObject, value: JsonElement, path: String, out: MutableList<String>) {
        val unsupported = schema.keys - supported
        require(unsupported.isEmpty()) { "Schema keyword(s) not supported by the test validator at $path: $unsupported" }
        schema["\$ref"]?.let { ref ->
            val name = ref.jsonPrimitive.content.removePrefix("#/\$defs/")
            check(root.getValue("\$defs").jsonObject.getValue(name).jsonObject, value, path, out)
        }
        schema["type"]?.let { type ->
            val names = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
            if (names.none { matchesType(it, value) }) {
                out += "$path: expected ${names.joinToString("|")}"
                return
            }
        }
        schema["enum"]?.let { values -> if (values.jsonArray.none { it == value }) out += "$path: not one of the allowed values" }
        schema["const"]?.let { if (it != value) out += "$path: not the constant value" }
        schema["oneOf"]?.let { alternatives ->
            val matching = alternatives.jsonArray.count { sub -> mutableListOf<String>().also { check(sub.jsonObject, value, path, it) }.isEmpty() }
            if (matching != 1) out += "$path: matches $matching of the oneOf alternatives, not one"
        }
        schema["anyOf"]?.let { alternatives ->
            if (alternatives.jsonArray.none { sub -> mutableListOf<String>().also { check(sub.jsonObject, value, path, it) }.isEmpty() }) {
                out += "$path: matches none of the anyOf alternatives"
            }
        }
        when (value) {
            is JsonObject -> {
                val properties = schema["properties"]?.jsonObject
                schema["required"]?.jsonArray?.forEach { name ->
                    if (name.jsonPrimitive.content !in value) out += "$path: required property ${name.jsonPrimitive.content} is missing"
                }
                if (schema["additionalProperties"]?.let { it is JsonPrimitive && it.booleanOrNull == false } == true) {
                    value.keys.filter { properties == null || it !in properties }.forEach { out += "$path: property $it is not in the schema" }
                }
                properties?.forEach { (name, sub) -> value[name]?.let { check(sub.jsonObject, it, "$path.$name", out) } }
            }
            is JsonArray -> {
                schema["maxItems"]?.let { if (value.size > it.jsonPrimitive.long()) out += "$path: more than $it items" }
                if (schema["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true && value.toSet().size != value.size) out += "$path: items are not unique"
                schema["items"]?.let { items -> value.forEachIndexed { i, element -> check(items.jsonObject, element, "$path[$i]", out) } }
            }
            is JsonPrimitive -> if (value !is JsonNull) checkPrimitive(schema, value, path, out)
        }
    }

    private fun checkPrimitive(schema: JsonObject, value: JsonPrimitive, path: String, out: MutableList<String>) {
        if (value.isString) {
            val text = value.content
            schema["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(text)) out += "$path: does not match the pattern" }
            schema["minLength"]?.let { if (text.length < it.jsonPrimitive.long()) out += "$path: shorter than $it" }
            schema["maxLength"]?.let { if (text.length > it.jsonPrimitive.long()) out += "$path: longer than $it" }
        } else if (value.longOrNull != null || value.doubleOrNull != null) {
            val number = value.doubleOrNull ?: return
            schema["minimum"]?.let { if (number < it.jsonPrimitive.double()) out += "$path: below $it" }
            schema["maximum"]?.let { if (number > it.jsonPrimitive.double()) out += "$path: above $it" }
            schema["multipleOf"]?.let { if (number % it.jsonPrimitive.double() != 0.0) out += "$path: not a multiple of $it" }
        }
    }

    private fun matchesType(name: String, value: JsonElement): Boolean = when (name) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "null" -> value is JsonNull
        else -> error("Unknown type $name")
    }

    private fun JsonElement.long(): Long = jsonPrimitive.longOrNull ?: error("Not an integer: $this")
    private fun JsonElement.double(): Double = jsonPrimitive.doubleOrNull ?: error("Not a number: $this")

    companion object {
        fun parse(text: String): JsonSchemaValidator =
            JsonSchemaValidator(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject)
    }
}
