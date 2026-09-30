package com.hddev.smartemu.ui.guided

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hddev.smartemu.ui.theme.successColor
import kotlinx.coroutines.delay

/**
 * The colours the illustrations draw with, taken from the theme so that they suit both light and dark.
 */
private data class Palette(
    val body: Color,
    val screen: Color,
    val viewfinder: Color,
    val page: Color,
    val ink: Color,
    val accent: Color,
    val wave: Color,
    val cover: Color,
    val gold: Color,
    val success: Color
)

@Composable
private fun palette(): Palette {
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    return Palette(
        body = if (dark) Color(0xFF5B606A) else Color(0xFF2B2F36),
        screen = if (dark) Color(0xFF23272D) else Color.White,
        viewfinder = Color(0xFF30343B),
        page = Color(0xFFF4EFE1),
        ink = Color(0xFF6E7179),
        accent = colors.primary,
        wave = colors.secondary,
        cover = Color(0xFF1F3F66),
        gold = Color(0xFFD9B45A),
        success = MaterialTheme.successColor
    )
}

/** Width to height of a passport data page, ICAO 9303 TD3. */
private const val PAGE_RATIO = 125f / 88f

/**
 * This phone showing a passport cover, bobbing gently with contactless waves coming off it: the phone becomes a
 * passport.
 */
@Composable
fun PassportPhoneIllustration(modifier: Modifier = Modifier) {
    val palette = palette()
    val transition = rememberInfiniteTransition(label = "passportPhone")
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOut), RepeatMode.Reverse),
        label = "bob"
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "wave"
    )

    IllustrationCanvas(modifier, "A phone showing a passport, sending out contactless waves") {
        val phoneHeight = size.height * 0.84f
        val phoneSize = Size(phoneHeight * 0.5f, phoneHeight)
        val topLeft = Offset(size.width * 0.36f - phoneSize.width / 2, (size.height - phoneHeight) / 2 + bob * size.height * 0.02f)
        val phone = Rect(topLeft, phoneSize)

        drawWaves(
            center = Offset(phone.right, phone.top + phone.height * 0.4f),
            maxRadius = size.width * 0.34f,
            phase = wave,
            color = palette.wave,
            startAngle = -40f,
            sweep = 80f
        )
        drawPhone(phone, palette) { screen ->
            drawRect(palette.cover, screen.topLeft, screen.size)
            drawPassportCover(screen.deflate(screen.width * 0.14f), palette)
        }
    }
}

/**
 * This phone, as the passport, and a second phone with the app that reads it, with dots running from one to the
 * other: the two phones a read needs.
 */
@Composable
fun TwoPhonesIllustration(modifier: Modifier = Modifier) {
    val palette = palette()
    val transition = rememberInfiniteTransition(label = "twoPhones")
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "flow"
    )

    IllustrationCanvas(modifier, "Two phones: this one shows a passport, the other runs the app that reads it") {
        val phoneHeight = size.height * 0.8f
        val phoneSize = Size(phoneHeight * 0.5f, phoneHeight)
        val top = (size.height - phoneHeight) / 2
        val left = Rect(Offset(size.width * 0.24f - phoneSize.width / 2, top), phoneSize)
        val right = Rect(Offset(size.width * 0.76f - phoneSize.width / 2, top), phoneSize)

        drawPhone(left, palette) { screen ->
            drawRect(palette.cover, screen.topLeft, screen.size)
            drawPassportCover(screen.deflate(screen.width * 0.14f), palette)
        }
        drawPhone(right, palette) { screen -> drawAppScreen(screen, palette, progress = flow) }

        // Dots travelling from the passport to the app
        val y = size.height / 2
        val start = left.right + size.width * 0.03f
        val end = right.left - size.width * 0.03f
        val count = 5
        repeat(count) { index ->
            val fraction = ((index + flow) / count)
            val x = start + (end - start) * fraction
            val fade = 1f - kotlin.math.abs(fraction - 0.5f) * 2f
            drawCircle(palette.accent.copy(alpha = 0.25f + 0.75f * fade), radius = size.height * 0.022f, center = Offset(x, y))
        }
    }
}

