package com.hddev.smartemu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Face
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.PassportSimulatorUiState
import com.hddev.smartemu.data.Portrait
import com.hddev.smartemu.ui.components.AdditionalDetailsCallbacks
import com.hddev.smartemu.ui.components.AppButton
import com.hddev.smartemu.ui.components.ButtonEmphasis
import com.hddev.smartemu.ui.components.EditingLockedBanner
import com.hddev.smartemu.ui.components.NextStepButton
import com.hddev.smartemu.ui.components.PassportInputForm
import com.hddev.smartemu.ui.components.PassportPreview
import com.hddev.smartemu.ui.components.PortraitImage
import com.hddev.smartemu.ui.components.PortraitPicker
import com.hddev.smartemu.ui.components.SectionCard
import com.hddev.smartemu.ui.components.rememberPortraitPicker
import com.hddev.smartemu.ui.navigation.AppDestination
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel

/** Screens are no wider than this, so that lines stay readable on tablets and in landscape. */
internal val MaxContentWidth = 640.dp

/**
 * The passport being emulated: its data page as it would be printed, and the details and portrait it's made from.
 * Quick start fills it with sample data or a ready-made document from [onLoadPreset]. [onNext] leads to the next
 * setup screen; [onShowMessage] reports a photo that couldn't be used.
 */
@Composable
fun PassportScreen(
    uiState: PassportSimulatorUiState,
    viewModel: PassportSimulatorViewModel,
    onOpenDataPage: () -> Unit,
    onLoadPreset: () -> Unit,
    onNext: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val passportData = uiState.passportData
    val editable = uiState.isPassportFormEnabled()
    val portraitPicker = rememberPortraitPicker(onPicked = viewModel::updatePortrait, onFailed = onShowMessage)

    ScreenColumn(modifier = modifier) {
        if (!editable && uiState.simulationStatus.isActiveOrStarting()) {
            EditingLockedBanner(onStopEmulation = viewModel::stopSimulation, stopEnabled = uiState.canStopSimulation())
        }

        QuickStartSection(
            fresh = passportData.hasNoHolderDetails(),
            enabled = editable,
            onLoadPreset = onLoadPreset,
            onFillSampleData = viewModel::autofillPassportData
        )

        PassportPreview(passportData = passportData, onOpenFullScreen = onOpenDataPage)

        PassportInputForm(
            passportData = passportData,
            validationErrors = uiState.validationErrors,
            enabled = editable,
            onPassportNumberChange = viewModel::updatePassportNumber,
            onFirstNameChange = viewModel::updateFirstName,
            onLastNameChange = viewModel::updateLastName,
            onDateOfBirthChange = viewModel::updateDateOfBirth,
            onExpiryDateChange = viewModel::updateExpiryDate,
            onGenderChange = viewModel::updateGender,
            onIssuingCountryChange = viewModel::updateIssuingCountry,
            onNationalityChange = viewModel::updateNationality,
            onDocumentTypeChange = viewModel::updateDocumentType,
            additionalDetails = AdditionalDetailsCallbacks(
                onPersonalNumberChange = viewModel::updatePersonalNumber,
                onPlaceOfBirthChange = viewModel::updatePlaceOfBirth,
                onIssuingAuthorityChange = viewModel::updateIssuingAuthority,
                onDateOfIssueChange = viewModel::updateDateOfIssue
            )
        )

        PortraitSection(
            portrait = passportData.portrait,
            picker = portraitPicker,
            enabled = editable,
            onRemove = { viewModel.updatePortrait(null) }
        )

        NextStepButton(
            label = "Next: ${AppDestination.CHIP.title}",
            onClick = onNext,
            icon = AppDestination.CHIP.selectedIcon
        )
    }
}

/**
 * Ways to fill the whole document at once: a ready-made one, with its chip settings, or sample holder details. The
 * buttons stand out while the document is still empty.
 */
@Composable
private fun QuickStartSection(
    fresh: Boolean,
    enabled: Boolean,
    onLoadPreset: () -> Unit,
    onFillSampleData: () -> Unit
) {
    SectionCard(
        title = "Quick start",
        subtitle = if (fresh) {
            "Enter the details below, or start from a ready-made document. You can change anything afterwards."
        } else {
            "Replace these details with a ready-made document or sample data"
        },
        icon = Icons.Outlined.Bolt
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AppButton(
                text = "Load a preset",
                onClick = onLoadPreset,
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                emphasis = if (fresh) ButtonEmphasis.High else ButtonEmphasis.Medium,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            AppButton(
                text = "Sample data",
                onClick = onFillSampleData,
                icon = Icons.Filled.AutoFixHigh,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The holder's portrait, with the ways to replace or remove it.
 */
@Composable
private fun PortraitSection(
    portrait: Portrait?,
    picker: PortraitPicker,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    SectionCard(
        title = "Portrait",
        subtitle = if (portrait != null) {
            "Printed on the data page and stored on the chip"
        } else {
            "Optional. Without a photo the chip stores a grey placeholder."
        },
        icon = Icons.Outlined.Face
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PortraitImage(portrait = portrait, modifier = Modifier.width(72.dp).height(96.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppButton(
                    text = "Choose photo",
                    onClick = picker::chooseFromLibrary,
                    icon = Icons.Filled.PhotoLibrary,
                    enabled = enabled
                )
                if (picker.canTakeSelfie) {
                    AppButton(
                        text = "Take selfie",
                        onClick = picker::takeSelfie,
                        icon = Icons.Filled.PhotoCamera,
                        enabled = enabled
                    )
                }
                if (portrait != null) {
                    AppButton(
                        text = "Remove",
                        onClick = onRemove,
                        icon = Icons.Filled.Delete,
                        emphasis = ButtonEmphasis.Low,
                        destructive = true,
                        enabled = enabled
                    )
                }
            }
        }
    }
}

private fun PassportData.hasNoHolderDetails(): Boolean =
    passportNumber.isBlank() && firstName.isBlank() && lastName.isBlank() && dateOfBirth == null && expiryDate == null

/**
 * Scrolling column that the form screens share, with room for the keyboard, centred and no wider than
 * [MaxContentWidth].
 */
@Composable
internal fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}
