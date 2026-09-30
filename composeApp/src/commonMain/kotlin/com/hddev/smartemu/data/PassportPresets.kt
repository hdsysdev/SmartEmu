package com.hddev.smartemu.data

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * What a ready-made document is for, in the order they're offered.
 */
enum class PresetGroup(val title: String) {
    EVERYDAY("Everyday"),
    COUNTRIES("By country"),
    TRICKY_DETAILS("Tricky details"),
    OTHER_DOCUMENTS("Other documents"),
    CHIP_SECURITY("Chip security"),
    FORGED("Should be refused")
}

/**
 * A ready-made document, for trying a reader against a common or awkward case without typing it in. [build] makes
 * it for a given day, so that one meant to be valid stays valid and an expired one stays expired.
 */
class PassportPreset(
    val id: String,
    val group: PresetGroup,
    val title: String,
    val description: String,
    val build: (today: LocalDate) -> PassportData
)

/**
 * The ready-made documents the app offers, among them a specimen of each country's chip profile. None has a portrait
 * or a CAN: applying one keeps the holder's photo, which is theirs rather than the preset's, and the CAN, which only
 * matters with PACE.
 */
object PassportPresets {

    val all: List<PassportPreset> = listOf(
        preset("everyday", PresetGroup.EVERYDAY, "Everyday passport", "An adult's passport, valid for years") { today ->
            adult(today)
        },
        preset("child", PresetGroup.EVERYDAY, "Child's passport", "A 7-year-old's, valid for five years") { today ->
            adult(today).copy(
                firstName = "Maya",
                lastName = "Okafor",
                gender = "F",
                dateOfBirth = today.minus(DatePeriod(years = 7, months = 2)),
                expiryDate = today.plus(DatePeriod(years = 4, months = 3))
            )
        },
        preset("expired", PresetGroup.EVERYDAY, "Expired passport", "Ran out three months ago") { today ->
            adult(today).copy(expiryDate = today.minus(DatePeriod(months = 3)))
        },
        preset("expiring", PresetGroup.EVERYDAY, "Expires next week", "Still valid, but only for seven more days") { today ->
            adult(today).copy(expiryDate = today.plus(DatePeriod(days = 7)))
        },

        preset("long-name", PresetGroup.TRICKY_DETAILS, "Very long name", "Too long for the code lines, so it's cut short there") { today ->
            adult(today).copy(
                firstName = "Maximiliana Alexandrina Josephine",
                lastName = "Montgomery-Worthington"
            )
        },
        preset("accents", PresetGroup.TRICKY_DETAILS, "Accented name", "Letters such as ü, ø and é, spelled out in the code lines") { today ->
            adult(today).copy(firstName = "Zoë Renée", lastName = "Müller-Østergård", issuingCountry = "DNK", nationality = "DNK")
        },
        preset("apostrophe", PresetGroup.TRICKY_DETAILS, "Apostrophe and spaces", "A surname with an apostrophe and several words") { today ->
            adult(today).copy(firstName = "Mary Ann", lastName = "O'Neill de la Cruz", issuingCountry = "IRL", nationality = "IRL")
        },
        preset("sex-x", PresetGroup.TRICKY_DETAILS, "Sex shown as X", "Neither male nor female") { today ->
            adult(today).copy(firstName = "Robin", lastName = "Taylor", gender = "X", issuingCountry = "AUS", nationality = "AUS")
        },
        preset("look-alike", PresetGroup.TRICKY_DETAILS, "Look-alike characters", "A document number mixing O and 0, I and 1, S and 5") { today ->
            adult(today).copy(passportNumber = "O0I1S5B8")
        },
        preset("stateless", PresetGroup.TRICKY_DETAILS, "Stateless holder", "Issued by one country to someone with no nationality") { today ->
            adult(today).copy(firstName = "Amir", lastName = "Haddad", nationality = "XXA", issuingCountry = "DEU")
        },

        preset("id-card", PresetGroup.OTHER_DOCUMENTS, "ID card", "A bank-card-sized identity card, code lines on the back") { today ->
            adult(today).copy(
                documentType = DocumentType.ID_CARD,
                passportNumber = "SPECI2021",
                firstName = "Willeke Liselotte",
                lastName = "De Bruijn",
                gender = "F",
                issuingCountry = "NLD",
                nationality = "NLD"
            )
        },
        preset("residence-permit", PresetGroup.OTHER_DOCUMENTS, "Residence permit", "A card for someone living in a country that isn't theirs") { today ->
            adult(today).copy(
                documentType = DocumentType.RESIDENCE_PERMIT,
                passportNumber = "ZR8016421",
                firstName = "Priya",
                lastName = "Raghunathan",
                gender = "F",
                issuingCountry = "GBR",
                nationality = "IND"
            )
        },

        preset("older-chip", PresetGroup.CHIP_SECURITY, "Older passport", "Only the older way of unlocking the chip (BAC)") { today ->
            adult(today).copy(accessControl = AccessControl.BAC_ONLY)
        },
        preset("newest-chip", PresetGroup.CHIP_SECURITY, "Newest security only", "Refuses the older way of unlocking (PACE only)") { today ->
            adult(today).copy(accessControl = AccessControl.PACE_ONLY)
        },
        preset("anti-copy", PresetGroup.CHIP_SECURITY, "Anti-copy chip", "Proves it isn't a copy (Active Authentication and PACE-CAM)") { today ->
            adult(today).copy(activeAuthentication = true, paceMapping = PaceMapping.CHIP_AUTHENTICATION)
        },

        forged("altered-details", ChipFault.ALTERED_DETAILS, "Changed details", "The details on the chip were changed after it was made"),
        forged("swapped-photo", ChipFault.SWAPPED_PHOTO, "Swapped photo", "The photo on the chip was replaced"),
        forged("broken-signature", ChipFault.BROKEN_SIGNATURE, "Broken signature", "The chip's signature doesn't check out"),
        forged("expired-signer", ChipFault.EXPIRED_SIGNER, "Expired signer", "Signed with a certificate that has run out"),
        forged("unknown-issuer", ChipFault.UNTRUSTED_SIGNER, "Unknown issuer", "Signed by an authority no one trusts"),
        forged("cloned-chip", ChipFault.CLONED_CHIP, "Copied chip", "A copy of a real chip, which fails the anti-copy check")
    ) + ChipProfiles.all.mapNotNull(::countryPreset)

