package com.hddev.smartemu.ui.glossary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shows explanations of glossary terms: [show] opens a sheet with one or more terms, [showAll] the whole glossary.
 * [GlossaryHost] provides one to everything below it.
 */
@Stable
class GlossaryController {
    internal var shown by mutableStateOf<List<GlossaryTerm>>(emptyList())
    internal var title by mutableStateOf<String?>(null)
    internal var allShown by mutableStateOf(false)

    /** What the sheet showed before each term opened from it, so that Back returns there. */
    private var history by mutableStateOf<List<Pair<List<GlossaryTerm>, String?>>>(emptyList())
    internal val canGoBack: Boolean get() = history.isNotEmpty()

    /** Explains [terms], under [title] if given. */
    fun show(terms: List<GlossaryTerm>, title: String? = null) {
        if (terms.isEmpty()) return
        history = emptyList()
        shown = terms
        this.title = title
    }

    /** Opens [term] from the sheet, keeping what it showed to go back to. */
    internal fun open(term: GlossaryTerm) {
        history = history + (shown to title)
        shown = listOf(term)
        title = null
    }

    internal fun back() {
        val (terms, previousTitle) = history.lastOrNull() ?: return
        history = history.dropLast(1)
        shown = terms
        title = previousTitle
    }

    internal fun dismiss() {
        history = emptyList()
        shown = emptyList()
    }

    fun showAll() {
        dismiss()
        allShown = true
    }
}

/** The glossary [GlossaryText] and the tags open terms in; without a [GlossaryHost], terms aren't marked. */
val LocalGlossary = staticCompositionLocalOf<GlossaryController?> { null }

/** Lets everything in [content] explain the terms it shows, in a sheet over the screen or the dialog it's in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryHost(content: @Composable () -> Unit) {
    val controller = remember { GlossaryController() }
    CompositionLocalProvider(LocalGlossary provides controller) {
        content()

        if (controller.shown.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = controller::dismiss,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            ) {
                GlossarySheetContent(
                    title = controller.title,
                    terms = controller.shown,
                    onBack = if (controller.canGoBack) controller::back else null,
                    onShowTerm = controller::open,
                    // The full glossary may be open under the sheet already
                    onShowAll = if (controller.allShown) null else controller::showAll
                )
            }
        }
        if (controller.allShown) {
            GlossaryDialog(onDismiss = { controller.allShown = false })
        }
    }
}

/**
 * [text] with each glossary term in it marked with a dotted underline, which explains the term when tapped. With
 * [linked] false the terms are marked but not tappable, for text in something that's tapped as a whole. Terms
 * listed in [exclude] by id stay unmarked.
 */
@Composable
fun GlossaryText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    linked: Boolean = true,
    exclude: Set<String> = emptySet()
) {
    val glossary = LocalGlossary.current
    val matches = remember(text, exclude) { Glossary.find(text).filter { it.term.id !in exclude } }
    if (glossary == null || matches.isEmpty()) {
        Text(text = text, modifier = modifier, style = style, color = color, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow)
        return
    }
    val pressed = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val annotated = remember(text, matches, linked, pressed) {
        buildAnnotatedString {
            append(text)
            if (linked) {
                matches.forEach { match ->
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = match.term.id,
                            styles = TextLinkStyles(pressedStyle = SpanStyle(background = pressed)),
                            linkInteractionListener = { glossary.show(listOf(match.term)) }
                        ),
                        match.range.first,
                        match.range.last + 1
                    )
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val underline = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }.copy(alpha = 0.55f)
    Text(
        text = annotated,
        modifier = modifier.drawBehind { layout?.let { drawDottedUnderlines(it, matches, underline) } },
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it }
    )
}

