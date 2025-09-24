package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Gender selection dropdown component for passport forms.
 * Provides standard gender options according to passport specifications.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenderDropdown(
    selectedGender: String,
    onGenderSelected: (String) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val genderOptions = listOf(
        "M" to "Male",
        "F" to "Female",
        "X" to "Unspecified"
    )
    
    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it && enabled }
        ) {
            OutlinedTextField(
                value = genderOptions.find { it.first == selectedGender }?.second ?: "",
                onValueChange = { },
                readOnly = true,
                label = { Text("Gender") },
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
                genderOptions.forEach { (code, displayName) ->
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        onClick = {
                            onGenderSelected(code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}