// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hddev.passportemu.capture.profile.BundledProfiles
import com.hddev.passportemu.capture.ui.AndroidCaptureEntry
import com.hddev.passportemu.di.AndroidAppModule
import com.hddev.passportemu.ui.capture.LocalCaptureEntry
import com.hddev.passportemu.ui.theme.isDark
import com.hddev.passportemu.viewmodel.PassportSimulatorViewModel

class MainActivity : ComponentActivity() {
    /**
     * The way into capture mode: in advanced mode, or from the settings once it is unlocked. Only builds made with
     * capture mode have one; in the others the app shows no trace of it.
     */
    private val captureEntry by lazy { if (AndroidCaptureEntry.inBuild) AndroidCaptureEntry(this) else null }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BundledProfiles.install(this)

        setContent {
            CompositionLocalProvider(LocalCaptureEntry provides captureEntry) {
                PassportSimulatorApp()
            }
        }
    }
}

@Composable
fun PassportSimulatorApp() {
    // Create ViewModel with dependency injection
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel = viewModel<PassportSimulatorViewModel> {
        AndroidAppModule.providePassportSimulatorViewModel(context)
    }
    
    // Handle Permission Requests
    val repository = AndroidAppModule.provideNfcSimulatorRepository(context) as? com.hddev.passportemu.repository.AndroidNfcSimulatorRepository
    
    var permissionCallback: ((Boolean) -> Unit)? by remember { mutableStateOf(null) }
    
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionCallback?.invoke(allGranted)
        permissionCallback = null
    }
    
    LaunchedEffect(repository) {
        repository?.setPermissionController(object : com.hddev.passportemu.repository.AndroidNfcSimulatorRepository.PermissionController {
            override fun requestPermissions(callback: (Boolean) -> Unit) {
                permissionCallback = callback
                
                val permissionsToRequest = mutableListOf(android.Manifest.permission.NFC)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(android.Manifest.permission.NFC_TRANSACTION_EVENT)
                }
                
                launcher.launch(permissionsToRequest.toTypedArray())
            }
        })
    }
    
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Keeps the phone from locking in the middle of a read, while the chip is on
    val view = LocalView.current
    val keepScreenOn = settings.keepScreenOn && uiState.simulationStatus.isActiveOrStarting()
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // The status and navigation bar icons follow the app's theme, which can differ from the phone's
    val darkTheme = settings.theme.isDark()
    val activity = context as? Activity
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    PassportEmuApp(viewModel = viewModel)
}

@Preview
@Composable
fun AppAndroidPreview() {
    PassportSimulatorApp()
}