package com.hddev.smartemu.capture.profile

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * The measured profiles that ship in the app: the build copies the merged profiles, the JSON files in `profiles/`
 * (never `profiles/pending/`), into the assets, and [install] links them into [MeasuredProfileRegistry] once per
 * process, so that the presets, the profile picker and the emulator all find them. A file that doesn't decode, or
 * whose name isn't its profile's id, is skipped and logged, as [ProfileRepository] does.
 */
object BundledProfiles {
    private const val TAG = "BundledProfiles"
    private const val ASSET_DIRECTORY = "profiles"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            // No assets where the service runs on the JVM, in the unit tests, so nothing to install
            val assets = (context.applicationContext ?: context).assets ?: return
            MeasuredProfileRegistry.link(load(assets))
            installed = true
        }
    }

    /** The profiles in the assets' profile directory that decode and are named for their id, in file name order. */
    internal fun load(assets: AssetManager): List<MeasuredProfile> =
        assets.list(ASSET_DIRECTORY).orEmpty().filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            runCatching {
                val profile = MeasuredProfileJson.decode(assets.open("$ASSET_DIRECTORY/$name").bufferedReader().use { it.readText() })
                require(name == "${profile.id}.json") { "File name does not match the profile id '${profile.id}'" }
                profile
            }.onFailure { Log.w(TAG, "Skipped bundled profile $name: ${it.message}") }.getOrNull()
        }
}
