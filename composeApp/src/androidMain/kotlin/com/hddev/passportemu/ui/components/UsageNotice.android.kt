// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The notices folder of the assets, which the build fills from LICENSE, THIRD_PARTY_LICENSES.md and licenses/. */
private const val NOTICES_FOLDER = "licenses"

/** The third-party notices first, then the other licence files, then the project's own licence, which is longest. */
private fun noticeOrder(name: String): Int = when (name) {
    "THIRD_PARTY_LICENSES.md" -> 0
    "LICENSE" -> 2
    else -> 1
}

@Composable
actual fun rememberOpenSourceNotices(): String? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val assets = context.assets
            assets.list(NOTICES_FOLDER).orEmpty()
                .sortedWith(compareBy(::noticeOrder).thenBy { it })
                .joinToString("\n\n————————\n\n") { name ->
                    assets.open("$NOTICES_FOLDER/$name").use { it.readBytes().decodeToString() }
                }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
