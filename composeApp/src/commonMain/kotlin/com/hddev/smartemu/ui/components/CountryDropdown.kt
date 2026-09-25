package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.hddev.smartemu.data.Countries

/**
 * English name of a country code, or the code itself if it is not a known code.
 */
fun countryName(code: String): String = Countries.nameOf(code)

/**
 * Country selection for passport forms, offering every issuing state and nationality code from ICAO Doc 9303.
 * Typing filters the list by name or code, so that nobody has to scroll through 250 countries; the keyboard's
 * Done key picks the first match. The selected country's code is shown beside its name, as it appears in the MRZ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryDropdown(
    label: String,
    selectedCountry: String,
    onCountrySelected: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val matches = remember(query) { Countries.all.filter { it.matches(query) }.sortedBy { it.rank(query) } }
    val select: (String) -> Unit = { code ->
        onCountrySelected(code)
        expanded = false
        query = ""
        focusManager.clearFocus()
    }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { open ->
                expanded = open && enabled
                if (!expanded) query = ""
            }
        ) {
            OutlinedTextField(
                // While the list is open the field is a search box; otherwise it shows the selected country
                value = if (expanded) query else countryName(selectedCountry),
                onValueChange = {
                    query = it
                    expanded = true
                },
                label = { Text(label) },
                placeholder = { Text("Type to search") },
                suffix = if (!expanded && selectedCountry.isNotBlank()) ({ Text(selectedCountry) }) else null,
                singleLine = true,
                enabled = enabled,
                isError = isError,
                // Only reserve space below the field for an error
                supportingText = errorMessage?.let { error -> { Text(text = error, color = MaterialTheme.colorScheme.error) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { matches.firstOrNull()?.let { select(it.first) } }),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                }
            ) {
                if (matches.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No country matches \"$query\"") },
                        onClick = {},
                        enabled = false
                    )
                }
                matches.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        trailingIcon = {
                            if (code == selectedCountry) {
                                Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected")
                            } else {
                                Text(
                                    text = code,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = { select(code) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

/**
 * Whether the country's name contains [query], or its code starts with it, ignoring case; everything matches a
 * blank query.
 */
private fun Pair<String, String>.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || second.contains(needle, ignoreCase = true) || first.startsWith(needle, ignoreCase = true)
}

/**
 * Where the country belongs among the matches for [query], lowest first: its exact code, then names starting with
 * the query, then the rest, so that Done on "pol" picks Poland rather than French Polynesia. The sort is stable, so
 * each group stays in name order.
 */
private fun Pair<String, String>.rank(query: String): Int {
    val needle = query.trim()
    return when {
        needle.isEmpty() -> 0
        first.equals(needle, ignoreCase = true) -> 0
        second.startsWith(needle, ignoreCase = true) -> 1
        else -> 2
    }
}
