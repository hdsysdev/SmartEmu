package com.hddev.smartemu.ui.components

import androidx.compose.runtime.Composable

/**
 * Whether the device's NFC is switched on, kept up to date as the user turns it on or off, and a way to the
 * system setting that does so.
 */
interface NfcSwitch {
    /** Whether NFC is on; false also when the device has no NFC at all. */
    val isOn: Boolean

    /** Opens the system's NFC setting, or the wireless settings where it has none of its own. */
    fun openSettings()
}

@Composable
expect fun rememberNfcSwitch(): NfcSwitch

/**
 * Keeps the screen on while in the composition, as a phone emulating a passport only answers readers while its
 * screen is on.
 */
@Composable
expect fun KeepScreenOnEffect()