    fun byId(id: String): PassportPreset? = all.find { it.id == id }

    private fun preset(
        id: String,
        group: PresetGroup,
        title: String,
        description: String,
        build: (LocalDate) -> PassportData
    ) = PassportPreset(id, group, title, description, build)

    private fun forged(id: String, fault: ChipFault, title: String, description: String) =
        preset(id, PresetGroup.FORGED, title, description) { today ->
            adult(today).copy(chipFault = fault, activeAuthentication = fault.needsActiveAuthentication)
        }

    /**
     * The specimen document of a country's profile, with its chip: issued a year ago, and valid for as long as the
     * issuer makes an adult's.
     */
    private fun countryPreset(profile: ChipProfile): PassportPreset? {
        val sample = profile.sample ?: return null
        return preset("profile-${profile.id}", PresetGroup.COUNTRIES, profile.title, profile.summary) { today ->
            val issued = today.minus(DatePeriod(years = 1))
            PassportData(
                passportNumber = sample.documentNumber,
                firstName = sample.firstName,
                lastName = sample.lastName,
                gender = sample.gender,
                dateOfBirth = sample.dateOfBirth,
                dateOfIssue = issued,
                expiryDate = issued.plus(DatePeriod(years = profile.validity.adultYears)),
                personalNumber = sample.personalNumber,
                placeOfBirth = sample.placeOfBirth,
                issuingAuthority = sample.issuingAuthority
            ).withChipProfile(profile)
        }
    }

    /** The adult the other presets start from, on a passport issued a year ago for ten years. */
    private fun adult(today: LocalDate) = PassportData(
        documentType = DocumentType.PASSPORT,
        passportNumber = "SPEC24681",
        firstName = "Anna Maria",
        lastName = "Eriksson",
        gender = "F",
        dateOfBirth = LocalDate(1986, 3, 14),
        expiryDate = today.plus(DatePeriod(years = 9)),
        issuingCountry = "SWE",
        nationality = "SWE"
    )
}