/** A dotted line under each match, across every line it wraps onto, like an abbreviation on the web. */
private fun DrawScope.drawDottedUnderlines(layout: TextLayoutResult, matches: List<GlossaryMatch>, color: Color) {
    val dot = 1.5.dp.toPx()
    val effect = PathEffect.dashPathEffect(floatArrayOf(dot, dot * 1.5f))
    val length = layout.layoutInput.text.length
    matches.forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (end > length) return@forEach
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        if (firstLine >= layout.lineCount) return@forEach
        for (line in firstLine..minOf(lastLine, layout.lineCount - 1)) {
            if (layout.isLineEllipsized(line) && line == layout.lineCount - 1 && end > layout.getLineEnd(line, visibleEnd = true)) continue
            val left = if (line == firstLine) layout.getHorizontalPosition(start, usePrimaryDirection = true) else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getHorizontalPosition(end, usePrimaryDirection = true) else layout.getLineRight(line)
            val y = layout.getLineBaseline(line) + 2.5.dp.toPx()
            drawLine(
                color = color,
                start = Offset(minOf(left, right), y),
                end = Offset(maxOf(left, right), y),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = effect
            )
        }
    }
}

/** The terms a sheet explains, each with what it stands for, what it means and the terms related to it. */
@Composable
private fun GlossarySheetContent(
    title: String?,
    terms: List<GlossaryTerm>,
    onBack: (() -> Unit)?,
    onShowTerm: (GlossaryTerm) -> Unit,
    onShowAll: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        onBack?.let {
            TextButton(onClick = it, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back")
            }
        }
        title?.let { Text(text = it, style = MaterialTheme.typography.titleLarge) }
        if (terms.size > COMPACT_AFTER) {
            // A key to many terms: each in brief, opening in full when tapped
            Column {
                terms.forEach { term -> TermSummaryRow(term = term, onClick = { onShowTerm(term) }) }
            }
        } else {
            terms.forEachIndexed { index, term ->
                if (index > 0) HorizontalDivider()
                TermExplanation(term = term, onShowTerm = onShowTerm)
            }
        }
        onShowAll?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("All terms and abbreviations")
            }
        }
    }
}

/** More terms than this are listed in brief rather than explained in full. */
private const val COMPACT_AFTER = 3

@Composable
private fun TermSummaryRow(term: GlossaryTerm, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Explain ${term.term}", onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TermTile(term, small = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = term.expansion ?: term.term, style = MaterialTheme.typography.titleSmall)
            Text(
                text = term.meaning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermExplanation(term: GlossaryTerm, onShowTerm: (GlossaryTerm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TermTile(term)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = term.expansion ?: term.term,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = term.category.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Other terms in the explanation open in the sheet in place of this one
        GlossaryText(text = term.meaning, style = MaterialTheme.typography.bodyLarge, exclude = setOf(term.id))
        val related = term.related.mapNotNull { Glossary[it] }
        if (related.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "See also",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                related.forEach { other ->
                    SuggestionChip(onClick = { onShowTerm(other) }, label = { Text(other.term) })
                }
            }
        }
    }
}

/** The abbreviation itself, or the first letters of a phrase, on a tile. */
@Composable
private fun TermTile(term: GlossaryTerm, small: Boolean = false) {
    Box(
        modifier = Modifier
            .heightIn(min = if (small) 40.dp else 48.dp)
            // The same width in a list, so that the explanations beside the tiles line up
            .then(if (small) Modifier.width(72.dp) else Modifier.widthIn(min = 48.dp))
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = term.tileLabel,
            style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}

/** A term short enough for the tile, or for a phrase such as "Secure messaging", its initials. */
private val GlossaryTerm.tileLabel: String
    get() = when {
        tile != null -> tile
        term.length <= 6 && ' ' !in term -> term
        term.startsWith("EF.") -> "EF"
        else -> term.split(' ', '-').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    }

/** Every term, by category, searchable by abbreviation, what it stands for or what it means. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryDialog(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val needle = query.trim()
        Glossary.terms.filter { term ->
            needle.isEmpty() || listOfNotNull(term.term, term.expansion, term.meaning).any { it.contains(needle, ignoreCase = true) }
        }.sortedBy { it.term.lowercase() }.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                TopAppBar(
                    title = { Text("Terms and abbreviations") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search, e.g. PACE or signature") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }) else null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                text = "No term matches \"${query.trim()}\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                    shown.forEach { (category, terms) ->
                        item(key = category.name) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                            )
                        }
                        items(terms, key = { it.id }) { term -> GlossaryEntry(term) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlossaryEntry(term: GlossaryTerm) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Text(text = term.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                term.expansion?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            GlossaryText(
                text = term.meaning,
                style = MaterialTheme.typography.bodyMedium,
                exclude = setOf(term.id)
            )
        }
    }
}
