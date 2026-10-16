package com.hddev.smartemu.capture

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The one JSON format of a [CaptureRecord]: pretty-printed, every field written, and strict on reading, so a record
 * with a field the schema doesn't have is refused rather than quietly dropped.
 */
object CaptureJson {
    val json: Json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = false
    }

    fun encode(record: CaptureRecord): String = json.encodeToString(CaptureRecord.serializer(), record)

    /**
     * The record as the text wrote it, whatever schema version that was (from [MIN_CAPTURE_SCHEMA_VERSION] to
     * [CAPTURE_SCHEMA_VERSION]). Throws [SerializationException] on malformed JSON, a field outside the schema, or a version
     * this build can't read.
     */
    fun decodeAsWritten(text: String): CaptureRecord {
        val record = json.decodeFromString(CaptureRecord.serializer(), text)
        if (record.schemaVersion !in MIN_CAPTURE_SCHEMA_VERSION..CAPTURE_SCHEMA_VERSION) {
            throw SerializationException("Unsupported capture schemaVersion ${record.schemaVersion}")
        }
        return record
    }

    /** The record upgraded to [CAPTURE_SCHEMA_VERSION] (see [CaptureMigration]); the same errors as [decodeAsWritten]. */
    fun decode(text: String): CaptureRecord = CaptureMigration.toCurrent(decodeAsWritten(text))
}

/**
 * Upgrades records of an older schema version. Version 1 to 2: `fingerprint` becomes the generation key, the digest of
 * every observation is added, and `redactions` become the current manifest. Nothing a version 1 record held is lost: the
 * generation key and the digest are computed from what it holds. Check a version 1 record's own fingerprint before
 * upgrading it ([CaptureFingerprint.isConsistent] on the record as written; [RedactionLint] does), since the upgrade
 * replaces it.
 */
object CaptureMigration {
    fun toCurrent(record: CaptureRecord): CaptureRecord {
        if (record.schemaVersion >= CAPTURE_SCHEMA_VERSION) return record
        val upgraded = record.copy(
            schemaVersion = CAPTURE_SCHEMA_VERSION,
            redactions = RedactionManifest.expected(CAPTURE_SCHEMA_VERSION, record.authenticated != null)
        )
        return CaptureFingerprint.seal(upgraded)
    }
}
