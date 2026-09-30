package com.hddev.smartemu.ui.components

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
actual fun rememberNfcSwitch(): NfcSwitch {
    val context = LocalContext.current
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var enabled by remember(adapter) { mutableStateOf(adapter?.isEnabled == true) }

    // Turning NFC on from the quick settings doesn't pause the app, so listen for the change as well. The NFC
    // service isn't the system, so the receiver is exported; it only rereads the adapter, so a forged one is harmless
    DisposableEffect(context, adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enabled = adapter?.isEnabled == true
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Back from the system settings
    LifecycleResumeEffect(adapter) {
        enabled = adapter?.isEnabled == true
        onPauseOrDispose {}
    }

    return remember(context) {
        object : NfcSwitch {
            override val isOn: Boolean get() = enabled

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_NFC_SETTINGS)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        }
    }
}

@Composable
actual fun KeepScreenOnEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
