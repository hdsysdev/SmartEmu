package com.hddev.smartemu.ui.components

import androidx.compose.runtime.Composable

/**
 * Gets a report of the event log out of the app, in the platform's way.
 */
interface LogExporter {
    /** Puts [text] on the clipboard; false if the platform refused it, as it can for very long logs. */
    fun copy(label: String, text: String): Boolean

    /** Offers [text] as a file to other apps, such as mail or chat. */
    fun share(fileName: String, mimeType: String, text: String)

    /** Asks where to save [text] as a file, then writes it there. */
    fun save(fileName: String, mimeType: String, text: String)
}

@Composable
expect fun rememberLogExporter(): LogExporter

/**
 * The app version, device and NFC capabilities, for the head of a report.
 */
@Composable
expect fun rememberPlatformDiagnostics(): Map<String, String>
