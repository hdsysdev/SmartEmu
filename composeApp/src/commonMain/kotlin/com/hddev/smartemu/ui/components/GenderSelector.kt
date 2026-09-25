package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The sex codes of the MRZ, ICAO Doc 9303 Part 4, with their names. */
private val GenderOptions = listOf("M" to "Male", "F" to "Female", "X" to "Unspecified")

/**
 * Sex of the holder, as a segmented button, so that all three options are visible and one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenderSelector(
    selectedGender: String,
    onGenderSelected: (String) -> Unit,
    enabled: Boolean = true,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Sex",
            style = MaterialTheme.typography.labelLarge,
            color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            GenderOptions.forEachIndexed { index, (code, name) ->
                SegmentedButton(
                    selected = selectedGender == code,
                    onClick = { onGenderSelected(code) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = GenderOptions.size),
                    enabled = enabled,
                    // No check mark: the filled segment shows the choice, and a third of a phone is narrow for "Unspecified"
                    icon = {},
                    label = { Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        errorMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
