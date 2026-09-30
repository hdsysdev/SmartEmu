package com.hddev.smartemu.ui.guided

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import smartemu.composeapp.generated.resources.Res
import smartemu.composeapp.generated.resources.passportemu_mascot

/** A gentle welcome animation; Compose's animation clock respects the system animation scale. */
@Composable
fun PassportEmuMascot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "emuWelcome")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOut), RepeatMode.Reverse),
        label = "emuBob"
    )
    Image(
        painter = painterResource(Res.drawable.passportemu_mascot),
        contentDescription = "The PassportEmu mascot, an emu bird holding a passport",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .graphicsLayer { translationY = bob * 4.dp.toPx() }
    )
}
