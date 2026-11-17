// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.ui.navigation

import androidx.compose.runtime.Composable

/**
 * Calls [onBack] instead of the platform's default back action (on Android, closing the activity) while [enabled].
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
