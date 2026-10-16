package com.hddev.smartemu.capture.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureStoreTest {
    private fun tempDirectory(): File = Files.createTempDirectory("capture-store-test").toFile().also { it.deleteOnExit() }

    @Test
    fun `files older than the retention are deleted and newer ones kept`() {
        val directory = tempDirectory()
        val day = 24L * 60 * 60 * 1000
        val now = 1_800_000_000_000L
        val old = File(directory, "capture-old.json").apply { writeText("{}"); setLastModified(now - 31 * day) }
        val edge = File(directory, "capture-edge.json").apply { writeText("{}"); setLastModified(now - 29 * day) }
        val fresh = File(directory, "capture-new.json").apply { writeText("{}"); setLastModified(now) }

        val deleted = CaptureStore.purgeOlderThan(directory, cutoffMillis = now - CaptureStore.RETENTION_DAYS * day)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(edge.exists())
        assertTrue(fresh.exists())
        directory.deleteRecursively()
    }

    @Test
    fun `a missing directory is nothing to purge`() {
        assertEquals(0, CaptureStore.purgeOlderThan(File(tempDirectory(), "none"), cutoffMillis = Long.MAX_VALUE))
    }

    @Test
    fun `the retention is thirty days`() {
        assertEquals(30L, CaptureStore.RETENTION_DAYS)
    }
}
