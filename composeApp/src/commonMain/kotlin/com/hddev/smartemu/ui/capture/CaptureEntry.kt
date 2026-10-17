package com.hddev.smartemu.ui.capture

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The way from the emulator's screens into capture mode. Developer mode always offers it; out of developer mode it
 * stays hidden (see docs/capture-mode-plan.md, section 3, item 4) until seven taps unlock it. A platform provides it;
 * where nothing does, the app shows no trace of capture mode.
 */
interface CaptureEntry {
    /** Whether the seven-tap unlock has been done. Read from composition, so it is observable state. */
    val unlocked: Boolean

    /** One tap on the unlock target; the platform counts them and unlocks after enough in a row. */
    fun registerUnlockTap()

    /** Opens the capture screen. */
    fun open()

    /** Hides capture mode again. */
    fun lock()
}

val LocalCaptureEntry = staticCompositionLocalOf<CaptureEntry?> { null }
