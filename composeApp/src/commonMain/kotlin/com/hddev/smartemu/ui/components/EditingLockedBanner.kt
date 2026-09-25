package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Explains why the passport can't be edited while the chip is being emulated, and offers to stop the emulation.
 */
@Composable
fun EditingLockedBanner(
    onStopEmulation: () -> Unit,
    stopEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = Icons.Filled.Lock, contentDescription = null)
            Text(
                text = "The chip is being emulated. Stop the emulation to make changes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            AppButton(
                text = "Stop",
                onClick = onStopEmulation,
                icon = Icons.Filled.Stop,
                emphasis = ButtonEmphasis.Low,
                destructive = true,
                enabled = stopEnabled
            )
        }
    }
}
