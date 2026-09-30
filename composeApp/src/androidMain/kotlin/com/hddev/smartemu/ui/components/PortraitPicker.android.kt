package com.hddev.smartemu.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.ExifInterface
import android.media.FaceDetector
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hddev.smartemu.data.Portrait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "PortraitPicker"

/**
 * Sizes and JPEG qualities tried in turn until the portrait fits in [Portrait.MAX_JPEG_SIZE]: the best-practice size
 * first, as long as its quality holds up, then the minimum size.
 */
private val ENCODINGS =
    listOf(85, 75, 65, 55).map { Triple(Portrait.WIDTH, Portrait.HEIGHT, it) } +
        listOf(85, 75, 65, 55, 45, 35).map { Triple(Portrait.MIN_WIDTH, Portrait.MIN_HEIGHT, it) }

/** Widest image searched for a face; detection is slow on a full-size photo and no better. */
private const val FACE_DETECTION_WIDTH = 640

@Composable
actual fun rememberPortraitPicker(onPicked: (Portrait) -> Unit, onFailed: (String) -> Unit): PortraitPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnFailed by rememberUpdatedState(onFailed)
    // Where the camera app writes the selfie; deleted once it's read
    val selfieFile = remember(context) { File(context.cacheDir, "portraits/selfie.jpg") }

    val load: (Uri, File?) -> Unit = { uri, temporaryFile ->
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readPortrait(context, uri) }.also { temporaryFile?.delete() }
            }
            result
                .onSuccess { currentOnPicked(it) }
                .onFailure {
                    Log.w(TAG, "Couldn't read a portrait from $uri", it)
                    currentOnFailed("Couldn't use that photo")
                }
        }
    }
    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri, null)
    }
    val camera = rememberLauncherForActivityResult(TakeSelfie()) { taken ->
        if (taken) load(Uri.fromFile(selfieFile), selfieFile) else selfieFile.delete()
    }

    return remember(context, library, camera) {
        object : PortraitPicker {
            override val canTakeSelfie: Boolean =
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

            override fun chooseFromLibrary() {
                library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            override fun takeSelfie() {
                selfieFile.parentFile?.mkdirs()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", selfieFile)
                try {
                    camera.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No camera app", e)
                    currentOnFailed("No camera app to take a selfie with")
                }
            }
        }
    }
}

/**
 * Takes a photo, asking for the front camera with the extras most camera apps honour; none is part of the platform.
 */
private class TakeSelfie : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra("android.intent.extras.CAMERA_FACING", 1)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
}

/**
 * Decodes the photo upright and turns it into a Token Frontal [Portrait]: scaled and cropped so that the eyes are
 * where ISO/IEC 19794-5 puts them, then encoded as a JPEG small enough for EF.DG2. If no face is found, the
 * photo is cropped to 3:4 around where a face usually is instead.
 */
private fun readPortrait(context: Context, uri: Uri): Portrait {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }

    // Decodes a large photo at no less than the portrait's size, rather than at full resolution
    val shortSide = minOf(bounds.outWidth, bounds.outHeight)
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (shortSide / (sampleSize * 2) >= Portrait.WIDTH && longSide / (sampleSize * 2) >= Portrait.HEIGHT) sampleSize *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: error("Couldn't decode the image")

    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val upright = applyOrientation(decoded, orientation)
    val eyes = findEyes(upright)

    return ENCODINGS.asSequence()
        .map { (width, height, quality) ->
            val image = if (eyes != null) tokenFrontal(upright, eyes, width, height) else centreCrop(upright, width, height)
            val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            Portrait(jpeg = jpeg, width = width, height = height)
        }
        .firstOrNull { it.jpeg.size <= Portrait.MAX_JPEG_SIZE }
        ?: error("The portrait doesn't fit in EF.DG2")
}

/** The point midway between a face's eyes, and the distance between them, in pixels of the photo they're in. */
private class Eyes(val midpoint: PointF, val distance: Float)

/** The eyes of the most prominent face in [photo], or null if there's no face to be confident of. */
private fun findEyes(photo: Bitmap): Eyes? {
    // The detector wants an RGB 565 image of even width
    val scale = minOf(1f, FACE_DETECTION_WIDTH.toFloat() / photo.width)
    val width = (photo.width * scale).toInt() and 1.inv()
    val height = (photo.height * scale).toInt()
    if (width < 2 || height < 1) return null
    val small = Bitmap.createScaledBitmap(photo, width, height, true).copy(Bitmap.Config.RGB_565, false)

    val faces = arrayOfNulls<FaceDetector.Face>(1)
    if (FaceDetector(width, height, faces.size).findFaces(small, faces) == 0) return null
    val face = faces[0]?.takeIf { it.confidence() >= FaceDetector.Face.CONFIDENCE_THRESHOLD } ?: return null

    val midpoint = PointF().also(face::getMidPoint)
    return Eyes(PointF(midpoint.x / scale, midpoint.y / scale), face.eyesDistance() / scale)
}

/**
 * [photo] scaled and cropped to [width] x [height] with [eyes] where a Token Frontal image has them. Where that
 * reaches beyond the photo, the portrait is white.
 */
private fun tokenFrontal(photo: Bitmap, eyes: Eyes, width: Int, height: Int): Bitmap {
    val scale = Portrait.EYE_DISTANCE * width / eyes.distance
    val matrix = Matrix().apply {
        setTranslate(-eyes.midpoint.x, -eyes.midpoint.y)
        postScale(scale, scale)
        postTranslate(width / 2f, Portrait.EYE_LINE * width)
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.WHITE)
            drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

/** [photo] cropped to 3:4 around the centre horizontally and the upper third vertically, scaled to [width] x [height]. */
private fun centreCrop(photo: Bitmap, width: Int, height: Int): Bitmap {
    val cropWidth = minOf(photo.width, photo.height * width / height)
    val cropHeight = minOf(photo.height, photo.width * height / width)
    val cropped = Bitmap.createBitmap(photo, (photo.width - cropWidth) / 2, (photo.height - cropHeight) / 3, cropWidth, cropHeight)
    return Bitmap.createScaledBitmap(cropped, width, height, true)
}

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

actual fun Portrait.toImageBitmap(): ImageBitmap? =
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

@Composable
actual fun ScanningDisplayEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        // Inside a dialog the dialog has its own window, which is the one shown on top
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val window = dialogWindow ?: view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val previousBrightness = window.attributes.screenBrightness
        if (dialogWindow != null) {
            // A dialog's window otherwise stops at the system bars, leaving the screen behind showing where they were
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            dialogWindow.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
