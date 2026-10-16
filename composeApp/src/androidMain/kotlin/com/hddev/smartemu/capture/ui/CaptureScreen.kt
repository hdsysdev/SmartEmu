package com.hddev.smartemu.capture.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.capture.CaptureRecord
import com.hddev.smartemu.capture.CredentialField
import com.hddev.smartemu.capture.CredentialKind
import com.hddev.smartemu.capture.FileObservation
import com.hddev.smartemu.capture.NfcTechnology
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.KeepScreenOnEffect
import com.hddev.smartemu.ui.components.rememberNfcSwitch
import com.hddev.smartemu.ui.guided.BottomActions
import com.hddev.smartemu.ui.guided.GuidedCard
import com.hddev.smartemu.ui.guided.GuidedScaffold
import com.hddev.smartemu.ui.guided.NoticeCard
import com.hddev.smartemu.ui.guided.NoticeTone

/**
 * The capture mode's one screen, switching between its stages: the consent intro, the key entry (for a read with the
 * document's key), waiting for the document, reading, the "what will be saved" preview and the failures. Nothing here
 * is kept in saved instance state; what is typed as a key is held by [KeyEntryFields], which is not saved either.
 */
@Composable
fun CaptureScreen(viewModel: CaptureViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nfcSwitch = rememberNfcSwitch()
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // What the intro offers: null reads without a key. Not sensitive, so it needs no care beyond not being saved
    var keyChoice by remember { mutableStateOf<CredentialKind?>(null) }
    val fields = rememberKeyEntryFields()
    val today = remember { KeyEntryRules.today() }
    // A typed key is cleared the moment the key screen is left, however it was left
    LaunchedEffect(state is CaptureUiState.KeyEntry) {
        if (state !is CaptureUiState.KeyEntry) fields.clearAll()
    }

    // The reader is on only while a document is wanted or being read, and off whenever the screen is not in front
    val wantsReader = state is CaptureUiState.Waiting || state is CaptureUiState.Reading
    ReaderModeEffect(active = wantsReader && nfcSwitch.isOn, adapter = adapter, onTag = viewModel::onTagDiscovered)
    if (wantsReader) KeepScreenOnEffect()

    BackHandler {
        when (val current = state) {
            CaptureUiState.Intro -> onExit()
            is CaptureUiState.KeyEntry, is CaptureUiState.Waiting, is CaptureUiState.Reading -> viewModel.cancel()
            is CaptureUiState.Failed -> viewModel.reset()
            is CaptureUiState.Preview -> if (current.saved) viewModel.reset() else confirmDiscard = true
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this capture?") },
            text = { Text("It hasn't been saved or shared, so it will be gone.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } }
        )
    }

    (state as? CaptureUiState.Reading)?.canPrompt?.let { prompt ->
        CanConfirmDialog(prompt, onAnswer = { accept, understood -> viewModel.answerCanAttempt(accept, understood) })
    }

    GuidedScaffold(
        title = "Capture a document",
        onBack = {
            when (val current = state) {
                CaptureUiState.Intro -> onExit()
                is CaptureUiState.Preview -> if (current.saved) viewModel.reset() else confirmDiscard = true
                else -> viewModel.cancel()
            }
        },
        bottomBar = {
            BottomActions {
                when (val current = state) {
                    CaptureUiState.Intro -> {
                        AppButton(
                            text = if (keyChoice == null) "I understand, start" else "I understand, enter the key",
                            onClick = { keyChoice?.let(viewModel::chooseKey) ?: viewModel.startWaiting() },
                            icon = if (keyChoice == null) Icons.Outlined.Nfc else Icons.Outlined.Lock,
                            emphasis = ButtonEmphasis.High,
                            enabled = adapter != null && nfcSwitch.isOn,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is CaptureUiState.KeyEntry -> {
                        val ready = KeyEntryRules.isReady(
                            if (current.kind == CredentialKind.MRZ) fields.mrzProblems(today) else fields.canProblems(today)
                        )
                        AppButton(
                            text = "Continue to the document",
                            onClick = {
                                val credentials = if (current.kind == CredentialKind.MRZ) fields.buildMrz() else fields.buildCan()
                                viewModel.startWithKey(credentials)
                            },
                            icon = Icons.Outlined.Nfc,
                            emphasis = ButtonEmphasis.High,
                            enabled = ready && adapter != null && nfcSwitch.isOn,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is CaptureUiState.Waiting, is CaptureUiState.Reading -> AppButton(
                        text = "Stop",
                        onClick = viewModel::cancel,
                        emphasis = ButtonEmphasis.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is CaptureUiState.Preview -> PreviewActions(current, viewModel)
                    is CaptureUiState.Failed -> {
                        if (current.partial != null) AppButton(
                            text = "See what was captured so far",
                            onClick = viewModel::reviewPartial,
                            emphasis = ButtonEmphasis.Medium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        AppButton(
                            text = "Start over",
                            onClick = viewModel::reset,
                            emphasis = ButtonEmphasis.High,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) {
        // Said beside whatever the screen shows: the document changed between two taps
        if (notice == CaptureNotice.DIFFERENT_DOCUMENT) {
            NoticeCard(
                title = "This looks like a different document, starting again",
                text = "It is not the one the first part of this capture was read from, so that part was dropped and this is a " +
                    "new capture. Nothing was tried on it with the key you typed for the first document.",
                icon = Icons.Outlined.Warning,
                tone = NoticeTone.WARNING
            )
        }
        when (val current = state) {
            CaptureUiState.Intro -> Intro(
                viewModel, keyChoice, { keyChoice = it }, adapter != null, nfcSwitch.isOn, nfcSwitch::openSettings
            )
            is CaptureUiState.KeyEntry -> KeyEntry(current, fields, today)
            is CaptureUiState.Waiting -> Waiting(current, nfcSwitch.isOn, nfcSwitch::openSettings)
            is CaptureUiState.Reading -> Reading(current)
            is CaptureUiState.Preview -> Preview(current, viewModel.matchSummary(current.record))
            is CaptureUiState.Failed -> Failure(current)
        }
    }
}

/**
 * Turns NFC reader mode on while [active] and the screen is resumed, and off as it pauses or leaves, so that the
 * phone's reader and the emulator never compete and no tag is handled with the screen hidden.
 */
@Composable
private fun ReaderModeEffect(active: Boolean, adapter: NfcAdapter?, onTag: (Tag) -> Unit) {
    val activity = LocalContext.current.findActivity()
    val currentOnTag by rememberUpdatedState(onTag)
    LifecycleResumeEffect(active, adapter, activity) {
        if (active && adapter != null && activity != null && adapter.isEnabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
            adapter.enableReaderMode(activity, { tag -> currentOnTag(tag) }, flags, extras)
        }
        onPauseOrDispose {
            if (adapter != null && activity != null) runCatching { adapter.disableReaderMode(activity) }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun Intro(
    viewModel: CaptureViewModel,
    keyChoice: CredentialKind?,
    onKeyChoice: (CredentialKind?) -> Unit,
    hasNfc: Boolean,
    nfcOn: Boolean,
    openNfcSettings: () -> Unit
) {
    Text("Record how a real document's chip behaves", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Use your own passport or ID card, or a colleague's who has agreed. Never a customer's.",
        style = MaterialTheme.typography.bodyLarge
    )
    if (!hasNfc) {
        NoticeCard(
            title = "This phone has no NFC",
            text = "Capturing needs a phone that can read NFC.",
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.PROBLEM
        )
    } else if (!nfcOn) {
        NoticeCard(
            title = "NFC is off",
            text = "Turn NFC on to read a document.",
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.WARNING,
            action = { AppButton(text = "Open NFC settings", onClick = openNfcSettings, emphasis = ButtonEmphasis.Medium) }
        )
    }
    GuidedCard(title = "How to read it") {
        ReadChoiceRow(
            selected = keyChoice == null,
            title = "Read without a key (no personal data)",
            text = "The usual way. No MRZ or CAN is needed or asked for, and the holder's details are never read.",
            onSelect = { onKeyChoice(null) }
        )
        ReadChoiceRow(
            selected = keyChoice != null,
            title = "Read with the document's key (MRZ or CAN)",
            text = "Also shows what is behind the lock: the data groups, the algorithms and whether Active and Chip " +
                "Authentication work. Needs the key printed on the document. Your own document or a consenting colleague's only.",
            onSelect = { onKeyChoice(keyChoice ?: CredentialKind.MRZ) }
        )
        if (keyChoice != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = keyChoice == CredentialKind.MRZ, onClick = { onKeyChoice(CredentialKind.MRZ) })
                Text("MRZ (passports and most ID cards)", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = keyChoice == CredentialKind.CAN, onClick = { onKeyChoice(CredentialKind.CAN) })
                Text("CAN (the 6 digits on an ID card's front)", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    GuidedCard(title = "What is read") {
        Bullet("How the chip announces itself and which security protocols it offers")
        Bullet("The files anyone can read without a key, and how the chip answers a few harmless test commands")
        Bullet("This phone's model and NFC limits")
        if (keyChoice != null) {
            Bullet("With the key: which data groups the chip has (and their rounded sizes), the algorithms and curves it uses, its signing certificate's type and size, and whether Active and Chip Authentication succeed")
            Text(
                AuthenticatedSummary.MEMORY_ONLY_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "No MRZ, CAN or other key is needed or asked for. The document's holder details are never read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    GuidedCard(title = "What is never saved") {
        RedactionManifestList(includeAuthenticated = keyChoice != null)
    }
    GuidedCard(title = "How to hold it") {
        Bullet("Hold the document flat against the back of the phone, near the NFC area, with any cover or case off.")
        Bullet("Keep it still until the reading is done. If it slips, the app asks you to tap again.")
        Bullet("The reading stops by itself if the document reports it is locked.")
    }
    Text(
        "You see everything that would be saved, and choose to save, share or discard it, before anything leaves " +
            "your control.",
        style = MaterialTheme.typography.bodyMedium
    )
    val saved = remember { viewModel.savedCount() }
    var deleted by remember { mutableStateOf(false) }
    if (saved > 0 && !deleted) {
        TextButton(onClick = { viewModel.deleteAllSaved(); deleted = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text("Delete $saved capture${if (saved == 1) "" else "s"} saved on this phone", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Waiting(state: CaptureUiState.Waiting, nfcOn: Boolean, openNfcSettings: () -> Unit) {
    if (!nfcOn) {
        NoticeCard(
            title = "NFC is off",
            text = "Turn NFC on to read a document.",
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.WARNING,
            action = { AppButton(text = "Open NFC settings", onClick = openNfcSettings, emphasis = ButtonEmphasis.Medium) }
        )
    }
    if (state.canMayHaveCounted) {
        NoticeCard(
            title = "The CAN attempt may have counted",
            text = "The document moved away while the CAN was being tried, so the card may already have counted that as a " +
                "failed try. It is not tried again by itself: when you tap again you are asked, and must confirm, before the CAN is sent a second time.",
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.WARNING
        )
    }
    if (state.retry) {
        NoticeCard(
            title = "Keep the document still and tap again",
            text = "The document moved away. Hold it against the phone again and the reading carries on by itself." +
                if (state.withKey != null) " You don't need to type the key again; it is held in memory only until you stop or leave this screen." else "",
            icon = Icons.Outlined.Nfc,
            tone = NoticeTone.WARNING
        )
    } else {
        Icon(Icons.Outlined.Nfc, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Waiting for the document", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Hold the document flat against the back of the phone and keep it still.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (state.withKey != null) {
            Text(
                "The key is held in memory only. It is erased when the reading ends, when you stop, and when you leave this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Reading(state: CaptureUiState.Reading) {
    Text("Reading the document", style = MaterialTheme.typography.headlineSmall)
    Text("Keep it still against the phone.", style = MaterialTheme.typography.bodyLarge)
    val progress = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Text(
        "Step ${(state.done + 1).coerceAtMost(state.total.coerceAtLeast(1))} of ${state.total.coerceAtLeast(1)}: ${state.step}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Failure(state: CaptureUiState.Failed) {
    val (title, text) = when (state.failure) {
        CaptureFailure.BLOCKED ->
            "The document reported it is locked" to "Stopped to protect it. Nothing more was sent to the document."
        CaptureFailure.TAG_LOST ->
            "Keep the document still and tap again" to
                "The document kept moving away, so the reading couldn't finish. Try again, holding it flat and still."
        CaptureFailure.UNSUPPORTED ->
            "This isn't a document the app can read" to
                "The chip doesn't look like an electronic passport or ID card."
        CaptureFailure.CAN_DECLINED ->
            "Stopped before reading, nothing was sent" to
                "You chose not to try the CAN, so it was never sent to the card and its counters are untouched. " +
                "The public part of the reading had already run."
        CaptureFailure.UNEXPECTED ->
            "Something went wrong" to "The reading stopped. Nothing was saved. Try again."
    }
    NoticeCard(
        title = title,
        text = text,
        icon = Icons.Outlined.Warning,
        tone = when (state.failure) {
            CaptureFailure.BLOCKED -> NoticeTone.PROBLEM
            CaptureFailure.CAN_DECLINED -> NoticeTone.INFO
            else -> NoticeTone.WARNING
        }
    )
    if (state.partial != null) {
        Text(
            "Some of the reading finished. You can look at it and decide whether to keep it.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PreviewActions(preview: CaptureUiState.Preview, viewModel: CaptureViewModel) {
    AppButton(
        text = "Share capture",
        onClick = viewModel::shareJson,
        icon = Icons.Outlined.Share,
        emphasis = ButtonEmphasis.High,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        AppButton(
            text = if (preview.saved) "Saved" else "Save",
            onClick = viewModel::save,
            icon = Icons.Outlined.Save,
            enabled = !preview.saved,
            emphasis = ButtonEmphasis.Medium,
            modifier = Modifier.weight(1f)
        )
        AppButton(
            text = "Report",
            onClick = viewModel::shareReport,
            icon = Icons.Outlined.Description,
            emphasis = ButtonEmphasis.Medium,
            modifier = Modifier.weight(1f)
        )
    }
    AppButton(
        text = if (preview.saved) "Delete this capture" else "Discard",
        onClick = viewModel::discard,
        icon = Icons.Outlined.Delete,
        destructive = true,
        emphasis = ButtonEmphasis.Low,
        modifier = Modifier.fillMaxWidth()
    )
}

/** The consent moment: every field that will be saved, and the list of what is left out. */
@Composable
private fun Preview(preview: CaptureUiState.Preview, match: MatchSummary) {
    val record = preview.record
    Text("What will be saved", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Nothing has left this phone. Look it over, then share, save or discard it.",
        style = MaterialTheme.typography.bodyLarge
    )
    if (preview.saved) {
        NoticeCard(
            title = "Saved on this phone",
            text = "Delete this capture removes it again.",
            icon = Icons.Outlined.CheckCircle,
            tone = NoticeTone.INFO
        )
    }
    record.aborted?.let {
        NoticeCard(
            title = "The reading stopped early",
            text = "Only what was read before it stopped is in this capture. Steps that didn't run are absent, not failed.",
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.WARNING
        )
    }

    GuidedCard(title = "The capture") {
        KeyValue("Month", "${record.captureMonth} (the day is not kept)")
        KeyValue("Capture ID", "${record.captureId} (random)")
        KeyValue("Source", record.source.name)
        KeyValue("Probe pack", "${record.probePack.id}, version ${record.probePack.version}")
        KeyValue("Fingerprint", record.fingerprint.ifEmpty { "not computed" })
        KeyValue("Compared with profiles", match.description.removePrefix("Compared with profiles: "))
    }
    GuidedCard(title = "This phone") {
        KeyValue("Model", record.reader.model)
        KeyValue("Android", record.reader.androidVersion)
        KeyValue("Max command length", "${record.reader.controllerMaxTransceiveLength ?: "unknown"} (phone's NFC controller)")
        KeyValue(
            "Extended-length APDUs",
            (record.reader.controllerExtendedLengthSupported?.let { if (it) "yes" else "no" } ?: "unknown") +
                " (phone's NFC controller)"
        )
        Text(
            "These are the phone's limits, not the document's.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    GuidedCard(title = "How the document connected") {
        KeyValue("Technology", if (record.link.technology == NfcTechnology.NFC_B) "NFC-B" else "NFC-A")
        KeyValue("Historical bytes", record.link.historicalBytes ?: "none")
        KeyValue(
            "UID random",
            when (record.link.uidRandom) {
                true -> "yes"
                false -> "no"
                null -> "unconfirmed"
            } + " (only this yes/no is saved, never the UID)"
        )
    }
    record.authenticated?.let { AuthenticatedPreview(it) }
    GuidedCard(title = "Files anyone can read") {
        FileRow("EF.CardAccess", record.files.cardAccess)
        FileRow("EF.ATR/INFO", record.files.atrInfo)
        FileRow("EF.DIR", record.files.dir)
        Text(
            "Sizes are rounded to ${com.hddev.smartemu.capture.Redaction.SIZE_BUCKET}-byte ranges.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    GuidedCard(title = "Test commands and the document's answers") {
        if (record.probes.isEmpty()) {
            Text("None ran.", style = MaterialTheme.typography.bodyMedium)
        }
        record.probes.forEach { probe ->
            Text("${probe.probeId} (tier ${probe.tier})", style = MaterialTheme.typography.titleSmall)
            probe.steps.forEach { step ->
                KeyValue(
                    step.stepId,
                    (step.statusWord?.let(CaptureReport::statusWord) ?: "no answer") +
                        (step.responseSize?.let { ", ${CaptureReport.sizeText(it)}" } ?: "")
                )
            }
        }
    }
    GuidedCard(title = "Left out on purpose") {
        RedactionManifestList(record)
    }
    if (record.authenticated != null) {
        Text(
            AuthenticatedSummary.MEMORY_ONLY_NOTE,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RedactionManifestList(record: CaptureRecord? = null, includeAuthenticated: Boolean = false) {
    val entries = record?.redactions ?: (
        com.hddev.smartemu.capture.RedactionManifest.entries +
            if (includeAuthenticated) com.hddev.smartemu.capture.RedactionManifest.authenticatedEntries else emptyList()
        )
    entries.forEach { entry ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Column {
                Text(entry.item, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileRow(name: String, file: FileObservation?) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        when {
            file == null -> Text("Not read", style = MaterialTheme.typography.bodyMedium)
            file.contentsHex == null -> Text(
                "Not offered (${CaptureReport.statusWord(file.selectStatusWord)})",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> {
                Text(
                    "Read, ${CaptureReport.sizeText(file.size)}. Tap to ${if (expanded) "hide" else "show"} the file's raw bytes.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
                Text(
                    file.contentsHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadChoiceRow(selected: Boolean, title: String, text: String, onSelect: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Typing the key: the MRZ's three fields or the CAN, checked as they are typed, with the same rules the capture uses. */
@Composable
private fun KeyEntry(state: CaptureUiState.KeyEntry, fields: KeyEntryFields, today: kotlinx.datetime.LocalDate) {
    val isMrz = state.kind == CredentialKind.MRZ
    Text(if (isMrz) "Type the MRZ details" else "Type the CAN", style = MaterialTheme.typography.headlineSmall)
    Text(
        if (isMrz) {
            "From the machine-readable zone of the document: the document number, the date of birth and the date of " +
                "expiry. Dates are six digits: year, month, day (YYMMDD)."
        } else {
            "The card access number, six digits, printed on the front of the ID card. It is tried once only."
        },
        style = MaterialTheme.typography.bodyLarge
    )
    if (state.problems.isNotEmpty()) {
        NoticeCard(
            title = "The key needs a fix before anything is sent",
            text = state.problems.joinToString("\n") { it.message },
            icon = Icons.Outlined.Warning,
            tone = NoticeTone.WARNING
        )
    }
    val problems = KeyEntryRules.shown(if (isMrz) fields.mrzProblems(today) else fields.canProblems(today))
    if (isMrz) {
        Text(
            "Type each field with its check digit on the end, as the MRZ line prints it (for example L898902C36, " +
                "7408122, 9406236). The check digits are required: they catch typing mistakes before anything is sent, " +
                "and a mistyped key can count as a failed attempt on the document.",
            style = MaterialTheme.typography.bodySmall
        )
        SensitiveInputField(
            state = fields.documentNumber,
            label = "Document number",
            visible = fields.showText,
            imeAction = ImeAction.Next,
            errorText = KeyEntryRules.messageFor(CredentialField.DOCUMENT_NUMBER, problems),
            modifier = Modifier.fillMaxWidth()
        )
        SensitiveInputField(
            state = fields.dateOfBirth,
            label = "Date of birth (YYMMDD)",
            visible = fields.showText,
            numeric = true,
            imeAction = ImeAction.Next,
            errorText = KeyEntryRules.messageFor(CredentialField.DATE_OF_BIRTH, problems),
            modifier = Modifier.fillMaxWidth()
        )
        SensitiveInputField(
            state = fields.dateOfExpiry,
            label = "Date of expiry (YYMMDD)",
            visible = fields.showText,
            numeric = true,
            errorText = KeyEntryRules.messageFor(CredentialField.DATE_OF_EXPIRY, problems),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        SensitiveInputField(
            state = fields.can,
            label = "CAN (6 digits)",
            visible = fields.showText,
            numeric = true,
            errorText = KeyEntryRules.messageFor(CredentialField.CAN, problems),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = fields.showText, onCheckedChange = { fields.showText = it })
        Text("Show what I type", style = MaterialTheme.typography.bodyMedium)
    }
    GuidedCard(title = "What happens to it") {
        Bullet("It is held in memory only: not in saved state, not in logs, and the keyboard is told not to learn it.")
        Bullet("It is erased when the reading ends, when you stop, and when you leave this screen.")
        Bullet("It is never saved or shared. Neither is any personal data read from the document.")
        if (!isMrz) {
            Bullet("The CAN is tried once. You will be asked to confirm before it is sent. Some ID cards count wrong tries and the limit is not known.")
        }
    }
}

/**
 * The question before the CAN is sent. Cancel is the safe answer and the prominent one, and a tap outside gives it. If
 * the CAN was begun before and cut short, or failed earlier in this session, the chip may already have counted a failure:
 * the person must tick that they understand before "Try the CAN again" does anything.
 */
@Composable
private fun CanConfirmDialog(prompt: CanPrompt, onAnswer: (accept: Boolean, understandsRisk: Boolean) -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAnswer(false, false) },
        title = { Text(if (prompt.requiresExtraConfirmation) "Try the CAN again?" else "Try the CAN once?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This tries the code once, and only once. It will not be tried again after that.")
                Text(
                    "How many wrong tries an ID card allows is not known for every issuer, and a wrong code may count " +
                        "against the card. Go on only if you are sure the number is the one printed on the card."
                )
                if (prompt.interruptedBefore) {
                    Text(
                        "An earlier try with this CAN was cut off when the card moved away. It may have counted against " +
                            "the card, and this would be a second try."
                    )
                }
                if (prompt.previouslyFailed) {
                    Text("A try with a CAN already failed in this session. This may be the same document: another failure counts again.")
                }
                if (prompt.requiresExtraConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = understood, onCheckedChange = { understood = it })
                        Text("I understand and want to try the CAN again.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        // Cancel is the prominent button, so the careful answer is the easy one
        confirmButton = { Button(onClick = { onAnswer(false, false) }) { Text("Cancel") } },
        dismissButton = {
            TextButton(
                onClick = { onAnswer(true, understood) },
                enabled = !prompt.requiresExtraConfirmation || understood
            ) { Text(if (prompt.requiresExtraConfirmation) "Try the CAN again" else "Try the CAN once") }
        }
    )
}

/** The authenticated part of the preview: how the key fared, what was seen, and that the key and the data weren't kept. */
@Composable
private fun AuthenticatedPreview(observation: com.hddev.smartemu.capture.AuthenticatedObservation) {
    val notice = AuthenticatedSummary.notice(AuthenticatedSummary.outcome(observation))
    NoticeCard(
        title = notice.title,
        text = notice.text,
        icon = if (notice.problem) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
        tone = if (notice.problem) NoticeTone.PROBLEM else NoticeTone.INFO
    )
    GuidedCard(title = "Read with the document's key") {
        Text(AuthenticatedSummary.MEMORY_ONLY_NOTE, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Sizes are rounded to ${com.hddev.smartemu.capture.Redaction.SIZE_BUCKET}-byte ranges. Only the structure is kept: " +
                "algorithms, curves, key sizes, which files exist and what the chip answered.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AuthenticatedSummary.sections(observation).forEach { section ->
            Spacer(Modifier.height(4.dp))
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            section.rows.forEach { KeyValue(it.label, it.value) }
        }
    }
}
