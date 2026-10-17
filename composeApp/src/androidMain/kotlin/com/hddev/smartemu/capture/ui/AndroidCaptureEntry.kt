package com.hddev.smartemu.capture.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hddev.smartemu.repository.AndroidSettingsStore
import com.hddev.smartemu.ui.capture.CaptureEntry

/**
 * The way into capture mode: always open in developer mode, and otherwise hidden until seven taps on the "About" text
 * in the settings unlock it, which is remembered until [lock]. The unlock is kept apart from the app settings, so that
 * the settings model stays the emulator's.
 */
class AndroidCaptureEntry(private val context: Context) : CaptureEntry {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var unlocked by mutableStateOf(isUnlocked(context))
        private set

    private var taps = 0
    private var lastTap = 0L

    override fun registerUnlockTap() {
        if (unlocked) return
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTap > TAP_WINDOW_MS) 1 else taps + 1
        lastTap = now
        val remaining = TAPS_TO_UNLOCK - taps
        if (remaining <= 0) {
            preferences.edit().putBoolean(KEY_UNLOCKED, true).apply()
            unlocked = true
            taps = 0
            toast("Capture mode unlocked")
        } else if (remaining <= 3) {
            toast("$remaining more tap${if (remaining == 1) "" else "s"} to unlock capture mode")
        }
    }

    override fun open() {
        context.startActivity(Intent(context, CaptureActivity::class.java))
    }

    override fun lock() {
        preferences.edit().putBoolean(KEY_UNLOCKED, false).apply()
        unlocked = false
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREFERENCES_NAME = "capture_gate"
        private const val KEY_UNLOCKED = "unlocked"
        private const val TAPS_TO_UNLOCK = 7
        private const val TAP_WINDOW_MS = 2000L

        fun isUnlocked(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_UNLOCKED, false)

        /** Whether capture mode may open: developer mode is on, or the seven taps unlocked it. */
        fun isAvailable(context: Context): Boolean =
            isUnlocked(context) || AndroidSettingsStore(context).load().developerMode
    }
}
