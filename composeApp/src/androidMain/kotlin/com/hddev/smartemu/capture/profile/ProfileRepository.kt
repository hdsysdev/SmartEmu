package com.hddev.smartemu.capture.profile

import java.io.File

/** A profile file that could not be loaded, and why. */
data class ProfileLoadError(val file: String, val message: String)

data class ProfileLoadResult(val profiles: List<MeasuredProfile>, val errors: List<ProfileLoadError>)

/**
 * Reads measured profiles from a directory of `<id>.json` files, the `profiles/` convention (see profiles/README.md).
 * The curated Kotlin profiles stay where they are during the migration: [MeasuredProfileRegistry.link] attaches what
 * is loaded here to them.
 *
 * A file that doesn't decode (strict: an unknown field or version is refused), or whose name isn't its profile's id,
 * is reported in [ProfileLoadResult.errors] and not loaded.
 */
class ProfileRepository(private val directory: File) {

    fun load(): ProfileLoadResult {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        val profiles = mutableListOf<MeasuredProfile>()
        val errors = mutableListOf<ProfileLoadError>()
        for (file in files) {
            try {
                val profile = MeasuredProfileJson.decode(file.readText())
                if (file.name != "${profile.id}.json") {
                    errors += ProfileLoadError(file.name, "File name does not match the profile id '${profile.id}'")
                } else {
                    profiles += profile
                }
            } catch (e: Exception) {
                errors += ProfileLoadError(file.name, e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return ProfileLoadResult(profiles, errors)
    }

    /** The profiles that load, ignoring the ones that don't. */
    fun profiles(): List<MeasuredProfile> = load().profiles

    /** Writes [profile] to `<id>.json` in the directory, creating it if needed. */
    fun save(profile: MeasuredProfile): File {
        directory.mkdirs()
        return File(directory, "${profile.id}.json").also { it.writeText(MeasuredProfileJson.encode(profile) + "\n") }
    }

    companion object {
        /** Decodes profiles from JSON texts, for assets and resources a caller has read itself. */
        fun fromTexts(texts: List<String>): List<MeasuredProfile> = texts.map(MeasuredProfileJson::decode)
    }
}
