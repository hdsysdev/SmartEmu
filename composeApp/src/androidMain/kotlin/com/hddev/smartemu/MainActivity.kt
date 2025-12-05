package com.hddev.smartemu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hddev.smartemu.di.AndroidAppModule
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PassportSimulatorApp()
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
    val repository = AndroidAppModule.provideNfcSimulatorRepository(context) as? com.hddev.smartemu.repository.AndroidNfcSimulatorRepository
    
    var permissionCallback: ((Boolean) -> Unit)? by remember { mutableStateOf(null) }
    
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        permissionCallback?.invoke(allGranted)
        permissionCallback = null
    }
    
    LaunchedEffect(repository) {
        repository?.setPermissionController(object : com.hddev.smartemu.repository.AndroidNfcSimulatorRepository.PermissionController {
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
    
    // Main App composable
    App(viewModel = viewModel)
}

@Composable
private fun ErrorBoundary(error: Throwable) {
    MaterialTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Application Error",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = error.message ?: "An unexpected error occurred",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(
                    onClick = { 
                        // In a real app, this could restart the activity or navigate to a safe state
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                ) {
                    Text("Restart App")
                }
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    PassportSimulatorApp()
}