/**
 * What the other phone's camera sees: this phone showing the passport's photo page, framed by the viewfinder,
 * with a scan line sweeping down it and the two lines of code at the bottom lighting up as it reads them.
 */
@Composable
fun ScanPageIllustration(modifier: Modifier = Modifier) {
    val palette = palette()
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3000
                0f at 0 using FastOutSlowInEasing
                1f at 2000
                1f at 3000
            }
        ),
        label = "sweep"
    )

    IllustrationCanvas(modifier, "The other phone's camera framing the passport photo page on this phone") {
        val phoneHeight = size.height * 0.96f
        val phoneSize = Size(phoneHeight * 0.52f, phoneHeight)
        val camera = Rect(Offset((size.width - phoneSize.width) / 2, (size.height - phoneHeight) / 2), phoneSize)

        drawPhone(camera, palette) { screen ->
            drawRect(palette.viewfinder, screen.topLeft, screen.size)

            // This phone, turned on its side, as the camera sees it
            val passportPhoneWidth = screen.width * 0.9f
            val passportPhone = Rect(
                Offset(screen.center.x - passportPhoneWidth / 2, screen.center.y - passportPhoneWidth * 0.3f),
                Size(passportPhoneWidth, passportPhoneWidth * 0.6f)
            )
            drawPhone(passportPhone, palette.copy(body = Color(0xFF15171B))) { inner ->
                drawRect(Color(0xFF15171B), inner.topLeft, inner.size)
                val pageWidth = minOf(inner.width * 0.94f, inner.height * 0.94f * PAGE_RATIO)
                val page = Rect(
                    Offset(inner.center.x - pageWidth / 2, inner.center.y - pageWidth / PAGE_RATIO / 2),
                    Size(pageWidth, pageWidth / PAGE_RATIO)
                )
                val scanY = page.top + page.height * sweep
                val mrzLit = ((sweep - 0.72f) / 0.28f).coerceIn(0f, 1f)
                drawDataPage(page, palette, mrzHighlight = mrzLit)
                if (sweep < 1f) {
                    val band = page.height * 0.18f
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, palette.accent.copy(alpha = 0.35f)),
                            startY = scanY - band,
                            endY = scanY
                        ),
                        topLeft = Offset(page.left, scanY - band),
                        size = Size(page.width, band)
                    )
                    drawLine(palette.accent, Offset(page.left, scanY), Offset(page.right, scanY), strokeWidth = page.height * 0.025f)
                }
            }

            drawViewfinderCorners(passportPhone.inflate(screen.width * 0.03f), color = Color.White, length = screen.width * 0.12f)

            // Once the page is read, a tick on the other phone
            if (sweep >= 1f) {
                val badgeCenter = Offset(screen.center.x, screen.bottom - screen.height * 0.14f)
                val radius = screen.width * 0.1f
                drawCircle(palette.success, radius, badgeCenter)
                drawCheck(badgeCenter, radius * 0.9f, Color.White, progress = 1f)
            }
        }
    }
}

/** How [TapPhonesIllustration] shows the phones. */
enum class TapMode {
    /** Shows, over and over, this phone being laid on the other. */
    DEMO,

    /** The phones held together, with the chip talking. */
    CONNECTED,

    /** The phones held together, the read finished. */
    DONE
}

/**
 * This phone being laid back to back on the other, with contactless waves where they meet. In [TapMode.DEMO] it
 * shows the movement over and over; in the other modes the phones stay together.
 */
