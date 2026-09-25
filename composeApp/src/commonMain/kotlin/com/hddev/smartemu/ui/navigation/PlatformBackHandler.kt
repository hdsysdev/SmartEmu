package com.hddev.smartemu.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Calls [onBack] instead of the platform's default back action (on Android, closing the activity) while [enabled].
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
