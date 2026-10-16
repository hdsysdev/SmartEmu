package com.hddev.smartemu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipAuthenticationSpec
import com.hddev.smartemu.data.ChipConfiguration
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.ChipProfile
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.EcCurve
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PaceSpec
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.ProfileAspect
import com.hddev.smartemu.data.Provenance
import com.hddev.smartemu.ui.glossary.Glossary
import com.hddev.smartemu.ui.glossary.GlossaryText
import com.hddev.smartemu.ui.glossary.LocalGlossary

/**
 * The chosen chip profile at a glance: where it's from, the document, and the same row of tags every profile has,
 * with [onChange] to pick another and [onShowDetails] for the evidence behind it.
 */
@Composable
fun ChipProfileCard(
    configuration: ChipConfiguration,
    enabled: Boolean,
    onChange: () -> Unit,
    onShowDetails: () -> Unit
) {
    val profile = configuration.profile
    SectionCard(
        title = "Chip profile",
        subtitle = "How a real kind of document's chip behaves. It sets the options below, which you can still " +
            "change. Tap a tag or a dotted term to see what it means.",
        icon = Icons.Outlined.Flag
    ) {
        Surface(
            onClick = onChange,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CountryTile(profile)
                    ProfileName(profile, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ChipTags(configuration.tags)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(text = "Change profile", onClick = onChange, icon = Icons.Outlined.SwapHoriz, enabled = enabled)
            AppButton(text = "Details", onClick = onShowDetails, icon = Icons.Outlined.Info, emphasis = ButtonEmphasis.Low)
        }
    }
}

/**
 * What the chip does with the chosen profile, one short line per part, each with where its facts come from, and how
 * the chip differs from the profile, if it does. The evidence, notes and sources are in [onShowDetails].
 */
@Composable
fun ChipProfileSpecCard(configuration: ChipConfiguration, onShowDetails: () -> Unit) {
    val sourced = ProfileAspect.entries.count { configuration.provenanceOf(it) != Provenance.ASSUMED }
    SectionCard(
        title = "What the profile does",
        subtitle = "$sourced of ${ProfileAspect.entries.size} parts backed by a specification or report",
        icon = Icons.Outlined.Info
    ) {
        AdaptationsNotice(configuration.adaptations)
        ProfileAspect.entries.forEachIndexed { index, aspect ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SpecRow(label = aspect.displayName, value = aspectValue(configuration, aspect), provenance = configuration.provenanceOf(aspect))
        }
        TextButton(onClick = onShowDetails, contentPadding = PaddingValues(horizontal = 0.dp)) {
            val profile = configuration.profile
            val count = profile.evidence.size + profile.sources.size
            Text(if (count > 0) "Evidence, notes and sources ($count)" else "Notes and what the badges mean")
        }
    }
}

/**
 * Every profile, grouped by country, each in one short row with the same tags, to search and filter. Tapping a row
 * calls [onSelected]; its info button shows what the chip would do with it, from [preview].
 */
@Composable
fun ChipProfilePickerDialog(
    selected: ChipProfile,
    preview: (ChipProfile) -> ChipConfiguration,
    onSelected: (ChipProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf<ProfileFilter>()) }
    var detailsFor by remember { mutableStateOf<ChipProfile?>(null) }
    val shown = remember(query, filters) {
        ChipProfiles.all.filter { profile -> profile.matches(query) && filters.all { it.test(profile) } }
    }
    val groups = remember(shown) { shown.groupBy { it.heading }.toList().sortedBy { (heading, _) -> groupOrder(heading) } }
    // Opens on the selected profile's country, so that the current choice is in view
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = groups.takeWhile { (_, profiles) -> profiles.none { it.id == selected.id } }
            .sumOf { (_, profiles) -> profiles.size + 1 }
            .takeIf { it < shown.size + groups.size } ?: 0
    )
    val glossary = LocalGlossary.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
        ) {
            Column {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Choose a chip profile", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Country, document or curve") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                        }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProfileFilter.entries.forEach { filter ->
                        val on = filter in filters
                        FilterChip(
                            selected = on,
                            onClick = { filters = if (on) filters - filter else filters + filter },
                            label = { Text(filter.label) },
                            leadingIcon = if (on) ({
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            }) else null
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${shown.size} of ${ChipProfiles.all.size} profiles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (glossary != null) {
                        TextButton(onClick = { glossary.show(PICKER_TERMS.mapNotNull { Glossary[it] }, title = "What the labels mean") }) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("What the labels mean")
                        }
                    }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (groups.isEmpty()) {
                        item {
                            Text(
                                text = "No profile matches. Clear the search or a filter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                    groups.forEach { (heading, profiles) ->
                        // The country stays at the top while its profiles scroll under it
                        stickyHeader(key = "group-$heading") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CountryTile(profiles.first(), small = true)
                                Text(
                                    text = heading,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = profiles.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                )
                            }
                            ProfileRow(
                                profile = profile,
                                selected = profile.id == selected.id,
                                onClick = {
                                    onSelected(profile)
                                    onDismiss()
                                },
                                onInfo = { detailsFor = profile }
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }

    detailsFor?.let { profile ->
        ChipProfileDetailsDialog(
            configuration = preview(profile),
            onUse = if (profile.id == selected.id) null else ({
                onSelected(profile)
                detailsFor = null
                onDismiss()
            }),
            onDismiss = { detailsFor = null }
        )
    }
}

/**
 * Everything known about a profile: what the chip does with it and where each fact comes from, the primary evidence,
 * the emulator's limits and the sources. [onUse], if given, switches to the profile.
 */
@Composable
fun ChipProfileDetailsDialog(
    configuration: ChipConfiguration,
    onUse: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val profile = configuration.profile
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CountryTile(profile)
                    ProfileName(profile, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChipTags(configuration.tags)
                    GlossaryText(text = profile.summary, style = MaterialTheme.typography.bodyMedium)
                    AdaptationsNotice(configuration.adaptations, startExpanded = true)

                    DetailsHeading("What the chip does")
                    ProfileAspect.entries.forEach { aspect ->
                        SpecRow(label = aspect.displayName, value = aspectValue(configuration, aspect), provenance = configuration.provenanceOf(aspect))
                    }

                    if (profile.evidence.isNotEmpty()) {
                        DetailsHeading("Primary evidence for this generation")
                        profile.evidence.forEach { evidence ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ProvenanceBadge(evidence.provenance)
                                        Text(
                                            text = evidence.aspect?.displayName ?: "Generation",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    GlossaryText(text = evidence.claim, style = MaterialTheme.typography.bodyMedium)
                                    Row(
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .clickable(role = Role.Button) { uriHandler.openUri(evidence.url) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "${evidence.sourceTitle} · ${evidence.locator}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (profile.notes.isNotEmpty()) {
                        DetailsHeading("Accuracy limits and emulator choices")
                        profile.notes.forEach { Bullet(it) }
                    }
                    if (profile.sources.isNotEmpty()) {
                        DetailsHeading("Sources")
                        profile.sources.forEach { Bullet(it) }
                    }
                    DetailsHeading("What the badges mean")
                    Provenance.entries.forEach { provenance ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProvenanceBadge(provenance)
                            Text(
                                text = provenance.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(
                        text = "A source may establish one part of a row while its remaining choices are unknown; the " +
                            "row then stays ASSUMED. Parts you change yourself are ASSUMED too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    if (onUse != null) {
                        Button(onClick = onUse) { Text("Use this profile") }
                    }
                }
            }
        }
    }
}

/** The ways the profile list can be narrowed, all of which a profile must pass. */
private enum class ProfileFilter(val label: String, val test: (ChipProfile) -> Boolean) {
    PACE("PACE", { it.accessControl.supportsPace }),
    PACE_ONLY("PACE only", { it.accessControl == AccessControl.PACE_ONLY }),
    BAC_ONLY("BAC only", { it.accessControl == AccessControl.BAC_ONLY }),
    BRAINPOOL("Brainpool PACE", { it.accessControl.supportsPace && !it.pace.curve.isNist }),
    CAM("PACE-CAM", { it.accessControl.supportsPace && it.paceMapping == PaceMapping.CHIP_AUTHENTICATION }),
    CHIP_AUTHENTICATION("CA", { it.chipAuthentication != null }),
    ACTIVE_AUTHENTICATION("AA", { it.activeAuthentication != null }),
    TERMINAL_AUTHENTICATION("TA", { it.terminalAuthentication }),
    ID_CARD("Cards", { it.documentType?.isCard == true })
}

private fun ChipProfile.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return listOf(title, id, country.orEmpty(), summary, pace.curve.displayName, tags.joinToString { it.text })
        .any { it.contains(needle, ignoreCase = true) }
}

/** The generic profiles first, then the countries in alphabetical order. */
private fun groupOrder(heading: String): String = when (heading) {
    GENERIC_HEADING -> "0"
    EU_HEADING -> "1"
    else -> "2$heading"
}

private const val GENERIC_HEADING = "Generic ICAO chip"
private const val EU_HEADING = "European Union"

/** Where the profile's documents come from, as in "Germany"; profiles for any state share one heading. */
internal val ChipProfile.heading: String
    get() = when {
        " · " in title -> title.substringBefore(" · ")
        title.startsWith("EU ") -> EU_HEADING
        else -> GENERIC_HEADING
    }

/** The document, as in "Passport", without the generation. */
internal val ChipProfile.documentName: String
    get() = when {
        id == ChipProfiles.GENERIC_ID -> "Any country, any document"
        else -> withoutHeading.substringBefore(" (").replaceFirstChar { it.uppercase() }
    }

/** The generation, as in "2017 design", or null if the title names none. */
internal val ChipProfile.generation: String?
    get() = withoutHeading.substringAfter(" (", "").removeSuffix(")").ifEmpty { null }

private val ChipProfile.withoutHeading: String
    get() = when {
        " · " in title -> title.substringAfter(" · ")
        title.startsWith("EU ") -> title.removePrefix("EU ")
        else -> title
    }

/** The ICAO code of the issuing state, or EU or ICAO for profiles any member or state could issue. */
private val ChipProfile.tileLabel: String
    get() = country ?: if (heading == EU_HEADING) "EU" else "ICAO"

/** A short name for the tags: P-256 for NIST P-256, BP-256 for brainpoolP256r1. */
internal val EcCurve.shortName: String get() = if (isNist) "P-$bits" else "BP-$bits"

internal val PaceSpec.shortDomain: String get() = dhGroup?.let { "DH ${it.bits}" } ?: curve.shortName

internal data class ChipTag(val text: String, val kind: ChipTagKind)

internal enum class ChipTagKind { ACCESS, CRYPTO, FEATURE, NOTICE }

/**
 * The tags every profile has, in the same order: access control, the PACE suite, then CA, AA and TA where the chip
 * has them.
 */
private fun chipTags(
    accessControl: AccessControl,
    paceMappings: List<PaceMapping>,
    pace: PaceSpec,
    chipAuthentication: ChipAuthenticationSpec?,
    activeAuthentication: Boolean,
    terminalAuthentication: Boolean
): List<ChipTag> = buildList {
    add(ChipTag(accessControl.shortName, ChipTagKind.ACCESS))
    if (accessControl.supportsPace) {
        add(ChipTag("${paceMappings.joinToString("/") { it.abbreviation }} · ${pace.shortDomain} · ${pace.cipher.displayName}", ChipTagKind.CRYPTO))
    }
    chipAuthentication?.let { add(ChipTag("CA ${it.curve.shortName}", ChipTagKind.FEATURE)) }
    if (activeAuthentication) add(ChipTag("AA", ChipTagKind.FEATURE))
    if (terminalAuthentication) add(ChipTag("TA", ChipTagKind.FEATURE))
}

internal val ChipProfile.tags: List<ChipTag>
    get() = chipTags(
        accessControl, listOf(paceMapping) + otherPaceMappings, pace, chipAuthentication, activeAuthentication != null,
        terminalAuthentication
    )

/** The tags for the chip as it is configured, with a notice when it differs from the profile. */
internal val ChipConfiguration.tags: List<ChipTag>
    get() = chipTags(accessControl, paceMappings, pace, chipAuthentication, activeAuthentication != null, terminalAuthentication) +
        listOfNotNull(
            (paceSuites.size - paceMappings.size).takeIf { it > 0 }?.let { ChipTag("+$it PACE suite${if (it > 1) "s" else ""}", ChipTagKind.NOTICE) },
            adaptations.size.takeIf { it > 0 }?.let { ChipTag("Adapted", ChipTagKind.NOTICE) }
        )

/** The tags in a row; tapping one explains each abbreviation in it, marked with a dotted underline. */
@Composable
internal fun ChipTags(tags: List<ChipTag>, modifier: Modifier = Modifier) {
    val glossary = LocalGlossary.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            val (container, content) = when (tag.kind) {
                ChipTagKind.ACCESS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                ChipTagKind.CRYPTO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                ChipTagKind.FEATURE -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
                ChipTagKind.NOTICE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            }
            val terms = remember(tag.text) { Glossary.termsIn(tag.text) }
            val explain = if (glossary != null && terms.isNotEmpty()) ({ glossary.show(terms) }) else null
            GlossaryText(
                text = tag.text,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                linked = false,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(container)
                    .then(
                        if (explain != null) {
                            Modifier.clickable(onClickLabel = "Explain ${tag.text}", role = Role.Button, onClick = explain)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** The issuing state's code on a tile, the same for every profile of it. */
@Composable
internal fun CountryTile(profile: ChipProfile, small: Boolean = false) {
    Box(
        modifier = Modifier
            .size(if (small) 28.dp else 44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = profile.tileLabel,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1
        )
    }
}

/** Where the profile is from on the first line, the document and its generation on the second. */
@Composable
internal fun ProfileName(profile: ChipProfile, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = profile.heading, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = listOfNotNull(profile.documentName, profile.generation).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One profile in two lines laid out the same for every profile, so that they compare down the list: the document
 * and generation with marks for Chip, Active and Terminal Authentication, then the access control and the
 * cryptography a reader meets.
 */
@Composable
private fun ProfileRow(profile: ChipProfile, selected: Boolean, onClick: () -> Unit, onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "In use",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp)
                    )
                }
                // The generation tells apart a country's documents, so it gets a line of its own rather than an ellipsis
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.documentName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    profile.generation?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ProtocolMarks(profile, modifier = Modifier.padding(top = 2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessBadge(profile.accessControl)
                Text(
                    text = profile.cryptoSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "About ${profile.title}")
        }
    }
}

/** The access control in a word or two, its colour moving from BAC only, the oldest, to PACE only. */
@Composable
private fun AccessBadge(accessControl: AccessControl) {
    val (container, content) = when (accessControl) {
        AccessControl.BAC_ONLY -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
        AccessControl.BAC_AND_PACE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        AccessControl.PACE_ONLY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Text(
        text = accessControl.shortName,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** CA, AA and TA, always in that place: filled where the chip has them, faint where it doesn't. */
@Composable
private fun ProtocolMarks(profile: ChipProfile, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ProtocolMark("CA", "Chip Authentication", profile.chipAuthentication != null)
        ProtocolMark("AA", "Active Authentication", profile.activeAuthentication != null)
        ProtocolMark("TA", "Terminal Authentication", profile.terminalAuthentication)
    }
}

@Composable
private fun ProtocolMark(label: String, name: String, present: Boolean) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 20.dp)
            .then(
                if (present) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, shape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                }
            )
            .semantics { contentDescription = if (present) name else "No $name" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (present) FontWeight.Bold else FontWeight.Normal,
            color = if (present) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            maxLines = 1
        )
    }
}

/** What a reader negotiates: the PACE mapping, curve or group and cipher, or BAC's 3DES. */
private val ChipProfile.cryptoSummary: String
    get() = if (accessControl.supportsPace) {
        "${paceMapping.abbreviation} · ${pace.shortDomain} · ${pace.cipher.displayName}"
    } else {
        "3DES secure messaging"
    }

private val AccessControl.shortName: String
    get() = when (this) {
        AccessControl.BAC_ONLY -> "BAC only"
        AccessControl.PACE_ONLY -> "PACE only"
        AccessControl.BAC_AND_PACE -> "BAC + PACE"
    }

/** The terms the picker's rows and filters use, which "What the labels mean" explains. */
private val PICKER_TERMS = listOf("BAC", "PACE", "GM", "CAM", "IM", "NIST", "BRAINPOOL", "DH", "AES", "3DES", "CA", "AA", "TA")

/** How the chip differs from its profile, collapsed to a count until opened. */
@Composable
internal fun AdaptationsNotice(adaptations: List<String>, startExpanded: Boolean = false) {
    if (adaptations.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Differs from the profile in ${adaptations.size} way${if (adaptations.size > 1) "s" else ""}" +
                    if (expanded) "" else " · show",
                style = MaterialTheme.typography.labelLarge
            )
            if (expanded) {
                adaptations.forEach { GlossaryText(text = "• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** One part of the chip: a label above its value, and where the facts come from if [provenance] is given. */
@Composable
internal fun SpecRow(label: String, value: String, provenance: Provenance? = null, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            GlossaryText(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GlossaryText(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
        provenance?.let { ProvenanceBadge(it) }
    }
}

@Composable
internal fun ProvenanceBadge(provenance: Provenance) {
    val (container, content) = when (provenance) {
        Provenance.MEASURED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Provenance.SPECIFICATION -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Provenance.REPORTED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Provenance.ASSUMED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = provenance.displayName,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun DetailsHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Bullet(text: String) {
    GlossaryText(text = "• $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Where the facts about [aspect] come from; a part changed from the profile's is the user's own choice. */
internal fun ChipConfiguration.provenanceOf(aspect: ProfileAspect): Provenance {
    val changed = when (aspect) {
        ProfileAspect.ACCESS_CONTROL -> accessControl != profile.accessControl
        ProfileAspect.PACE_CRYPTOGRAPHY -> pace != profile.pace || paceMapping != profile.paceMapping
        ProfileAspect.ACTIVE_AUTHENTICATION -> activeAuthentication != profile.activeAuthentication
        else -> false
    }
    return if (changed) Provenance.ASSUMED else profile.provenanceOf(aspect)
}

/** The part of the chip [aspect] covers, as the chip has it now, in a line. */
internal fun aspectValue(configuration: ChipConfiguration, aspect: ProfileAspect): String {
    val profile = configuration.profile
    return when (aspect) {
        ProfileAspect.ACCESS_CONTROL -> configuration.accessControl.displayName
        ProfileAspect.PACE_CRYPTOGRAPHY -> if (configuration.supportsPace) {
            val pace = configuration.pace
            val preset = profile.pace.curve.takeIf { !pace.usesDh && it != pace.curve }?.let { " (profile: ${it.displayName})" } ?: ""
            "PACE-${configuration.paceMapping.abbreviation} · ${pace.domainName}$preset · ${pace.cipher.displayName}"
        } else {
            "No PACE"
        }
        ProfileAspect.ACTIVE_AUTHENTICATION -> configuration.activeAuthentication?.displayName ?: "Off"
        ProfileAspect.EXTENDED_ACCESS_CONTROL -> buildList {
            configuration.chipAuthentication?.let { add("CA · ${it.curve.displayName} · ${it.cipher.displayName}") }
            if (configuration.terminalAuthentication) add("TA guards the fingerprints, which stay locked")
        }.ifEmpty { listOf("None") }.joinToString("\n")
        ProfileAspect.DATA_GROUPS -> configuration.dataGroups.joinToString { "DG$it" }
        ProfileAspect.SIGNATURE -> "${configuration.sod.signerKey.displayName} · ${configuration.sod.digestAlgorithm}"
        ProfileAspect.MRZ -> buildList {
            profile.mrz.documentCode?.let { add("Document code $it") }
            profile.mrz.documentNumberFormat?.let { add("Numbers: $it") }
            if (profile.mrz.personalNumberInMrz) add("Personal number in the MRZ")
            add("Valid ${profile.validity.displayText}")
        }.joinToString("\n")
        ProfileAspect.ERROR_RESPONSES ->
            "EXTERNAL AUTHENTICATE without a challenge: " +
                configuration.errorResponses.outOfSequence.toString(16).uppercase().padStart(4, '0')
    }
}

/** The Document Signer that signs the chip's files, as test-pki/README.md names it. */
internal fun documentSignerName(passportData: PassportData, configuration: ChipConfiguration): String =
    when (passportData.chipFault) {
        ChipFault.EXPIRED_SIGNER -> "the expired test Document Signer"
        ChipFault.UNTRUSTED_SIGNER -> "a Document Signer from the unknown CSCA"
        ChipFault.BROKEN_SIGNATURE -> "a key that isn't the Document Signer's"
        else -> {
            val signer = if (passportData.documentType.isCard) configuration.sod.cardSigner else configuration.sod.passportSigner
            "$signer (${configuration.sod.signerKey.displayName}) under ${configuration.sod.csca}"
        }
    }