@Composable
fun TapPhonesIllustration(mode: TapMode, modifier: Modifier = Modifier) {
    val palette = palette()
    val together = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        if (mode == TapMode.DEMO) {
            while (true) {
                together.animateTo(1f, tween(1000, easing = EaseInOut))
                delay(1800)
                together.animateTo(0f, tween(700, easing = EaseInOut))
                delay(600)
            }
        } else {
            together.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    val transition = rememberInfiniteTransition(label = "tap")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (mode == TapMode.CONNECTED) 900 else 1500, easing = LinearEasing)),
        label = "wave"
    )
    val success = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        if (mode == TapMode.DONE) success.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) else success.snapTo(0f)
    }

    val description = when (mode) {
        TapMode.DEMO -> "This phone being laid back to back on the other phone"
        TapMode.CONNECTED -> "The two phones held together, talking"
        TapMode.DONE -> "The two phones held together, reading finished"
    }
    IllustrationCanvas(modifier, description) {
        val phoneHeight = size.height * 0.74f
        val phoneSize = Size(phoneHeight * 0.5f, phoneHeight)
        val other = Rect(Offset(size.width * 0.6f - phoneSize.width / 2, size.height * 0.2f), phoneSize)
        val apart = Offset(other.left - size.width * 0.4f, other.top - size.height * 0.06f)
        val joined = Offset(other.left - size.width * 0.07f, other.top - size.height * 0.12f)
        val t = together.value
        val passport = Rect(Offset(apart.x + (joined.x - apart.x) * t, apart.y + (joined.y - apart.y) * t), phoneSize)

        // The other phone lies screen up; this one comes down on top of it
        drawPhone(other, palette) { screen -> drawAppScreen(screen, palette, progress = if (mode == TapMode.DONE) 1f else wave * t) }

        val contact = Offset((other.center.x + passport.center.x) / 2, other.top + other.height * 0.3f)
        if (t > 0.9f && mode != TapMode.DONE) {
            drawWaves(contact, maxRadius = size.width * 0.3f, phase = wave, color = palette.wave, alpha = (t - 0.9f) * 10f)
        }

        // A soft shadow lifts this phone off the other
        val lift = size.height * 0.03f * (1f - t * 0.6f)
        drawRoundRect(
            Color.Black.copy(alpha = 0.18f),
            topLeft = passport.topLeft + Offset(lift, lift * 1.4f),
            size = passport.size,
            cornerRadius = CornerRadius(passport.width * 0.16f)
        )
        drawPhone(passport, palette) { screen ->
            drawRect(palette.cover, screen.topLeft, screen.size)
            drawPassportCover(screen.deflate(screen.width * 0.14f), palette)
        }

        if (success.value > 0f) {
            val radius = size.height * 0.12f * (0.6f + 0.4f * success.value)
            drawCircle(palette.success, radius, contact)
            drawCheck(contact, radius * 0.9f, Color.White, progress = success.value)
        }
    }
}

/**
 * A tick drawn in a circle, stroke by stroke, then held: the read went through.
 */
@Composable
fun SuccessMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.successColor
    val progress = remember { Animatable(0f) }
    val ring = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        ring.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) {
        delay(150)
        progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    Canvas(modifier = modifier.semantics { contentDescription = "Success" }) {
        val radius = size.minDimension / 2 * ring.value
        drawCircle(color.copy(alpha = 0.16f), radius, center)
        drawCircle(color, radius * 0.72f, center)
        drawCheck(center, radius * 0.62f, Color.White, progress = progress.value)
    }
}

/**
 * Rings spreading out from the middle, while the passport waits for the other phone.
 */
@Composable
fun WaitingPulse(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "waiting")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier = modifier) {
        repeat(3) { index ->
            val p = (phase + index / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.5f), radius = size.minDimension / 2 * p, center = center)
        }
    }
}

@Composable
private fun IllustrationCanvas(modifier: Modifier, description: String, onDraw: DrawScope.() -> Unit) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .semantics { contentDescription = description },
        onDraw = onDraw
    )
}

/**
 * A phone seen from the front: a rounded body, the screen inset in it, and a camera hole. [content] draws on the
 * screen, clipped to it.
 */
private fun DrawScope.drawPhone(bounds: Rect, palette: Palette, content: DrawScope.(Rect) -> Unit) {
    val short = minOf(bounds.width, bounds.height)
    val corner = short * 0.16f
    val bezel = short * 0.05f
    drawRoundRect(palette.body, bounds.topLeft, bounds.size, CornerRadius(corner))
    val screen = bounds.deflate(bezel)
    val screenPath = Path().apply {
        addRoundRect(RoundRect(screen, CornerRadius(corner - bezel)))
    }
    clipPath(screenPath) {
        drawRect(palette.screen, screen.topLeft, screen.size)
        content(screen)
    }
    val hole = if (bounds.width < bounds.height) {
        Offset(screen.center.x, screen.top + bezel * 1.4f)
    } else {
        Offset(screen.left + bezel * 1.4f, screen.center.y)
    }
    drawCircle(palette.body, bezel * 0.7f, hole)
}

