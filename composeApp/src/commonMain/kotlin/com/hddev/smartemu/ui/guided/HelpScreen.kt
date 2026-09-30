package com.hddev.smartemu.ui.guided

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.rememberLogExporter
import com.hddev.smartemu.ui.components.rememberPlatformDiagnostics
import com.hddev.smartemu.utils.EventLogFormatter

private class Question(val icon: ImageVector, val title: String, val answers: List<String>)

private val questions = listOf(
    Question(
        Icons.Outlined.Nfc,
        "Nothing happens when I hold the phones together",
        listOf(
            "Check that NFC is on in both phones. On iPhones it's always on.",
            "Start the passport scan in the other app first, then hold the phones together when it asks.",
            "Slide this phone slowly over the back of the other. The sweet spot is often near the cameras, but " +
                "it varies from phone to phone.",
            "Take off thick cases, and anything metal such as a card holder or a phone ring."
        )
    ),
    Question(
        Icons.Outlined.CameraAlt,
        "The app can't scan the photo page",
        listOf(
            "Show the page large with Show photo page. The screen brightens by itself.",
            "Hold the other phone about 20 cm away, with the whole page inside its frame.",
            "Tilt this phone a little to get rid of reflections, and avoid bright lights behind you."
        )
    ),
    Question(
        Icons.Outlined.Key,
        "The app says the details don't match",
        listOf(
            "The app opens the passport with the details it scanned from the photo page. If it misread them, " +
                "or you typed them in yourself, they have to match this passport exactly.",
            "Scan the photo page again, or compare what the app has with the details on this phone."
        )
    ),
    Question(
        Icons.Outlined.GppMaybe,
        "The app says the passport isn't genuine or can't be verified",
        listOf(
            "That's expected. Real passports are signed by their government; this one is signed by a sample " +
                "authority that apps don't trust, so the app can't confirm it's official.",
            "Everything else, such as the details and photo, should still come through."
        )
    ),
    Question(
        Icons.Outlined.PhoneAndroid,
        "Which phones can I use?",
        listOf(
            "This phone must be an Android phone with NFC. Most phones that can pay contactless have it.",
            "The other phone can be any phone the reading app runs on, iPhones included."
        )
    ),
    Question(
        Icons.Outlined.PrivacyTip,
        "Is this a real passport?",
        listOf(
            "No. It's a virtual passport with the details you gave it, marked as a specimen. It can't be used as ID and " +
                "doesn't copy any real passport.",
            "The details and photo stay on this phone, unless you send a report."
        )
    )
)

/**
 * Answers to the questions someone is likely to have, a way to watch the introduction again, and a report of
 * recent reads to send to whoever supports them.
 */
@Composable
fun HelpScreen(
    uiState: PassportSimulatorUiState,
    onReplayIntro: () -> Unit,
    onBack: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(-1) }
    val exporter = rememberLogExporter()
    val platform = rememberPlatformDiagnostics()

    GuidedScaffold(title = "Help", onBack = onBack) {
        AppButton(
            text = "Watch how it works",
            onClick = onReplayIntro,
            icon = Icons.Filled.PlayCircle,
            emphasis = ButtonEmphasis.Medium,
            modifier = Modifier.fillMaxWidth()
        )

        questions.forEachIndexed { index, question ->
            ExpandableItem(
                title = question.title,
                expanded = expanded == index,
                onToggle = { expanded = if (expanded == index) -1 else index },
                icon = question.icon
            ) {
                question.answers.forEach { answer -> Text(answer, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        GuidedCard(title = "Still stuck?") {
            Text(
                "Send a report to the team that gave you this app. It holds the passport, photo included, " +
                    "and a record of what the other phone asked for, which helps them see what went wrong.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppButton(
                text = "Send a report",
                onClick = {
                    val context = EventLogFormatter.ReportContext(uiState, platform)
                    val format = EventLogFormatter.Format.TEXT
                    exporter.share(
                        EventLogFormatter.fileName(context.generatedAt, format.extension),
                        format.mimeType,
                        EventLogFormatter.format(format, uiState.nfcEvents, context)
                    )
                },
                icon = Icons.Filled.Share,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
