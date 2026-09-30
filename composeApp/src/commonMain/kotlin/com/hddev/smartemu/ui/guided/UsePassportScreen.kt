package com.hddev.smartemu.ui.guided

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.SimulationStatus
import com.hddev.smartemu.data.readerInfo
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.KeepScreenOnEffect
import com.hddev.smartemu.ui.components.NfcSwitch
import com.hddev.smartemu.ui.components.countryName
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** The steps of using the passport, in order. */
private enum class UseStep { SCAN, TAP, RESULT }

/** How long the reader may send nothing before a read of every part counts as finished. */
private const val IDLE_MILLIS = 2500L

/**
 * Using the passport, one step at a time: show the photo page to the other app's camera, hold the phones together while it
 * reads the chip, then compare what it read. The emulation runs for as long as this screen is shown, so that the
 * chip is ready whenever the other app wants it, and stops on leaving it.
 */
@Composable
fun UsePassportScreen(
    uiState: PassportSimulatorUiState,
    viewModel: PassportSimulatorViewModel,
    nfcSwitch: NfcSwitch,
    onShowPhotoPage: () -> Unit,
    onEditDetails: () -> Unit,
    onHelp: () -> Unit,
    onClose: () -> Unit
) {
    val documentType = uiState.passportData.documentType
    var step by rememberSaveable { mutableStateOf(UseStep.SCAN) }
    var photoPageShown by rememberSaveable { mutableStateOf(false) }
    // Only what the other phone does from here on counts, so that an earlier read doesn't show as this one
    var since by remember { mutableStateOf(Clock.System.now()) }

    // Each step starts at its top, with its picture
    val scrollState = rememberScrollState()
    LaunchedEffect(step) { scrollState.scrollTo(0) }

    KeepScreenOnEffect()
    val currentState by rememberUpdatedState(uiState)
    LaunchedEffect(nfcSwitch.isOn) {
        if (nfcSwitch.isOn && currentState.canStartSimulation()) viewModel.startSimulation()
    }
    DisposableEffect(Unit) {
        onDispose { if (viewModel.uiState.value.canStopSimulation()) viewModel.stopSimulation() }
    }

    val restart: () -> Unit = {
        since = Clock.System.now()
        if (uiState.canStartSimulation()) viewModel.startSimulation()
    }

    GuidedScaffold(
        title = when (step) {
            UseStep.SCAN -> "Step 1 of 2"
            UseStep.TAP -> "Step 2 of 2"
            UseStep.RESULT -> "${documentType.displayName} read"
        },
        onBack = onClose,
        scrollState = scrollState,
        actions = {
            IconButton(onClick = onHelp) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
            }
        },
        bottomBar = {
            BottomActions {
                when (step) {
                    UseStep.SCAN -> {
                        AppButton(
                            text = "Show ${documentType.scanPageName}",
                            onClick = {
                                photoPageShown = true
                                onShowPhotoPage()
                            },
                            icon = Icons.Filled.Fullscreen,
                            emphasis = if (photoPageShown) ButtonEmphasis.Medium else ButtonEmphasis.High,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        )
                        AppButton(
                            text = if (photoPageShown) "Next: hold phones together" else "Skip, my app doesn't scan it",
                            onClick = {
                                since = Clock.System.now()
                                step = UseStep.TAP
                            },
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            emphasis = if (photoPageShown) ButtonEmphasis.High else ButtonEmphasis.Low,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        )
                    }
                    UseStep.TAP -> AppButton(
                        text = "Back to scanning",
                        onClick = { step = UseStep.SCAN },
                        icon = Icons.Filled.DocumentScanner,
                        emphasis = ButtonEmphasis.Low,
                        modifier = Modifier.fillMaxWidth()
                    )
                    UseStep.RESULT -> {
                        AppButton(
                            text = "Done",
                            onClick = onClose,
                            icon = Icons.Filled.Check,
                            emphasis = ButtonEmphasis.High,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        )
                        AppButton(
                            text = "Read again",
                            onClick = {
                                photoPageShown = false
                                restart()
                                step = UseStep.SCAN
                            },
                            icon = Icons.Filled.Refresh,
                            emphasis = ButtonEmphasis.Low,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(350)) { it / 3 * direction } + fadeIn()) togetherWith
                    (slideOutHorizontally(tween(350)) { -it / 3 * direction } + fadeOut())
            },
            label = "useStep"
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (shown) {
                    UseStep.SCAN -> ScanStep(documentType)
                    UseStep.TAP -> TapStep(
                        uiState = uiState,
                        since = since,
                        nfcSwitch = nfcSwitch,
                        onFinished = { step = UseStep.RESULT },
                        onScanAgain = { step = UseStep.SCAN },
                        onTryAgain = restart,
                        onEditDetails = onEditDetails
                    )
                    UseStep.RESULT -> ResultStep(passportData = uiState.passportData)
                }
            }
        }
    }
}