/**
 * The front of a passport booklet in [bounds]: a gold crest, a title line and the chip symbol.
 */
private fun DrawScope.drawPassportCover(bounds: Rect, palette: Palette) {
    val stroke = bounds.width * 0.04f
    val crest = Offset(bounds.center.x, bounds.top + bounds.height * 0.38f)
    val crestRadius = bounds.width * 0.26f
    drawCircle(palette.gold, crestRadius, crest, style = Stroke(stroke))
    drawCircle(palette.gold, crestRadius * 0.45f, crest)
    // Title
    drawLine(
        palette.gold,
        Offset(bounds.left + bounds.width * 0.2f, bounds.top + bounds.height * 0.08f),
        Offset(bounds.right - bounds.width * 0.2f, bounds.top + bounds.height * 0.08f),
        strokeWidth = stroke * 1.2f,
        cap = StrokeCap.Round
    )
    // Chip symbol: a rounded rectangle with a circle in it
    val chipWidth = bounds.width * 0.3f
    val chipTopLeft = Offset(bounds.center.x - chipWidth / 2, bounds.bottom - bounds.height * 0.2f)
    val chipSize = Size(chipWidth, chipWidth * 0.62f)
    drawRoundRect(palette.gold, chipTopLeft, chipSize, CornerRadius(chipSize.height * 0.2f), style = Stroke(stroke * 0.8f))
    drawCircle(palette.gold, chipSize.height * 0.22f, Offset(chipTopLeft.x + chipSize.width / 2, chipTopLeft.y + chipSize.height / 2), style = Stroke(stroke * 0.8f))
}

/**
 * A passport data page in [bounds]: the photo, lines standing for the holder's details, and the two lines of code
 * at the bottom, tinted [Palette.accent] by [mrzHighlight] from 0 to 1.
 */
private fun DrawScope.drawDataPage(bounds: Rect, palette: Palette, mrzHighlight: Float = 0f) {
    val w = bounds.width
    val h = bounds.height
    drawRoundRect(palette.page, bounds.topLeft, bounds.size, CornerRadius(h * 0.05f))

    fun line(x: Float, y: Float, length: Float, color: Color = palette.ink.copy(alpha = 0.55f), thickness: Float = h * 0.035f) {
        drawLine(color, Offset(bounds.left + w * x, bounds.top + h * y), Offset(bounds.left + w * (x + length), bounds.top + h * y), strokeWidth = thickness, cap = StrokeCap.Round)
    }

    line(0.06f, 0.1f, 0.4f, palette.cover.copy(alpha = 0.7f), h * 0.045f)

    // Photo: a head and shoulders
    val photo = Rect(Offset(bounds.left + w * 0.06f, bounds.top + h * 0.2f), Size(w * 0.24f, h * 0.48f))
    drawRect(palette.ink.copy(alpha = 0.18f), photo.topLeft, photo.size)
    translate(photo.left, photo.top) {
        drawCircle(palette.ink.copy(alpha = 0.55f), photo.width * 0.2f, Offset(photo.width / 2, photo.height * 0.38f))
        drawOval(
            palette.ink.copy(alpha = 0.55f),
            topLeft = Offset(photo.width * 0.16f, photo.height * 0.66f),
            size = Size(photo.width * 0.68f, photo.height * 0.6f)
        )
    }

    line(0.36f, 0.24f, 0.36f)
    line(0.36f, 0.36f, 0.48f)
    line(0.36f, 0.48f, 0.28f)
    line(0.36f, 0.6f, 0.4f)

    // The machine readable zone
    val mrzColor = lerp(palette.ink.copy(alpha = 0.8f), palette.accent, mrzHighlight)
    val dash = PathEffect.dashPathEffect(floatArrayOf(w * 0.018f, w * 0.008f))
    listOf(0.8f, 0.9f).forEach { y ->
        drawLine(
            mrzColor,
            Offset(bounds.left + w * 0.06f, bounds.top + h * y),
            Offset(bounds.right - w * 0.06f, bounds.top + h * y),
            strokeWidth = h * 0.05f,
            pathEffect = dash
        )
    }
    if (mrzHighlight > 0f) {
        drawRoundRect(
            palette.accent.copy(alpha = 0.12f * mrzHighlight),
            topLeft = Offset(bounds.left + w * 0.03f, bounds.top + h * 0.73f),
            size = Size(w * 0.94f, h * 0.24f),
            cornerRadius = CornerRadius(h * 0.03f)
        )
    }
}

