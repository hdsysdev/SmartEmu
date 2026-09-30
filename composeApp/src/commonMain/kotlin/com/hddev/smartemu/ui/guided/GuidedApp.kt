package com.hddev.smartemu.ui.guided

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.ui.components.PassportFullScreenDialog
import com.hddev.smartemu.ui.components.rememberNfcSwitch
import com.hddev.smartemu.ui.navigation.PlatformBackHandler
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.launch

/**
 * The screens of the guided app, in the order they lead on from one another.
 */
enum class GuidedScreen { INTRO, HOME, PRESETS, HISTORY, EDIT, USE, HELP }

/**
 * The guided screens, out of developer mode: a guided journey for someone who isn't a developer, using this phone as a
 * passport that apps on another phone can read. It opens with a short introduction the first time, while [showIntro], and calls [onIntroFinished]
 * once it has been seen. The chip settings stay simple, and the emulation only runs while the passport is in use.
 */
@Composable
fun GuidedApp(
    viewModel: PassportSimulatorViewModel,
    showIntro: Boolean,
    onIntroFinished: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (showIntro) GuidedScreen.INTRO else GuidedScreen.HOME) }
    var showPhotoPage by rememberSaveable { mutableStateOf(false) }
    val nfcSwitch = rememberNfcSwitch()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
    }

    PlatformBackHandler(enabled = screen != GuidedScreen.HOME) {
        if (screen == GuidedScreen.INTRO) onIntroFinished()
        screen = GuidedScreen.HOME
    }

    // The developer messages become plain ones, or nothing where a screen already explains the problem
    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        viewModel.clearError()
        friendlyMessage(message)?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Each screen has its own top bar and insets; this one only hosts the messages
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { _ ->
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    // Onward screens come in from the right, and going back slides them away again
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (slideInHorizontally { it / 4 * direction } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 * direction } + fadeOut())
                },
                label = "guidedScreen"
            ) { shown ->
                when (shown) {
                    GuidedScreen.INTRO -> IntroScreen(
                        onFinished = {
                            onIntroFinished()
                            screen = GuidedScreen.HOME
                        }
                    )
                    GuidedScreen.HOME -> HomeScreen(
                        uiState = uiState,
                        nfcSwitch = nfcSwitch,
                        onUseSampleDetails = viewModel::autofillPassportData,
                        onChoosePreset = { screen = GuidedScreen.PRESETS },
                        onShowHistory = { screen = GuidedScreen.HISTORY },
                        onMakeGenuine = { viewModel.updateChipFault(ChipFault.NONE) },
                        onEdit = { screen = GuidedScreen.EDIT },
                        onShowPhotoPage = { showPhotoPage = true },
                        onUsePassport = { screen = GuidedScreen.USE },
                        onRequestPermission = viewModel::requestNfcPermissions,
                        onHelp = { screen = GuidedScreen.HELP },
                        onSettings = onOpenSettings
                    )
                    GuidedScreen.PRESETS -> PresetsScreen(
                        onSelected = { preset ->
                            viewModel.applyPreset(preset)
                            screen = GuidedScreen.HOME
                            showMessage("Now using: ${preset.title}")
                        },
                        onBack = { screen = GuidedScreen.HOME }
                    )
                    GuidedScreen.HISTORY -> HistoryScreen(
                        records = uiState.readHistory,
                        onClear = viewModel::clearReadHistory,
                        onBack = { screen = GuidedScreen.HOME }
                    )
                    GuidedScreen.EDIT -> EditPassportScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        onShowMessage = showMessage,
                        onDone = { screen = GuidedScreen.HOME }
                    )
                    GuidedScreen.USE -> UsePassportScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        nfcSwitch = nfcSwitch,
                        onShowPhotoPage = { showPhotoPage = true },
                        onEditDetails = { screen = GuidedScreen.EDIT },
                        onHelp = { screen = GuidedScreen.HELP },
                        onClose = { screen = GuidedScreen.HOME }
                    )
                    GuidedScreen.HELP -> HelpScreen(
                        uiState = uiState,
                        onReplayIntro = { screen = GuidedScreen.INTRO },
                        onBack = { screen = GuidedScreen.HOME }
                    )
                }
            }
        }

        if (showPhotoPage) {
            PassportFullScreenDialog(passportData = uiState.passportData, onDismiss = { showPhotoPage = false })
        }
    }
}

/**
 * What to tell someone who isn't a developer about an error the ViewModel reports, or null where the screens
 * already show the problem in their own way, or it isn't theirs to act on.
 */
internal fun friendlyMessage(error: String): String? = when {
    error.startsWith("Failed to start simulation") ->
        "The passport couldn't switch on. Check that NFC is turned on, then try again."
    error.startsWith("Failed to save passport") || error.startsWith("Failed to restore passport") ->
        "Your passport's details couldn't be saved on this phone."
    error.startsWith("Failed to request permissions") ->
        "This app wasn't allowed to use NFC. You can allow it in the phone's settings."
    // NFC availability and permission have their own banners, and the passport screen explains read problems
    else -> null
}
