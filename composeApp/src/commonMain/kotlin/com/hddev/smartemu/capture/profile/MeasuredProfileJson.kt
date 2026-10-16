package com.hddev.smartemu.capture.profile

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The one JSON format of a [MeasuredProfile]: pretty-printed, every field written, and strict on reading, so a file
 * with a field the schema doesn't have (docs/measured-profile.schema.json) is refused rather than quietly dropped.
 */
object MeasuredProfileJson {
    val json: Json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun encode(profile: MeasuredProfile): String = json.encodeToString(MeasuredProfile.serializer(), profile)

    /**
     * Throws [SerializationException] on malformed JSON, a field outside the schema, or a `schemaVersion` this build
     * has no migration for.
     */
    fun decode(text: String): MeasuredProfile {
        val profile = json.decodeFromString(MeasuredProfile.serializer(), text)
        if (profile.schemaVersion != MEASURED_PROFILE_SCHEMA_VERSION) {
            throw SerializationException(
                "Unsupported measured profile schemaVersion ${profile.schemaVersion}; this build reads $MEASURED_PROFILE_SCHEMA_VERSION"
            )
        }
        return profile
    }
}
