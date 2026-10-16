package com.hddev.smartemu.capture.profile

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bumped on any change to the allowed-deviation file. */
const val ALLOWED_DEVIATIONS_SCHEMA_VERSION = 1

/**
 * A difference between the emulator and a measured profile that a reviewer has accepted, for the time being. Every one
 * has a field, a reason and an owner; an entry with an expiry stops being honoured on that day, so a deviation can't
 * be waved through for ever.
 */
@Serializable
data class AllowedDeviation(
    /** A field key (see [FieldKeys]); `*` stands for any run of characters. */
    val field: String,
    val reason: String,
    /** Who is to fix it or confirm it, or the placeholder "TODO" until somebody takes it. */
    val owner: String,
    /** The last day the entry is honoured, `YYYY-MM-DD`, or null for no expiry. */
    val expires: String? = null,
    /** A profile id the entry is for, or null for every profile. */
    val profile: String? = null
) {
    fun appliesTo(profileId: String, key: String): Boolean =
        (profile == null || profile == profileId) && globMatches(field, key)

    fun isExpired(today: LocalDate): Boolean = expires?.let { LocalDate.parse(it) < today } ?: false

    private fun globMatches(pattern: String, text: String): Boolean {
        if ('*' !in pattern) return pattern == text
        val parts = pattern.split('*')
        var index = 0
        parts.forEachIndexed { i, part ->
            if (part.isEmpty()) return@forEachIndexed
            val found = text.indexOf(part, index)
            if (found < 0 || (i == 0 && found != 0)) return false
            index = found + part.length
        }
        return parts.last().isEmpty() || text.endsWith(parts.last())
    }
}

/** The reviewed list in docs/fidelity-allowed-deviations.json. */
@Serializable
data class AllowedDeviations(
    val schemaVersion: Int = ALLOWED_DEVIATIONS_SCHEMA_VERSION,
    val deviations: List<AllowedDeviation> = emptyList()
) {
    /** The entry that allows a deviation of [key] on [profileId] as of [today], or null. Expired entries allow nothing. */
    fun find(profileId: String, key: String, today: LocalDate): AllowedDeviation? =
        deviations.firstOrNull { !it.isExpired(today) && it.appliesTo(profileId, key) }

    fun expired(today: LocalDate): List<AllowedDeviation> = deviations.filter { it.isExpired(today) }

    companion object {
        val none = AllowedDeviations()

        private val json = Json { ignoreUnknownKeys = false }

        /** Throws [SerializationException] on malformed JSON, an unknown field or an unsupported version. */
        fun decode(text: String): AllowedDeviations {
            val parsed = json.decodeFromString(serializer(), text)
            if (parsed.schemaVersion != ALLOWED_DEVIATIONS_SCHEMA_VERSION) {
                throw SerializationException("Unsupported allowed-deviations schemaVersion ${parsed.schemaVersion}")
            }
            return parsed
        }
    }
}