@Composable
private fun ScanStep(documentType: DocumentType) {
    val page = documentType.scanPageName
    ScanPageIllustration()
    Headline(
        title = "Scan the $page",
        text = "On the other phone, open the app that reads ${if (documentType.isCard) "ID documents" else "passports"} " +
            "and start its scan. When it asks for the $page, tap Show $page and point its camera at this screen."
    )
    GuidedCard(title = "For a quick scan") {
        Tip(
            Icons.Outlined.CenterFocusStrong,
            if (documentType.isCard) {
                "Fit the whole card in the camera's frame, about 15 cm away."
            } else {
                "Fit the whole page in the camera's frame, about 20 cm away."
            }
        )
        Tip(Icons.Outlined.WbSunny, "Tilt this phone slightly if you see reflections or glare.")
        Tip(Icons.Outlined.BrightnessHigh, "The screen brightens by itself while it shows the page.")
    }
}

@Composable
private fun TapStep(
    uiState: PassportSimulatorUiState,
    since: Instant,
    nfcSwitch: NfcSwitch,
    onFinished: () -> Unit,
    onScanAgain: () -> Unit,
    onTryAgain: () -> Unit,
    onEditDetails: () -> Unit
) {
    // A reader that has read everything and then goes quiet has finished, even if the phones stay together
    val lastEvent = uiState.nfcEvents.lastOrNull()?.timestamp
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastEvent) {
        idle = false
        delay(IDLE_MILLIS)
        idle = true
    }
    val progress = remember(uiState.nfcEvents, since, idle) { ReadProgress.of(uiState.nfcEvents, since, idle) }
    LaunchedEffect(progress.finished) {
        if (progress.finished) {
            // Long enough to see the last tick land
            delay(900)
            onFinished()
        }
    }

    val status = uiState.simulationStatus
    // Stopped for a moment while the emulation starts; stopped for longer, it didn't start
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(status, uiState.isLoading) {
        stalled = false
        if (status == SimulationStatus.STOPPED && !uiState.isLoading) {
            delay(1500)
            stalled = true
        }
    }
    val failed = status == SimulationStatus.ERROR || stalled
    val mode = when {
        progress.finished -> TapMode.DONE
        progress.connected && progress.problem == null -> TapMode.CONNECTED
        else -> TapMode.DEMO
    }
    TapPhonesIllustration(mode = mode)

    val (title, text) = when {
        !nfcSwitch.isOn -> "Turn on NFC first" to "This phone can only be read with NFC switched on."
        failed -> "Something went wrong" to "The passport isn't switched on. Try again."
        status == SimulationStatus.STARTING || status == SimulationStatus.STOPPED -> "Getting ready…" to "Just a moment."
        progress.finished -> "All read!" to "The other app has everything it needs."
        progress.problem != null -> problemText(progress.problem)
        progress.connected -> "Hold still…" to "The other phone has found the passport. Keep the phones together " +
            "until every step below has a tick."
        else -> "Hold the phones back to back" to "When the other app asks for the passport, lay this phone on " +
            "the back of the other one, and keep them still."
    }
    // Read out as it changes, so that someone not looking at the screen hears how it's going
    Box(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        AnimatedContent(targetState = title to text, label = "tapHeadline") { (shownTitle, shownText) ->
            Headline(title = shownTitle, text = shownText)
        }
    }

    when {
        !nfcSwitch.isOn -> NoticeCard(
            title = "NFC is off",
            text = "Turn it on in quick settings or in the phone's settings.",
            icon = Icons.Outlined.Nfc,
            tone = NoticeTone.WARNING,
            action = { AppButton(text = "Open settings", onClick = nfcSwitch::openSettings) }
        )
        failed -> AppButton(
            text = "Try again",
            onClick = onTryAgain,
            icon = Icons.Filled.Refresh,
            emphasis = ButtonEmphasis.High,
            modifier = Modifier.fillMaxWidth()
        )
        else -> ProblemActions(progress.problem, onScanAgain = onScanAgain, onTryAgain = onTryAgain, onEditDetails = onEditDetails)
    }

    GuidedCard {
        ProgressStep("Found the other phone", if (progress.connected || progress.unlocked) StepState.DONE else StepState.PENDING)
        ProgressStep("Unlocked the passport", progress.unlockState())
        ProgressStep("Sent the personal details", progress.partState(PassportPart.DETAILS))
        ProgressStep("Sent the photo", progress.partState(PassportPart.PHOTO))
        ProgressStep("Proved the ${uiState.passportData.documentType.noun} is signed", progress.partState(PassportPart.SECURITY))
        // Only some apps ask for the anti-copy check, so it's listed once one has
        val provedGenuine = remember(uiState.nfcEvents, since) {
            uiState.nfcEvents.any { it.timestamp >= since && it.readerInfo() == NfcEvent.ACTIVE_AUTHENTICATION_ANSWERED }
        }
        if (provedGenuine) ProgressStep("Passed the anti-copy check", StepState.DONE)
    }

    var showTips by rememberSaveable { mutableStateOf(false) }
    ExpandableItem(
        title = "Nothing happening?",
        expanded = showTips,
        onToggle = { showTips = !showTips },
        icon = Icons.Outlined.SearchOff
    ) {
        Tip(Icons.Outlined.PanTool, "Slide this phone slowly around the back of the other. The sweet spot is often near the cameras.")
        Tip(Icons.Outlined.Layers, "Take off thick cases, and anything metal such as a card holder or ring.")
        Tip(Icons.Outlined.Nfc, "Check that NFC is on in the other phone too.")
        Tip(Icons.Outlined.ScreenLockPortrait, "Keep this app open with the screen on.")
    }
}

