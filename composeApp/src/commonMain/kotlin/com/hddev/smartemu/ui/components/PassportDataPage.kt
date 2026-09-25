package com.hddev.smartemu.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.Portrait
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.jetbrains.compose.resources.Font
import smartemu.composeapp.generated.resources.Res
import smartemu.composeapp.generated.resources.ocr_b
import kotlin.math.min

private val PageColor = Color(0xFFF4F0E6)
private val HeaderColor = Color(0xFF1F3A5F)
private val InkColor = Color(0xFF1A1A1A)
private val LabelColor = Color(0xFF5B6470)
private val MrzZoneColor = Color.White
private val FullScreenBackground = Color(0xFF101214)

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

// Geometry of the ID-3 (TD3) data page, in millimetres, from ICAO Doc 9303-4 Figures 3 and 7
private const val PAGE_WIDTH = 125f
private const val PAGE_HEIGHT = 88f
private const val CORNER_RADIUS = 3.18f
private const val HEADER_HEIGHT = 9f
private const val PADDING = 4f

/** The portrait, at the 35 x 45 mm of a passport photo. */
private const val PORTRAIT_WIDTH = 35f
private const val PORTRAIT_HEIGHT = 45f

/** The MRZ: the left-hand edge of each line's first character and their centre lines, from the bottom edge. */
private const val MRZ_HEIGHT = 23.2f
private const val MRZ_LEFT = 6f
private val MRZ_CENTRE_LINES = listOf(15.75f, 9.4f)

/** OCR-B size 1, at 10 characters per inch. */
private const val MRZ_CHARACTER_PITCH = 2.54f

/** Advance width and the centre of the capitals, above the baseline, of the bundled OCR-B, as fractions of its em. */
private const val OCR_B_ADVANCE = 0.723f
private const val OCR_B_CAPITALS_CENTRE = 0.3515f

private const val LABEL_TEXT_SIZE = 2.6f
private const val VALUE_TEXT_SIZE = 4.2f

/** Width the full-screen page is laid out at before it's scaled to fit, so that it looks as it does in the preview. */
private val FullScreenLayoutWidth = 360.dp

/**
 * Section showing the [PassportDataPage] of the passport being emulated. Tapping the page, or the button below it,
 * calls [onOpenFullScreen].
 */
@Composable
fun PassportPreview(
    passportData: PassportData,
    onOpenFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = "Data page",
        subtitle = "Updates as you edit. Show it full screen so that a reader app's camera can scan the MRZ, " +
            "the two lines of code at the bottom.",
        icon = Icons.Outlined.Badge,
        modifier = modifier
    ) {
        PassportDataPage(passportData = passportData, onClick = onOpenFullScreen)
        AppButton(
            text = "Show full screen",
            onClick = onOpenFullScreen,
            icon = Icons.Filled.Fullscreen,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The [PassportDataPage] alone on a dark background, scaled to fill as much of the screen as it can, at full
 * brightness, so that a reader app's camera can scan the MRZ. At first the page is turned to whichever way shows it
 * largest; the rotate buttons turn it a quarter at a time.
 */
@Composable
fun PassportFullScreenDialog(
    passportData: PassportData,
    onDismiss: () -> Unit
) {
    // Quarter turns clockwise, or null until the user rotates the page
    var quarterTurns by rememberSaveable { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ScanningDisplayEffect()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(FullScreenBackground)
                .safeDrawingPadding()
        ) {
            // The page is wider than it is tall, so a tall screen shows it largest turned sideways
            val turns = quarterTurns ?: if (maxHeight > maxWidth) 1 else 0

            PassportDataPage(
                passportData = passportData,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .fitTurned(turns, FullScreenLayoutWidth)
            )

            // In the corners, which the page leaves free whichever way it's turned
            AppIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close",
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppIconButton(
                    icon = Icons.Filled.Rotate90DegreesCcw,
                    contentDescription = "Rotate left",
                    onClick = { quarterTurns = (turns + 3) % 4 }
                )
                AppIconButton(
                    icon = Icons.Filled.Rotate90DegreesCw,
                    contentDescription = "Rotate right",
                    onClick = { quarterTurns = (turns + 1) % 4 }
                )
            }
        }
    }
}

