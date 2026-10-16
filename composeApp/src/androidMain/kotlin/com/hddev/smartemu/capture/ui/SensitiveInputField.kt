package com.hddev.smartemu.capture.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Holds something that must not outlive its use, such as an MRZ or a CAN.
 *
 * It lives in `remember`, never `rememberSaveable`, so it can't reach saved instance state, and it is cleared when its
 * composable leaves the composition as well as after use. [shape] is applied to every edit (to cap the length, say); it
 * must not drop characters silently where the person would want to be told they are wrong.
 */
@Stable
class SensitiveInputState(private val shape: (String) -> String = { it }) {
    var text by mutableStateOf("")
        private set

    fun update(value: String) {
        text = shape(value)
    }

    fun clear() {
        text = ""
    }

    /** Hands the text to [block] and clears it afterwards, even if [block] throws. */
    inline fun <T> useAndClear(block: (String) -> T): T {
        try {
            return block(text)
        } finally {
            clear()
        }
    }
}

@Composable
fun rememberSensitiveInputState(shape: (String) -> String = { it }): SensitiveInputState {
    val state = remember { SensitiveInputState(shape) }
    DisposableEffect(state) { onDispose { state.clear() } }
    return state
}

/**
 * A text field for an MRZ or CAN: the keyboard is a password one, so it doesn't learn from, suggest or correct what
 * is typed, and the text is hidden unless [visible]. [errorText], if any, is shown under the field in the error colour.
 */
@Composable
fun SensitiveInputField(
    state: SensitiveInputState,
    label: String,
    modifier: Modifier = Modifier,
    visible: Boolean = false,
    numeric: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
    errorText: String? = null
) {
    OutlinedTextField(
        value = state.text,
        onValueChange = state::update,
        label = { Text(label) },
        modifier = modifier,
        enabled = enabled,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = imeAction
        )
    )
}
