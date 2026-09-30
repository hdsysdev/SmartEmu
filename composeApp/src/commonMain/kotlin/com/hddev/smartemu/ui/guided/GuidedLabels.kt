package com.hddev.smartemu.ui.guided

import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PresetGroup
import com.hddev.smartemu.data.ReadOutcome

// The guided screens' words for what developer mode names in a reader tester's terms

/** What to call the document in a sentence, as in "Use this passport". */
internal val DocumentType.noun: String get() = displayName.lowercase().replace("id card", "ID card")

/** Where the code lines the other app's camera scans are. */
internal val DocumentType.scanPageName: String get() = if (isCard) "back of the card" else "photo page"

internal val DocumentType.friendlyDescription: String
    get() = when (this) {
        DocumentType.PASSPORT -> "A passport booklet. The code lines are at the bottom of the photo page."
        DocumentType.ID_CARD -> "A bank-card-sized ID card. The code lines are on the back."
        DocumentType.RESIDENCE_PERMIT -> "A card that lets someone live in a country. The code lines are on the back."
    }

internal val ChipFault.friendlyName: String
    get() = when (this) {
        ChipFault.NONE -> "Genuine (recommended)"
        ChipFault.ALTERED_DETAILS -> "Changed details"
        ChipFault.SWAPPED_PHOTO -> "Swapped photo"
        ChipFault.BROKEN_SIGNATURE -> "Broken signature"
        ChipFault.EXPIRED_SIGNER -> "Expired signer"
        ChipFault.UNTRUSTED_SIGNER -> "Unknown issuer"
        ChipFault.CLONED_CHIP -> "Copied chip"
    }

internal val ChipFault.friendlyDescription: String
    get() = when (this) {
        ChipFault.NONE -> "Passes every security check."
        ChipFault.ALTERED_DETAILS -> "The details on the chip were changed after it was made."
        ChipFault.SWAPPED_PHOTO -> "The photo on the chip was replaced."
        ChipFault.BROKEN_SIGNATURE -> "The chip's digital signature doesn't check out."
        ChipFault.EXPIRED_SIGNER -> "It was signed with a certificate that has run out."
        ChipFault.UNTRUSTED_SIGNER -> "It was signed by an authority no one trusts."
        ChipFault.CLONED_CHIP -> "A copy of a real chip. It fails the anti-copy check, which it turns on."
    }

internal val PresetGroup.friendlyTitle: String
    get() = when (this) {
        PresetGroup.EVERYDAY -> "Everyday passports"
        PresetGroup.COUNTRIES -> "By country and generation"
        PresetGroup.TRICKY_DETAILS -> "Unusual details"
        PresetGroup.OTHER_DOCUMENTS -> "ID cards and permits"
        PresetGroup.CHIP_SECURITY -> "Different chips"
        PresetGroup.FORGED -> "Fakes an app should refuse"
    }

internal val ReadOutcome.friendlyName: String
    get() = when (this) {
        ReadOutcome.COMPLETE -> "Read everything"
        ReadOutcome.PARTIAL -> "Read some of it"
        ReadOutcome.WRONG_DETAILS -> "Details didn't match"
        ReadOutcome.UNSUPPORTED_PROTOCOL -> "Type not supported"
        ReadOutcome.INTERRUPTED -> "Phones moved apart"
    }
