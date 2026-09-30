package com.hddev.smartemu.ui.guided

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.NfcSwitch
import com.hddev.smartemu.ui.components.DocumentImages

/**
 * Where every visit starts: the passport, or an invitation to make one, and the button that puts it to use.
 * Anything that stops it being used, such as NFC being off, shows above it with a way to fix it.
 */
@Composable
fun HomeScreen(
    uiState: PassportSimulatorUiState,
    nfcSwitch: NfcSwitch,
    onUseSampleDetails: () -> Unit,
    onChoosePreset: () -> Unit,
    onShowHistory: () -> Unit,
    onMakeGenuine: () -> Unit,
    onEdit: () -> Unit,
    onShowPhotoPage: () -> Unit,
    onUsePassport: () -> Unit,
    onRequestPermission: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit
) {
    val passportData = uiState.passportData
    val hasPassport = !passportData.hasNoHolderDetails()
    val readiness = readiness(uiState, nfcSwitch)

    GuidedScaffold(
        title = "PassportEmu",
        actions = {
            IconButton(onClick = onShowHistory) {
                Icon(Icons.Outlined.History, contentDescription = "Past reads")
            }
            IconButton(onClick = onHelp) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        },
        bottomBar = {
            if (hasPassport) {
                BottomActions {
                    AppButton(
                        text = "Use this ${passportData.documentType.noun}",
                        onClick = onUsePassport,
                        icon = Icons.Filled.PlayArrow,
                        emphasis = ButtonEmphasis.High,
                        enabled = readiness == Readiness.READY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                    )
                    Text(
                        text = when (readiness) {
                            Readiness.READY -> "Have the phone that will read it nearby."
                            Readiness.DETAILS_INCOMPLETE -> "Finish the ${passportData.documentType.noun} details to use it."
                            else -> "Sort out the message above to use the ${passportData.documentType.noun}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) {
        ReadinessNotice(readiness = readiness, nfcSwitch = nfcSwitch, onRequestPermission = onRequestPermission, onEdit = onEdit)

        if (hasPassport && passportData.chipFault != ChipFault.NONE) {
            NoticeCard(
                title = "This is set up as a fake",
                text = "${passportData.chipFault.friendlyName}: ${passportData.chipFault.friendlyDescription} An app " +
                    "that checks properly should refuse it.",
                icon = Icons.Outlined.GppBad,
                tone = NoticeTone.WARNING,
                action = { AppButton(text = "Make it genuine", onClick = onMakeGenuine, emphasis = ButtonEmphasis.Medium) }
            )
        }

        if (hasPassport) {
            PassportCard(
                passportData = passportData,
                onShowPhotoPage = onShowPhotoPage,
                onEdit = onEdit,
                onChoosePreset = onChoosePreset
            )
            HowItWorksCard(passportData.documentType)
        } else {
            CreatePassportCard(
                onUseSampleDetails = onUseSampleDetails,
                onChoosePreset = onChoosePreset,
                onEnterDetails = onEdit
            )
        }
    }
}

/** What, if anything, stops the passport being used, most fundamental first. */
internal enum class Readiness { NO_NFC, NFC_OFF, NEEDS_PERMISSION, DETAILS_INCOMPLETE, READY }

internal fun readiness(uiState: PassportSimulatorUiState, nfcSwitch: NfcSwitch): Readiness = when {
    !uiState.nfcAvailable -> Readiness.NO_NFC
    !nfcSwitch.isOn -> Readiness.NFC_OFF
    !uiState.hasNfcPermission -> Readiness.NEEDS_PERMISSION
    !uiState.passportData.isValid() -> Readiness.DETAILS_INCOMPLETE
    else -> Readiness.READY
}

@Composable
private fun ReadinessNotice(
    readiness: Readiness,
    nfcSwitch: NfcSwitch,
    onRequestPermission: () -> Unit,
    onEdit: () -> Unit
) {
    when (readiness) {
        Readiness.NO_NFC -> NoticeCard(
            title = "This phone can't be a passport",
            text = "It doesn't have NFC, the contactless feature this needs. Try another Android phone: one that " +
                "can pay contactless usually works.",
            icon = Icons.Outlined.PhonelinkErase,
            tone = NoticeTone.PROBLEM
        )
        Readiness.NFC_OFF -> NoticeCard(
            title = "Turn on NFC",
            text = "NFC lets this phone talk to the other one. You'll usually find it in quick settings, next to " +
                "Wi-Fi and Bluetooth.",
            icon = Icons.Outlined.Nfc,
            tone = NoticeTone.WARNING,
            action = { AppButton(text = "Open settings", onClick = nfcSwitch::openSettings, emphasis = ButtonEmphasis.Medium) }
        )
        Readiness.NEEDS_PERMISSION -> NoticeCard(
            title = "Allow NFC",
            text = "This app needs your permission to use NFC.",
            icon = Icons.Outlined.Nfc,
            tone = NoticeTone.WARNING,
            action = { AppButton(text = "Allow", onClick = onRequestPermission, emphasis = ButtonEmphasis.Medium) }
        )
        Readiness.DETAILS_INCOMPLETE -> Unit
        Readiness.READY -> Unit
    }
}

/**
 * First visit: what the passport is, with the quickest way to one, sample details, and the way to one's own.
 */
@Composable
private fun CreatePassportCard(onUseSampleDetails: () -> Unit, onChoosePreset: () -> Unit, onEnterDetails: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        PassportPhoneIllustration(modifier = Modifier.heightIn(max = 180.dp))
        Headline(
            title = "Create your passport",
            text = "Start with sample details, pick a ready-made passport or ID card, or enter your own. You can " +
                "change them at any time."
        )
        AppButton(
            text = "Use sample details",
            onClick = onUseSampleDetails,
            icon = Icons.Filled.AutoFixHigh,
            emphasis = ButtonEmphasis.High,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        )
        AppButton(
            text = "Choose a ready-made one",
            onClick = onChoosePreset,
            icon = Icons.Outlined.CollectionsBookmark,
            emphasis = ButtonEmphasis.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        )
        AppButton(
            text = "Enter my own details",
            onClick = onEnterDetails,
            icon = Icons.Filled.Edit,
            emphasis = ButtonEmphasis.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        )
    }
}

/**
 * The passport's photo page, or the card, as the other app will see it, with the ways to show it large, change it,
 * or swap it for a ready-made one.
 */
@Composable
private fun PassportCard(passportData: PassportData, onShowPhotoPage: () -> Unit, onEdit: () -> Unit, onChoosePreset: () -> Unit) {
    val noun = passportData.documentType.noun
    GuidedCard(title = "Your $noun") {
        DocumentImages(passportData = passportData, onClick = onShowPhotoPage)
        AnimatedVisibility(visible = !passportData.isValid()) {
            NoticeCard(
                title = "Some details need finishing",
                text = "Check the $noun details before using it.",
                icon = Icons.Outlined.ErrorOutline,
                tone = NoticeTone.PROBLEM
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(text = "Edit details", onClick = onEdit, icon = Icons.Filled.Edit)
            AppButton(text = "Show large", onClick = onShowPhotoPage, icon = Icons.Filled.Fullscreen, emphasis = ButtonEmphasis.Low)
            AppButton(text = "Try another", onClick = onChoosePreset, icon = Icons.Outlined.CollectionsBookmark, emphasis = ButtonEmphasis.Low)
        }
    }
}

@Composable
private fun HowItWorksCard(documentType: DocumentType) {
    GuidedCard(title = "How it works") {
        NumberedStep(
            number = 1,
            title = "Show the ${documentType.scanPageName}",
            text = "The app on the other phone photographs this phone's screen."
        )
        NumberedStep(
            number = 2,
            title = "Hold the phones together",
            text = "Back to back, while the app reads the chip. This phone shows how it's going."
        )
        NumberedStep(
            number = 3,
            title = "Check what it read",
            text = "Compare what the app shows with the details on this phone."
        )
    }
}

internal fun PassportData.hasNoHolderDetails(): Boolean =
    passportNumber.isBlank() && firstName.isBlank() && lastName.isBlank() && dateOfBirth == null && expiryDate == null
