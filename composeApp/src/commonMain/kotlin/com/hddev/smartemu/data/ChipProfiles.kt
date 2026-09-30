package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate

/**
 * The kinds of document the chip can behave like. Everything a profile claims about a real document carries a
 * [Provenance]: "specification" where a standard or regulation requires it of every such document, "reported" where
 * public sources describe it, and "assumed" where nothing public was found and a common choice stands in. None of
 * it has been checked against a real chip; tools/pkd_profile.py checks the signature algorithms against the Document
 * Signer certificates in the ICAO PKD.
 *
 * Every document is signed by a SmartEmu test Document Signer, never an issuer's own: a reader accepts it only if it
 * trusts the matching SmartEmu Test CSCA from the repository's test-pki directory.
 */
object ChipProfiles {

    const val GENERIC_ID = "generic"

    private const val ICAO_9303 = "ICAO Doc 9303, 8th edition (2021), parts 3, 4, 5, 10 and 11"
    private const val BSI_TR_03110 = "BSI TR-03110, Advanced Security Mechanisms for Machine Readable Travel Documents"
    private const val EU_PASSPORT_REGULATION =
        "Council Regulation (EC) No 2252/2004, and the Commission decisions on its technical specifications: " +
            "C(2006) 2909 (Extended Access Control) and C(2011) 5499 (Supplemental Access Control, PACE)"
    private const val EU_ID_CARD_REGULATION = "Regulation (EU) 2019/1157 on the security of identity cards (from 2 August 2021)"
    private const val BSI_TR_03116_2 = "BSI TR-03116-2, cryptographic requirements for German eID documents and eMRTDs"
    private const val FINGERPRINTING_STUDY =
        "H. Richter, W. Mostowski and E. Poll, \"Fingerprinting Passports\", NLUUG spring conference 2008: " +
            "how first-generation chips answer unexpected commands"

    private const val SIGNER_NOTE =
        "Signed by a SmartEmu test Document Signer, with \"TEST\" in its name, not the issuer's: readers must trust " +
            "the SmartEmu Test CSCA instead of the issuer's own."
    private const val FINGERPRINT_NOTE =
        "DG3 (fingerprints) exists, but only a terminal that passes Terminal Authentication may read it, and no " +
            "terminal certificate can chain to a test CVCA. Every reader is refused, as most real ones are."

    private val generic = ChipProfile(
        id = GENERIC_ID,
        title = "Generic ICAO chip",
        shortTitle = "a generic ICAO chip",
        country = null,
        documentType = null,
        summary = "Follows ICAO 9303 with the common choices: PACE on NIST P-256 with AES-128, and passive " +
            "authentication with ECDSA. Any country, any document.",
        accessControl = AccessControl.BAC_AND_PACE,
        provenance = ProfileAspect.entries.associateWith { Provenance.SPECIFICATION },
        sources = listOf(ICAO_9303),
        notes = listOf(SIGNER_NOTE)
    )

