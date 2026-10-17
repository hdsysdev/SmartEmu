package com.hddev.smartemu.capture.profile

import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledProfilesTest {

    /** Assets holding [files] under `profiles/`, by name. */
    private fun assets(files: Map<String, String>?): AssetManager = mockk {
        every { list("profiles") } returns files?.keys?.toTypedArray()
        every { open(any()) } answers {
            val name = firstArg<String>().removePrefix("profiles/")
            files?.get(name)?.byteInputStream() ?: throw FileNotFoundException(name)
        }
    }

    @Test
    fun `loads the profiles named for their id, in file name order, and skips the rest`() {
        val golden = GoldenMeasuredProfile.profile
        val other = golden.copy(id = "a-first")
        val profiles = BundledProfiles.load(
            assets(
                mapOf(
                    "golden-profile.json" to MeasuredProfileJson.encode(golden),
                    "a-first.json" to MeasuredProfileJson.encode(other),
                    "broken.json" to "{ not json",
                    "wrong-name.json" to MeasuredProfileJson.encode(golden),
                    "README.md" to "ignored"
                )
            )
        )
        assertEquals(listOf("a-first", "golden-profile"), profiles.map { it.id })
        assertEquals(golden, profiles.last())
    }

    @Test
    fun `no profile directory in the assets is nothing to load`() {
        assertTrue(BundledProfiles.load(assets(null)).isEmpty())
        assertTrue(BundledProfiles.load(assets(emptyMap())).isEmpty())
    }
}