/**
 * Lays the content out at [layoutWidth], then turns it [quarterTurns] times clockwise and scales it to the largest
 * size that fits the incoming constraints, which must be bounded, centred.
 */
private fun Modifier.fitTurned(quarterTurns: Int, layoutWidth: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixedWidth(layoutWidth.roundToPx()))
    val sideways = quarterTurns % 2 == 1
    val turnedWidth = if (sideways) placeable.height else placeable.width
    val turnedHeight = if (sideways) placeable.width else placeable.height
    val scale = min(constraints.maxWidth.toFloat() / turnedWidth, constraints.maxHeight.toFloat() / turnedHeight)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeWithLayer((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2) {
            rotationZ = quarterTurns * 90f
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * Sample image of the emulated passport's data page (ICAO 9303 TD3), at the proportions of a real one: the visual
 * inspection zone with the holder's details and the Card Access Number, and the machine readable zone in OCR-B at
 * the size and position that reader apps expect, so that they can scan it from the screen.
 */
@Composable
fun PassportDataPage(
    passportData: PassportData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val page = PageScale(millimetre = maxWidth / PAGE_WIDTH, density = LocalDensity.current)
        val pageShape = RoundedCornerShape(page.dp(CORNER_RADIUS))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_WIDTH / PAGE_HEIGHT)
                .clip(pageShape)
                .background(PageColor)
                .border(1.dp, LabelColor.copy(alpha = 0.4f), pageShape)
                .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show full screen", onClick = onClick) else Modifier)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DataPageHeader(passportData, page)
                VisualInspectionZone(passportData, page, modifier = Modifier.weight(1f))
                MachineReadableZone(passportData, page)
            }
            Text(
                text = "SPECIMEN",
                style = TextStyle(fontSize = page.sp(14f), fontWeight = FontWeight.Black, letterSpacing = page.sp(2f)),
                color = Color(0xFFB03030).copy(alpha = 0.14f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { rotationZ = -20f }
            )
        }
    }
}

/**
 * Converts millimetres on the data page to the sizes it is laid out at, [millimetre] to each. Text sizes ignore
 * the user's font scale, so that the page keeps its proportions.
 */
private class PageScale(private val millimetre: Dp, private val density: Density) {
    fun dp(mm: Float): Dp = millimetre * mm

    fun sp(mm: Float): TextUnit = with(density) { dp(mm).toSp() }
}

@Composable
private fun DataPageHeader(passportData: PassportData, page: PageScale) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(page.dp(HEADER_HEIGHT))
            .background(HeaderColor)
            .padding(horizontal = page.dp(PADDING)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "PASSPORT",
            style = TextStyle(fontSize = page.sp(3.8f), fontWeight = FontWeight.Bold, letterSpacing = page.sp(0.8f)),
            color = Color.White
        )
        Text(
            text = countryName(passportData.issuingCountry).uppercase(),
            style = TextStyle(fontSize = page.sp(3.4f)),
            color = Color.White,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = page.dp(3f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ChipSymbol(color = Color.White, modifier = Modifier.size(width = page.dp(8f), height = page.dp(5.5f)))
    }
}

@Composable
private fun VisualInspectionZone(passportData: PassportData, page: PageScale, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = page.dp(PADDING)),
        horizontalArrangement = Arrangement.spacedBy(page.dp(PADDING)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PortraitImage(
            portrait = passportData.portrait,
            modifier = Modifier.size(width = page.dp(PORTRAIT_WIDTH), height = page.dp(PORTRAIT_HEIGHT))
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .height(page.dp(PORTRAIT_HEIGHT)),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            val fieldSpacing = Arrangement.spacedBy(page.dp(PADDING))
            Row(horizontalArrangement = fieldSpacing) {
                DataField("Type", "P", page)
                DataField("Code", passportData.issuingCountry, page)
                DataField("Passport No.", passportData.passportNumber.uppercase(), page)
            }
            DataField("Surname", passportData.lastName.uppercase(), page, modifier = Modifier.fillMaxWidth())
            DataField("Given names", passportData.firstName.uppercase(), page, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = fieldSpacing) {
                DataField("Nationality", passportData.nationality, page)
                DataField("Date of birth", formatDate(passportData.dateOfBirth), page)
                DataField("Sex", passportData.gender.uppercase(), page)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                DataField("Date of expiry", formatDate(passportData.expiryDate), page)
                // The CAN is printed on the data page of documents whose chip offers PACE
                if (passportData.accessControl.supportsPace && passportData.hasCan()) {
                    DataField("CAN", passportData.can, page, emphasized = true)
                }
            }
        }
    }
}

