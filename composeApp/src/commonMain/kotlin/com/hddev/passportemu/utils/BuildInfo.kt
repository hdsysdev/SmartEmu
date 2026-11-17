// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.utils

/** Whether this is a debug build. Release builds keep personal or chip data out of the console and Logcat. */
internal expect val isDebugBuild: Boolean
