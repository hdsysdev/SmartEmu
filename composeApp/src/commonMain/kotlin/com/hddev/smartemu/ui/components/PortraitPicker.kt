package com.hddev.smartemu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.hddev.smartemu.data.Portrait

/**
 * Gets the holder's portrait from the device, cropped and scaled to a [Portrait] the chip can serve.
 */
interface PortraitPicker {
    /** Whether the device has a camera to take a selfie with. */
    val canTakeSelfie: Boolean

    /** Lets the user choose a photo from their library. */
    fun chooseFromLibrary()

    /** Opens the camera, on the front lens where the camera app allows it. */
    fun takeSelfie()
}

/**
 * [onPicked] receives the portrait once the user has chosen or taken a photo; [onFailed] explains why a photo
 * couldn't be used. Nothing is called if the user cancels.
 */
@Composable
expect fun rememberPortraitPicker(onPicked: (Portrait) -> Unit, onFailed: (String) -> Unit): PortraitPicker

/**
 * The portrait decoded for display, or null if its JPEG can't be decoded.
 */
expect fun Portrait.toImageBitmap(): ImageBitmap?

/**
 * Keeps the screen on, at full brightness and without system bars, while in the composition, so that a camera
 * can read what it shows.
 */
@Composable
expect fun ScanningDisplayEffect()
