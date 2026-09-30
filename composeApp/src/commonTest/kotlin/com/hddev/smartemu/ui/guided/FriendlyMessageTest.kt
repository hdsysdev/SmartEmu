package com.hddev.smartemu.ui.guided

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FriendlyMessageTest {

    @Test
    fun aFailedStartBecomesPlainAdvice() {
        val message = friendlyMessage("Failed to start simulation: NFC hardware not available")

        assertNotNull(message)
        assertEquals(false, "simulation" in message)
    }

    @Test
    fun problemsTheScreensShowAreNotRepeated() {
        assertNull(friendlyMessage("NFC is not available on this device"))
        assertNull(friendlyMessage("NFC permissions are required for simulation"))
        assertNull(friendlyMessage("BAC authentication failed: Authentication token mismatch"))
    }

    @Test
    fun datesAreWrittenOut() {
        assertEquals("1 January 2030", formatDate(LocalDate(2030, 1, 1)))
        assertEquals("31 December 1980", formatDate(LocalDate(1980, 12, 31)))
    }
}