/**
 * The screen of the app reading the passport: a coloured top bar, a passport outline and a progress bar filled to
 * [progress].
 */
private fun DrawScope.drawAppScreen(screen: Rect, palette: Palette, progress: Float) {
    val w = screen.width
    val h = screen.height
    drawRect(palette.accent, screen.topLeft, Size(w, h * 0.14f))
    val card = Rect(Offset(screen.left + w * 0.18f, screen.top + h * 0.3f), Size(w * 0.64f, w * 0.64f * 1.3f))
    drawRoundRect(palette.accent.copy(alpha = 0.12f), card.topLeft, card.size, CornerRadius(w * 0.06f))
    drawRoundRect(palette.accent, card.topLeft, card.size, CornerRadius(w * 0.06f), style = Stroke(w * 0.025f))
    drawCircle(palette.accent, card.width * 0.18f, Offset(card.center.x, card.top + card.height * 0.38f), style = Stroke(w * 0.025f))
    val barTop = screen.top + h * 0.8f
    val barLeft = screen.left + w * 0.14f
    val barWidth = w * 0.72f
    val barHeight = h * 0.025f
    drawRoundRect(palette.ink.copy(alpha = 0.25f), Offset(barLeft, barTop), Size(barWidth, barHeight), CornerRadius(barHeight / 2))
    drawRoundRect(palette.accent, Offset(barLeft, barTop), Size(barWidth * progress.coerceIn(0f, 1f), barHeight), CornerRadius(barHeight / 2))
}

/**
 * Three arcs spreading out from [center] and fading, [phase] from 0 to 1 moving them outwards. Full circles
 * unless [sweep] is under 360 degrees.
 */
private fun DrawScope.drawWaves(
    center: Offset,
    maxRadius: Float,
    phase: Float,
    color: Color,
    startAngle: Float = 0f,
    sweep: Float = 360f,
    alpha: Float = 1f
) {
    repeat(3) { index ->
        val p = (phase + index / 3f) % 1f
        val radius = maxRadius * (0.2f + 0.8f * p)
        val waveColor = color.copy(alpha = (1f - p) * 0.7f * alpha.coerceIn(0f, 1f))
        val stroke = Stroke(width = maxRadius * 0.05f, cap = StrokeCap.Round)
        if (sweep >= 360f) {
            drawCircle(waveColor, radius, center, style = stroke)
        } else {
            drawArc(
                waveColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = stroke
            )
        }
    }
}

/** Camera viewfinder brackets at the corners of [bounds]. */
private fun DrawScope.drawViewfinderCorners(bounds: Rect, color: Color, length: Float) {
    val stroke = Stroke(width = length * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    listOf(
        Triple(bounds.topLeft, 1f, 1f),
        Triple(bounds.topRight, -1f, 1f),
        Triple(bounds.bottomLeft, 1f, -1f),
        Triple(bounds.bottomRight, -1f, -1f)
    ).forEach { (corner, dx, dy) ->
        val path = Path().apply {
            moveTo(corner.x + dx * length, corner.y)
            lineTo(corner.x, corner.y)
            lineTo(corner.x, corner.y + dy * length)
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * A tick centred on [center], [size] across, drawn as far as [progress] from 0 to 1.
 */
private fun DrawScope.drawCheck(center: Offset, size: Float, color: Color, progress: Float) {
    if (progress <= 0f) return
    val tick = Path().apply {
        moveTo(center.x - size * 0.45f, center.y + size * 0.02f)
        lineTo(center.x - size * 0.12f, center.y + size * 0.34f)
        lineTo(center.x + size * 0.48f, center.y - size * 0.3f)
    }
    val measure = PathMeasure().apply { setPath(tick, false) }
    val drawn = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), drawn, true)
    drawPath(drawn, color, style = Stroke(width = size * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
