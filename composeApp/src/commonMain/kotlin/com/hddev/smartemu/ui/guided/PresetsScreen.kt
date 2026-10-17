package com.hddev.smartemu.ui.guided

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.PassportPreset
import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportPresets
import com.hddev.smartemu.data.PresetGroup
import com.hddev.smartemu.ui.components.CollapsibleHeader
import com.hddev.smartemu.ui.components.PresetRow
import com.hddev.smartemu.ui.components.initiallyExpanded

/**
 * Ready-made passports and cards to try an app with, grouped by what they're for, each group opening and closing
 * under its heading. Choosing one calls [onSelected] with it; [onBack] leaves without changing anything.
 */
@Composable
fun PresetsScreen(onSelected: (PassportPreset) -> Unit, onBack: () -> Unit) {
    val groups = remember { PassportPresets.grouped }
    // Group names, so that which are open survives rotation
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded(groups).map { it.name }) }
    GuidedScaffold(title = "Ready-made passports", onBack = onBack) {
        NoticeCard(
            title = "Try different kinds",
            text = "Each one replaces the current details. Your photo stays, and you can change anything afterwards.",
            icon = Icons.Outlined.Info,
            tone = NoticeTone.INFO
        )
        groups.forEach { (group, presets) ->
            val open = group.name in expanded
            GuidedCard {
                CollapsibleHeader(
                    title = group.friendlyTitle,
                    count = presets.size,
                    expanded = open,
                    onToggle = { expanded = if (open) expanded - group.name else expanded + group.name },
                    contentPadding = PaddingValues(vertical = 4.dp)
                )
                if (!open) return@GuidedCard
                if (group.countryProfiles) {
                    NoticeCard(
                        title = "Based on published information",
                        text = "Each name identifies a document generation. Some chip details are approximations. " +
                            "Developer mode shows the sources and exactly what is known.",
                        icon = Icons.Outlined.Info,
                        tone = NoticeTone.INFO
                    )
                }
                if (group == PresetGroup.FORGED) {
                    NoticeCard(
                        title = "These look real, but aren't",
                        text = "Each fails one security check. An app that checks passports properly should say " +
                            "so, or refuse it.",
                        icon = Icons.Outlined.GppBad,
                        tone = NoticeTone.WARNING
                    )
                }
                presets.forEach { preset ->
                    // The technical name in brackets is for developer mode
                    PresetRow(
                        preset = preset,
                        summary = preset.guidedSummary(),
                        onClick = { onSelected(preset) }
                    )
                }
            }
        }
    }
}

private val TechnicalSuffix = Regex(""" \([^)]*\)$""")

private fun PassportPreset.guidedSummary(): String {
    if (!group.countryProfiles) return description.replace(TechnicalSuffix, "")
    val profile = ChipProfiles.byId(id.removePrefix("profile-"))
    val document = profile.documentType?.noun ?: "document"
    val security = if (profile.accessControl.supportsPace) "newer" else "older"
    return "Virtual $document with $security chip security." +
        if (profile.activeAuthentication != null) " Includes an anti-copy check." else ""
}
