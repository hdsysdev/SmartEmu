package com.hddev.smartemu.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level destinations of the app, shown in the navigation bar or rail, in the order a user sets up an emulation.
 * [PASSPORT] is the start destination. [selectedIcon] is filled and [icon] outlined, as Material navigation expects.
 */
enum class AppDestination(
    val label: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    /** The holder's details and the sample data page. */
    PASSPORT("Passport", "Passport details", Icons.Outlined.Badge, Icons.Filled.Badge),

    /** Access control and the PACE settings of the emulated chip. */
    CHIP("Chip", "Chip security", Icons.Outlined.Shield, Icons.Filled.Shield),

    /** Starting and stopping the emulation, and the reader's commands as they arrive. */
    EMULATOR("Emulate", "Emulate", Icons.Outlined.Contactless, Icons.Filled.Contactless);

    companion object {
        val start = PASSPORT
    }
}