/**
 * The two MRZ lines in OCR-B size 1, 2.54 mm to a character, each centred on its reference line.
 */
@Composable
private fun MachineReadableZone(passportData: PassportData, page: PageScale) {
    val zoneModifier = Modifier
        .fillMaxWidth()
        .height(page.dp(MRZ_HEIGHT))
        .background(MrzZoneColor)
        .semantics { contentDescription = "Machine readable zone" }

    if (!passportData.isValid()) {
        Box(modifier = zoneModifier, contentAlignment = Alignment.Center) {
            Text(
                text = "The MRZ appears once the passport details are valid",
                style = TextStyle(fontSize = page.sp(LABEL_TEXT_SIZE)),
                color = LabelColor
            )
        }
        return
    }

    val lines = passportData.toMrzLines()
    val textMeasurer = rememberTextMeasurer()
    val ocrB = FontFamily(Font(Res.font.ocr_b))
    Canvas(modifier = zoneModifier) {
        val millimetre = size.width / PAGE_WIDTH
        val style = TextStyle(
            fontFamily = ocrB,
            fontSize = (MRZ_CHARACTER_PITCH / OCR_B_ADVANCE * millimetre).toSp(),
            color = InkColor
        )
        lines.zip(MRZ_CENTRE_LINES).forEach { (line, centreLine) ->
            val layout = textMeasurer.measure(line, style, softWrap = false, maxLines = 1)
            val baseline = size.height - centreLine * millimetre + OCR_B_CAPITALS_CENTRE * style.fontSize.toPx()
            drawText(layout, topLeft = Offset(MRZ_LEFT * millimetre, baseline - layout.firstBaseline))
        }
    }
}

/** A field's caption with its [value] below. The value shrinks to fit if it's too long. */
@Composable
private fun DataField(
    label: String,
    value: String,
    page: PageScale,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(fontSize = page.sp(LABEL_TEXT_SIZE)),
            color = LabelColor,
            maxLines = 1
        )
        BasicText(
            text = value.ifBlank { "-" },
            style = TextStyle(
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = if (emphasized) FontFamily.Monospace else FontFamily.Default,
                letterSpacing = if (emphasized) page.sp(0.7f) else 0.sp,
                color = InkColor
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(
                minFontSize = page.sp(VALUE_TEXT_SIZE / 2),
                maxFontSize = page.sp(VALUE_TEXT_SIZE),
                stepSize = 0.25.sp
            )
        )
    }
}

/**
 * The holder's [portrait], or a silhouette on grey if there is none.
 */
@Composable
internal fun PortraitImage(portrait: Portrait?, modifier: Modifier = Modifier) {
    val bitmap = remember(portrait) { portrait?.toImageBitmap() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFD9D6CE)),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Portrait",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Portrait placeholder",
                tint = Color(0xFF8E8A80),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The ICAO electronic document symbol printed on documents that contain a chip.
 */
@Composable
private fun ChipSymbol(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.semantics { contentDescription = "Contains an electronic chip" }) {
        val stroke = size.height * 0.1f
        val inset = stroke / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(stroke, stroke),
            style = Stroke(width = stroke)
        )
        val radius = size.height * 0.26f
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(color, Offset(inset, center.y), Offset(center.x - radius, center.y), strokeWidth = stroke)
        drawLine(color, Offset(center.x + radius, center.y), Offset(size.width - inset, center.y), strokeWidth = stroke)
    }
}

private fun formatDate(date: LocalDate?): String =
    date?.let { "${it.day.toString().padStart(2, '0')} ${MONTHS[it.month.number - 1]} ${it.year}" } ?: ""
