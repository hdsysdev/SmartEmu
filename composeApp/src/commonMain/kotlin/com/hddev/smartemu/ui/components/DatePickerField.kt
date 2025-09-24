package com.hddev.smartemu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import com.hddev.smartemu.utils.DateValidationUtils
import kotlinx.datetime.LocalDate

/**
 * Date picker field component that provides a text field with date picker functionality.
 * Handles date validation and formatting for passport dates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate?) -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var textFieldValue by remember(selectedDate) {
        mutableStateOf(
            TextFieldValue(
                text = selectedDate?.let { DateValidationUtils.formatForDisplay(it) } ?: ""
            )
        )
    }
    
    Column(modifier = modifier) {
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                // Try to parse the date as user types
                if (newValue.text.length == 10) { // DD/MM/YYYY format
                    val parsedDate = DateValidationUtils.parseDisplayDate(newValue.text)
                    onDateSelected(parsedDate)
                } else if (newValue.text.isEmpty()) {
                    onDateSelected(null)
                }
            },
            label = { Text(label) },
            placeholder = { Text("DD/MM/YYYY") },
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
                IconButton(
                    onClick = { showDatePicker = true },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = "Select date"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { showDatePicker = true }
        )
    }
    
    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { date ->
                onDateSelected(date)
                textFieldValue = TextFieldValue(
                    text = date?.let { DateValidationUtils.formatForDisplay(it) } ?: ""
                )
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            initialDate = selectedDate
        )
    }
}

/**
 * Date picker dialog component using Material3 DatePicker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    onDateSelected: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    initialDate: LocalDate? = null
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.let { date ->
            // Convert LocalDate to milliseconds since epoch
            kotlinx.datetime.Instant.parse("${date}T00:00:00Z").toEpochMilliseconds()
        }
    )
    
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selectedDateMillis = datePickerState.selectedDateMillis
                    val selectedDate = selectedDateMillis?.let { millis ->
                        // Convert milliseconds back to LocalDate
                        val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
                        instant.toString().substringBefore('T').let { dateString ->
                            LocalDate.parse(dateString)
                        }
                    }
                    onDateSelected(selectedDate)
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = false
        )
    }
}