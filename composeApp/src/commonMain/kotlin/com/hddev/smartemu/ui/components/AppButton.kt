package com.hddev.smartemu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * How much an [AppButton] stands out: [High] for the one main action of a screen, [Medium] for the actions of a
 * section, [Low] for the actions within a row or a banner.
 */
enum class ButtonEmphasis { High, Medium, Low }

/**
 * The app's button: a label with an optional leading icon, in one of three [ButtonEmphasis] levels, so that every
 * screen's buttons share their shape, icon size and spacing. [destructive] colours it for actions that stop or
 * delete something.
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    emphasis: ButtonEmphasis = ButtonEmphasis.Medium,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(text = text, maxLines = 1)
    }
    val error = MaterialTheme.colorScheme.error

    when (emphasis) {
        ButtonEmphasis.High -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (destructive) {
                ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError)
            } else {
                ButtonDefaults.buttonColors()
            },
            contentPadding = if (icon != null) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding,
            content = content
        )
        ButtonEmphasis.Medium -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) error else Color.Unspecified),
            border = ButtonDefaults.outlinedButtonBorder(enabled).let { if (destructive && enabled) BorderStroke(it.width, error) else it },
            contentPadding = if (icon != null) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding,
            content = content
        )
        ButtonEmphasis.Low -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) error else Color.Unspecified),
            contentPadding = if (icon != null) ButtonDefaults.TextButtonWithIconContentPadding else ButtonDefaults.TextButtonContentPadding,
            content = content
        )
    }
}

/**
 * An icon-only action, such as closing or rotating a full-screen view, styled to sit on any background.
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
