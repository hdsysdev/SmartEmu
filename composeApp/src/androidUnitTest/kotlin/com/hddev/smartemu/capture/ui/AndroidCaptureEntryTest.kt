package com.hddev.smartemu.capture.ui

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidCaptureEntryTest {

    /** A context whose shared preferences hold [values], by preferences name and key; any other read gets its default. */
    private fun context(values: Map<String, Map<String, Boolean>>): Context {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } answers {
            val stored = values[firstArg<String>()].orEmpty()
            mockk<SharedPreferences> {
                every { getBoolean(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg<Boolean>() }
                every { getString(any(), any()) } answers { secondArg<String?>() }
            }
        }
        return context
    }

    private fun available(unlocked: Boolean, developerMode: Boolean): Boolean = AndroidCaptureEntry.isAvailable(
        context(mapOf("capture_gate" to mapOf("unlocked" to unlocked), "settings" to mapOf("developer_mode" to developerMode)))
    )

    @Test
    fun `capture mode opens in developer mode or once unlocked, and stays closed otherwise`() {
        assertEquals(false, available(unlocked = false, developerMode = false))
        assertEquals(true, available(unlocked = true, developerMode = false))
        assertEquals(true, available(unlocked = false, developerMode = true))
        assertEquals(true, available(unlocked = true, developerMode = true))
    }

    @Test
    fun `developer mode does not count as the seven-tap unlock`() {
        val context = context(mapOf("settings" to mapOf("developer_mode" to true)))
        assertEquals(false, AndroidCaptureEntry.isUnlocked(context))
        assertEquals(true, AndroidCaptureEntry.isAvailable(context))
    }
}