    private val euPassport = ChipProfile(
        id = "eu-passport",
        title = "EU passport (current design)",
        shortTitle = "an EU passport",
        country = null,
        documentType = DocumentType.PASSPORT,
        summary = "What EU law requires of every member state's passport: BAC and PACE, Chip Authentication, and " +
            "fingerprints that only authorised terminals may read. The rest varies by country.",
        accessControl = AccessControl.BAC_AND_PACE,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.ASSUMED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.SPECIFICATION,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf(EU_PASSPORT_REGULATION, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            "Member states choose their own curves, Active Authentication and optional data groups; a country's " +
                "own profile is more precise where there is one.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val euIdCard = ChipProfile(
        id = "eu-id-card",
        title = "EU ID card (2021 design)",
        shortTitle = "an EU ID card",
        country = null,
        documentType = DocumentType.ID_CARD,
        summary = "An identity card issued under the 2019 EU regulation: an ICAO chip with the holder's photo and " +
            "two fingerprints, the fingerprints for authorised terminals only.",
        accessControl = AccessControl.BAC_AND_PACE,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.ASSUMED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.ASSUMED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.DATA_GROUPS to Provenance.SPECIFICATION,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.SPECIFICATION,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf(EU_ID_CARD_REGULATION, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            "Whether a card also accepts BAC varies by country; some accept PACE only.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val germanSigner = SodSpec(
        signerKey = KeySpec.Ec(EcCurve.BRAINPOOL_P256R1),
        passportSigner = "smartemu-test-ds-de",
        cardSigner = "smartemu-test-ds-de-id",
        csca = "SmartEmu Test CSCA DE"
    )
    private val germanMrz = MrzRules(
        documentNumberFormat = "9 characters: digits and the consonants C F G H J K L M N P R T V W X Y Z"
    )
    private val germanValidity = Validity(adultYears = 10, childYears = 6, adultFromAge = 24)
    private val germanSample = ProfileSample(
        firstName = "Erika",
        lastName = "Mustermann",
        gender = "F",
        dateOfBirth = LocalDate(1964, 8, 12),
        documentNumber = "C01X00T47",
        placeOfBirth = "Berlin",
        issuingAuthority = "Stadt Köln"
    )

    private val germanPassport = ChipProfile(
        id = "de-passport-2017",
        title = "Germany · passport (2017 design)",
        shortTitle = "a German passport",
        country = "DEU",
        documentType = DocumentType.PASSPORT,
        summary = "The German ePass: PACE and BAC on Brainpool curves, Chip Authentication instead of Active " +
            "Authentication, and protected fingerprints. \"D\" is Germany's code in the MRZ.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = germanSigner,
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample,
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED,
            ProfileAspect.MRZ to Provenance.SPECIFICATION,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf(BSI_TR_03116_2, BSI_TR_03110, EU_PASSPORT_REGULATION, "Passgesetz § 5 (validity)", ICAO_9303),
        notes = listOf(
            "Germany relies on Chip Authentication to prove the chip genuine; it doesn't use Active Authentication.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val germanIdCard = ChipProfile(
        id = "de-id-card",
        title = "Germany · ID card (Personalausweis)",
        shortTitle = "a German ID card",
        country = "DEU",
        documentType = DocumentType.ID_CARD,
        summary = "The German identity card's travel document application: PACE only, with the MRZ or the CAN " +
            "printed on the front, on Brainpool curves, with Chip Authentication and protected fingerprints.",
        accessControl = AccessControl.PACE_ONLY,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = germanSigner,
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample.copy(documentNumber = "L01X00T47"),
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.DATA_GROUPS to Provenance.SPECIFICATION,
            ProfileAspect.SIGNATURE to Provenance.REPORTED,
            ProfileAspect.MRZ to Provenance.SPECIFICATION,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf(
            BSI_TR_03116_2, BSI_TR_03110, EU_ID_CARD_REGULATION, "Personalausweisgesetz § 6 (validity)", ICAO_9303
        ),
        notes = listOf(
            "Only the travel document application is emulated, not the eID application for online identification.",
            "Fingerprints are mandatory on cards issued from August 2021.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val germanPassport2005 = ChipProfile(
        id = "de-passport-2005",
        title = "Germany · passport (2005, first generation)",
        shortTitle = "a first-generation German passport",
        country = "DEU",
        documentType = DocumentType.PASSPORT,
        summary = "One of the first chipped German passports: BAC only, no fingerprints, no anti-copy protection. " +
            "Every real one expired by 2017, so the ready-made one is dated as if new.",
        accessControl = AccessControl.BAC_ONLY,
        sod = germanSigner,
        mrz = germanMrz,
        errorResponses = ErrorResponses(outOfSequence = 0x6700),
        validity = germanValidity,
        sample = germanSample,
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.SPECIFICATION,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.SPECIFICATION,
            ProfileAspect.ERROR_RESPONSES to Provenance.REPORTED
        ),
        sources = listOf(FINGERPRINTING_STUDY, ICAO_9303),
        notes = listOf(
            "Fingerprints were added from November 2007, so this generation has no DG3.",
            "Answers EXTERNAL AUTHENTICATE without a GET CHALLENGE before it with 6700, as the 2008 study measured.",
            SIGNER_NOTE
        )
    )

    private val dutchMrz = MrzRules(
        personalNumberInMrz = true,
        documentNumberFormat = "9 characters: letters and digits, never the letter O, the last a digit"
    )
    private val dutchSigner = SodSpec(
        passportSigner = "smartemu-test-ds-nl",
        cardSigner = "smartemu-test-ds-nl-id",
        csca = "SmartEmu Test CSCA NL"
    )
    private val dutchValidity = Validity(adultYears = 10, childYears = 5, adultFromAge = 18)
    private val dutchSample = ProfileSample(
        firstName = "Willeke Liselotte",
        lastName = "De Bruijn",
        gender = "F",
        dateOfBirth = LocalDate(1965, 3, 10),
        documentNumber = "SPECI2014",
        personalNumber = "999999990",
        placeOfBirth = "Den Haag",
        issuingAuthority = "Burg. van Den Haag"
    )

    private val dutchPassport = ChipProfile(
        id = "nl-passport",
        title = "Netherlands · passport (2014 design)",
        shortTitle = "a Dutch passport",
        country = "NLD",
        documentType = DocumentType.PASSPORT,
        summary = "The Dutch passport: BAC and PACE, Active Authentication, Chip Authentication and protected " +
            "fingerprints, with the citizen service number (BSN) in the MRZ.",
        accessControl = AccessControl.BAC_AND_PACE,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        sod = dutchSigner,
        mrz = dutchMrz,
        validity = dutchValidity,
        sample = dutchSample,
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.REPORTED,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf(
            "Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)",
            EU_PASSPORT_REGULATION,
            ICAO_9303
        ),
        notes = listOf(
            "Designs from 2021 no longer carry the BSN in the MRZ; turn it off by leaving the personal number blank.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val dutchIdCard = ChipProfile(
        id = "nl-id-card",
        title = "Netherlands · ID card (2014 design)",
        shortTitle = "a Dutch ID card",
        country = "NLD",
        documentType = DocumentType.ID_CARD,
        summary = "The Dutch identity card: document code \"I\" rather than \"ID\", and the BSN in the MRZ, right " +
            "after the document number.",
        accessControl = AccessControl.BAC_AND_PACE,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        dataGroups = setOf(11, 12),
        sod = dutchSigner,
        mrz = dutchMrz.copy(documentCode = "I"),
        validity = dutchValidity,
        sample = dutchSample,
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.ASSUMED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.ASSUMED,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.REPORTED,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf("Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)", ICAO_9303),
        notes = listOf(
            "Cards of this design carry no fingerprints; those issued from August 2021 do, and no longer the BSN.",
            SIGNER_NOTE
        )
    )

    private val dutchPassport2006 = ChipProfile(
        id = "nl-passport-2006",
        title = "Netherlands · passport (2006, first generation)",
        shortTitle = "a first-generation Dutch passport",
        country = "NLD",
        documentType = DocumentType.PASSPORT,
        summary = "One of the first chipped Dutch passports: BAC only, and Active Authentication with a 1024-bit " +
            "RSA key. Every real one expired by 2016, so the ready-made one is dated as if new.",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Rsa(1024),
        sod = SodSpec(
            signerKey = KeySpec.Rsa(2048),
            passportSigner = "smartemu-test-ds-nl-rsa",
            cardSigner = "smartemu-test-ds-nl-rsa",
            csca = "SmartEmu Test CSCA NL"
        ),
        mrz = dutchMrz,
        errorResponses = ErrorResponses(outOfSequence = 0x6982),
        validity = Validity(adultYears = 5, childYears = 5, adultFromAge = 18),
        sample = dutchSample.copy(documentNumber = "SPECI2006"),
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.SPECIFICATION,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.REPORTED,
            ProfileAspect.ERROR_RESPONSES to Provenance.REPORTED
        ),
        sources = listOf(FINGERPRINTING_STUDY, ICAO_9303),
        notes = listOf(
            "Active Authentication signs with ISO/IEC 9796-2 and SHA-1, the scheme RSA chips of the time used.",
            "Answers EXTERNAL AUTHENTICATE without a GET CHALLENGE before it with 6982, as the 2008 study measured.",
            SIGNER_NOTE
        )
    )

    private val britishPassport = ChipProfile(
        id = "gb-passport",
        title = "United Kingdom · passport (2020 design)",
        shortTitle = "a British passport",
        country = "GBR",
        documentType = DocumentType.PASSPORT,
        summary = "The British passport: an ICAO chip with no fingerprints, as the UK has never stored them. " +
            "Little else about the chip is public.",
        accessControl = AccessControl.BAC_AND_PACE,
        dataGroups = setOf(11, 12),
        sod = SodSpec(passportSigner = "smartemu-test-ds-gb", cardSigner = "smartemu-test-ds-gb", csca = "SmartEmu Test CSCA GB"),
        mrz = MrzRules(documentNumberFormat = "9 digits"),
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample(
            firstName = "Angela Zoe",
            lastName = "Specimen",
            gender = "F",
            dateOfBirth = LocalDate(1988, 12, 4),
            documentNumber = "925665416",
            placeOfBirth = "Crawley",
            issuingAuthority = "HMPO"
        ),
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.ASSUMED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.ASSUMED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.REPORTED,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf("HM Passport Office: 10 years' validity from age 16, 5 years below", ICAO_9303),
        notes = listOf(SIGNER_NOTE)
    )

    private val americanPassport = ChipProfile(
        id = "us-passport-2021",
        title = "United States · passport (Next Generation, 2021)",
        shortTitle = "a US passport",
        country = "USA",
        documentType = DocumentType.PASSPORT,
        summary = "The US Next Generation Passport: an ICAO chip with no fingerprints, and document numbers of a " +
            "letter and eight digits. Little else about the chip is public.",
        accessControl = AccessControl.BAC_AND_PACE,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        sod = SodSpec(passportSigner = "smartemu-test-ds-us", cardSigner = "smartemu-test-ds-us", csca = "SmartEmu Test CSCA US"),
        mrz = MrzRules(documentNumberFormat = "A letter and 8 digits"),
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample(
            firstName = "Happy",
            lastName = "Traveler",
            gender = "M",
            dateOfBirth = LocalDate(1965, 1, 1),
            documentNumber = "A12345678",
            placeOfBirth = "Washington, D.C., U.S.A.",
            issuingAuthority = "United States Department of State"
        ),
        provenance = mapOf(
            ProfileAspect.ACCESS_CONTROL to Provenance.ASSUMED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.ASSUMED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.ASSUMED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.ASSUMED,
            ProfileAspect.SIGNATURE to Provenance.ASSUMED,
            ProfileAspect.MRZ to Provenance.REPORTED,
            ProfileAspect.ERROR_RESPONSES to Provenance.ASSUMED
        ),
        sources = listOf("22 CFR 51.4 (validity: 10 years from age 16, 5 years below)", ICAO_9303),
        notes = listOf(SIGNER_NOTE)
    )

    val all: List<ChipProfile> = listOf(
        generic,
        euPassport,
        euIdCard,
        germanPassport,
        germanIdCard,
        germanPassport2005,
        dutchPassport,
        dutchIdCard,
        dutchPassport2006,
        britishPassport,
        americanPassport
    )

    val default: ChipProfile get() = generic

    /** The profile with the given id, or the generic one for an id no profile has, as one saved by an older version. */
    fun byId(id: String): ChipProfile = all.find { it.id == id } ?: generic

    /** The profiles that fit a document of this type, generic first. */
    fun forDocumentType(type: DocumentType): List<ChipProfile> = all.filter { it.fits(type) }
}