private fun problemText(problem: ReadProblem): Pair<String, String> = when (problem) {
    ReadProblem.WRONG_DETAILS -> "The details didn't match" to "The other app couldn't unlock the passport. " +
        "It usually means it scanned the page wrongly, or was given different details. Scan the page again."
    ReadProblem.UNSUPPORTED_TYPE -> "This type of passport isn't supported" to "The other app can't open the type " +
        "of passport chosen in More options. Change it to Standard, or keep it if you wanted to see how the app copes."
    ReadProblem.INTERRUPTED -> "The phones moved apart" to "Reading stopped before it finished. Hold the phones " +
        "together again and keep them still. The app may ask you to start its scan again."
}

@Composable
private fun ProblemActions(
    problem: ReadProblem?,
    onScanAgain: () -> Unit,
    onTryAgain: () -> Unit,
    onEditDetails: () -> Unit
) {
    AnimatedVisibility(visible = problem != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (problem) {
                ReadProblem.WRONG_DETAILS -> {
                    AppButton(text = "Scan again", onClick = onScanAgain, icon = Icons.Filled.DocumentScanner, modifier = Modifier.weight(1f))
                    AppButton(text = "Check details", onClick = onEditDetails, icon = Icons.Filled.Edit, emphasis = ButtonEmphasis.Low, modifier = Modifier.weight(1f))
                }
                ReadProblem.UNSUPPORTED_TYPE -> {
                    AppButton(text = "Change type", onClick = onEditDetails, icon = Icons.Outlined.Tune, modifier = Modifier.weight(1f))
                    AppButton(text = "Try again", onClick = onTryAgain, icon = Icons.Filled.Refresh, emphasis = ButtonEmphasis.Low, modifier = Modifier.weight(1f))
                }
                ReadProblem.INTERRUPTED, null -> AppButton(
                    text = "Try again",
                    onClick = onTryAgain,
                    icon = Icons.Filled.Refresh,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ResultStep(passportData: PassportData) {
    val noun = passportData.documentType.noun
    val fault = passportData.chipFault
    if (fault != ChipFault.NONE) {
        FakeResult(passportData)
        return
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SuccessMark(modifier = Modifier.size(140.dp))
    }
    Headline(
        title = "The app read the $noun",
        text = "The other app should now show these details, and the photo if you added one."
    )
    GuidedCard(title = "What the app should show") {
        ResultRow("Name", "${passportData.firstName} ${passportData.lastName}".trim())
        ResultRow(if (passportData.documentType.isCard) "Document number" else "Passport number", passportData.passportNumber.uppercase())
        ResultRow("Nationality", countryName(passportData.nationality))
        ResultRow("Date of birth", passportData.dateOfBirth?.let(::formatDate) ?: "")
        ResultRow("Expiry date", passportData.expiryDate?.let(::formatDate) ?: "")
    }
    NoticeCard(
        title = "\"Not verified\" is expected",
        text = "The app may say it can't confirm the $noun was issued by a government. That's normal: this " +
            "$noun is signed by a sample authority, not a real country.",
        icon = Icons.Outlined.Verified,
        tone = NoticeTone.INFO
    )
}

/**
 * The result for a document set up as a fake: whether the other app caught it is the point, so that's the question.
 */
@Composable
private fun FakeResult(passportData: PassportData) {
    val noun = passportData.documentType.noun
    val fault = passportData.chipFault
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Outlined.GppBad,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(120.dp)
        )
    }
    Headline(
        title = "Did the app refuse it?",
        text = "The other app read this $noun, which is set up as a fake. An app that checks properly should warn " +
            "that it isn't genuine, or refuse it."
    )
    GuidedCard(title = "What's wrong with it") {
        ResultRow(fault.friendlyName, fault.friendlyDescription)
    }
    NoticeCard(
        title = "If the app accepted it",
        text = if (fault.needsActiveAuthentication) {
            "The app may not do the anti-copy check. Many apps don't, so it can't tell a copy from the real thing."
        } else {
            "The app may not check the chip's signature. Some apps only read the details, so they can't tell a " +
                "fake from the real thing."
        },
        icon = Icons.Outlined.Info,
        tone = NoticeTone.INFO
    )
}

@Composable
private fun ResultRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

private val monthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)

/** A date written out, as in 1 January 2030, so that nobody has to guess which number is the month. */
internal fun formatDate(date: kotlinx.datetime.LocalDate): String =
    "${date.day} ${monthNames[date.month.ordinal]} ${date.year}"
