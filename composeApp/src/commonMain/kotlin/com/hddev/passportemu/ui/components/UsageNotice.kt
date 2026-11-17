// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** What PassportEmu is for and what it must not be used for; shown once on first run and under Settings > About. */
object UsageNotice {
    const val TITLE = "Before you start"

    val paragraphs = listOf(
        "PassportEmu is a testing tool. It emulates the chip of a passport or ID card so that you can test reader " +
            "and verification software that you own or are authorised to test.",
        "Every emulated document is signed with PassportEmu's own test certificates, whose private keys are " +
            "published with its source code. It fails the signature check against any real country's certificates, " +
            "and it is not an identity document.",
        "Presenting an emulated document as a genuine one may be a crime.",
        "What you enter stays on this phone. PassportEmu is free software under the GNU General Public License, " +
            "version 3 or later, and comes with no warranty."
    )
}

/** The usage notice, which must be acknowledged; [dismissible] lets it close without acknowledging, once it has been. */
@Composable
fun UsageNoticeDialog(onAcknowledge: () -> Unit, dismissible: Boolean = false) {
    AlertDialog(
        onDismissRequest = { if (dismissible) onAcknowledge() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
        title = { Text(UsageNotice.TITLE) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                UsageNotice.paragraphs.forEach { paragraph ->
                    Text(paragraph, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text(if (dismissible) "Close" else "I understand") }
        }
    )
}

/** The project's licence and the licences of the libraries and font it ships, as the platform bundles them. */
@Composable
fun OpenSourceNoticesDialog(onDismiss: () -> Unit) {
    val notices = rememberOpenSourceNotices()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licences") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    notices ?: "The licence notices are not included in this build. See THIRD_PARTY_LICENSES.md in the source code.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** The bundled licence texts joined into one, or null where the platform has none. */
@Composable
expect fun rememberOpenSourceNotices(): String?
