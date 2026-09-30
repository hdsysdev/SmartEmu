package com.hddev.smartemu.data

/**
 * How the app looks and behaves, as opposed to the document it emulates.
 *
 * [developerMode] swaps the guided screens for the developer ones: every chip setting and chip profile, and the
 * reader's commands as they arrive. Only in developer mode does [exactCryptography] take effect, making the chip use
 * its profile's cryptography as it is, even where the r2w nfc-library can't read it; otherwise the chip adapts it
 * (see [ChipConfiguration]).
 */
data class AppSettings(
    val developerMode: Boolean = false,
    val exactCryptography: Boolean = true,
    val theme: AppTheme = AppTheme.SYSTEM,
    /** Whether the screen stays on while the chip is switched on, so a long read isn't cut short by the lock screen. */
    val keepScreenOn: Boolean = true,
    /** Whether reader sessions are recorded in the read history. */
    val keepReadHistory: Boolean = true,
    /** Whether the guided introduction has been seen, so it only opens the app the first time. */
    val introSeen: Boolean = false
) {
    /** Whether the chip uses its profile's cryptography as it is: only in developer mode, and only if asked to. */
    val usesExactCryptography: Boolean get() = developerMode && exactCryptography
}

enum class AppTheme(val displayName: String) {
    SYSTEM("Same as the phone"),
    LIGHT("Light"),
    DARK("Dark")
}
