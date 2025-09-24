package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Country selection dropdown component for passport forms.
 * Provides common country codes according to ISO 3166-1 alpha-3 standard.
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
    
    val countryOptions = listOf(
        "NLD" to "Netherlands",
        "USA" to "United States",
        "GBR" to "United Kingdom",
        "DEU" to "Germany",
        "FRA" to "France",
        "ESP" to "Spain",
        "ITA" to "Italy",
        "CAN" to "Canada",
        "AUS" to "Australia",
        "JPN" to "Japan",
        "BEL" to "Belgium",
        "CHE" to "Switzerland",
        "AUT" to "Austria",
        "SWE" to "Sweden",
        "NOR" to "Norway",
        "DNK" to "Denmark",
        "FIN" to "Finland",
        "IRL" to "Ireland",
        "PRT" to "Portugal",
        "POL" to "Poland"
    )
    
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it && enabled }
        ) {
            OutlinedTextField(
                value = countryOptions.find { it.first == selectedCountry }?.second ?: selectedCountry,
                onValueChange = { },
                readOnly = true,
                label = { Text(label) },
                enabled = enabled,
                isError = isError,
                supportingText = {
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                countryOptions.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text(displayName)
                                Text(
                                    text = code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onCountrySelected(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}