package com.hddev.smartemu.capture.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hddev.smartemu.repository.AndroidSettingsStore
import com.hddev.smartemu.ui.theme.SmartEmuTheme
import com.hddev.smartemu.ui.theme.isDark

/**
 * Hosts capture mode on its own, apart from the emulator's Activity: the phone can't emulate a document and read one
 * at once, and this keeps `FLAG_SECURE` and NFC reader mode confined to the capture screens. It is not exported, and
 * it closes at once unless developer mode is on or capture mode was unlocked.
 */
class CaptureActivity : ComponentActivity() {

    private val model: CaptureViewModel by viewModels {
        viewModelFactory { initializer { CaptureViewModel(applicationContext) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, and a blank thumbnail in the recent apps, for as long as this is open
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        // Not restored from a saved state: a capture is never kept in one
        super.onCreate(null)

        if (!AndroidCaptureEntry.isAvailable(this)) {
            finish()
            return
        }

        val theme = AndroidSettingsStore(this).load().theme
        setContent {
            SmartEmuTheme(darkTheme = theme.isDark()) {
                CaptureScreen(viewModel = model, onExit = ::finish)
            }
        }
    }

    // A key that was typed or is held for a read goes as soon as the screen isn't in front: switching apps, locking the
    // phone and rotating all stop here first, and the person starts again rather than leaving a key behind
    override fun onStop() {
        model.onScreenLeft()
        super.onStop()
    }

    override fun onDestroy() {
        model.onScreenLeft()
        super.onDestroy()
    }
}
