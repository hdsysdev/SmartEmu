package com.hddev.smartemu.data

import kotlinx.datetime.LocalDate

/**
 * The kinds of document the chip can behave like. Everything a profile claims about a real document carries a
 * [Provenance]: "specification" where a standard or regulation requires it of every such document, "reported" where
 * public sources describe it, and "assumed" where nothing public was found and a common choice stands in. None of
 * these emulator profiles has been checked against a real chip here. Historical evidence may include the authors'
 * own chip measurements. A CSCA master list establishes PKI authorities, not DS, AA, CA or PACE algorithms.
 *
 * Every document is signed by a PassportEmu test Document Signer, never an issuer's own: a reader accepts it only if it
 * trusts the matching test CSCA from the repository's test-pki directory (legacy CNs retain SmartEmu).
 */
object ChipProfiles {

    const val GENERIC_ID = "generic"

    private const val ICAO_9303 = "ICAO Doc 9303 — https://www.icao.int/publications/doc-series/doc-9303"
    private const val BSI_TR_03110 = "BSI TR-03110 — https://www.bsi.bund.de/EN/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/Technische-Richtlinien/TR-nach-Thema-sortiert/tr03110/tr-03110.html"
    private const val EU_PASSPORT_REGULATION =
        "Council Regulation (EC) No 2252/2004, and the Commission decisions on its technical specifications: " +
            "C(2006) 2909 (Extended Access Control) and C(2011) 5499 (Supplemental Access Control, PACE). " +
            "https://eur-lex.europa.eu/eli/reg/2004/2252/oj"
    private const val EU_ID_CARD_REGULATION = "Regulation (EU) 2019/1157 — https://eur-lex.europa.eu/eli/reg/2019/1157/oj"
    private const val BSI_TR_03116_2 = "BSI TR-03116-2, cryptographic requirements for German eID documents and eMRTDs"
    private const val FINGERPRINTING_STUDY =
        "H. Richter, W. Mostowski and E. Poll, \"Fingerprinting Passports\", NLUUG spring conference 2008: " +
            "how sampled chips answer unexpected commands. https://cs.ru.nl/E.Poll/papers/nluug.pdf"

    private const val FINGERPRINTING_URL = "https://cs.ru.nl/E.Poll/papers/nluug.pdf"
    private const val BELGIAN_STUDY_URL = "https://sites.uclouvain.be/security/download/papers/AvoineKQ-2008-fc.pdf"
    private const val SWISS_STUDY_URL = "https://www.iacr.org/conferences/eurocrypt2007/slides/rumpt05.pdf"
    private const val CANADIAN_TECH_URL = "https://www.canada.ca/en/news/archive/2014/05/technical-information-about-canadian-epassport.html"
    private const val CANADIAN_LAUNCH_URL = "https://www.canada.ca/en/news/archive/2014/05/history-epassport.html"
    private const val AUSTRALIAN_AUDIT_URL = "https://www.anao.gov.au/sites/default/files/201112%20Audit%20Report%20No%2033.pdf"
    private const val SPANISH_PACE_URL = "https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?id_menu=20&pag=REF_035"
    private const val ASSUMPTION_NOTE =
        "ASSUMED rows are emulator defaults, including disabled features, not confirmed issuer behaviour. " +
            "Evidence supports only the specific linked claims for this generation; optional files, algorithms, " +
            "validity and errors remain unknown unless explicitly sourced."
    private const val HISTORICAL_NOTE =
        "Historical study sample, not a current passport or a country-wide claim. The synthetic preset is dated " +
            "as if issued recently for reader testing; its holder and dates are not an official specimen."

    private const val SIGNER_NOTE =
        "Signed by a PassportEmu test Document Signer, with \"TEST\" in its name, not the issuer's: readers must trust " +
            "the matching test CSCA instead of the issuer's own."
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(ICAO_9303),
        notes = listOf(ASSUMPTION_NOTE, SIGNER_NOTE)
    )

    private val euPassport = ChipProfile(
        id = "eu-passport",
        title = "EU passport (current design)",
        shortTitle = "an EU passport",
        country = null,
        documentType = DocumentType.PASSPORT,
        summary = "An EU passport test configuration with BAC, PACE and protected fingerprints. Exact protocol " +
            "choices and algorithms need evidence for the issuing country and generation.",
        accessControl = AccessControl.BAC_AND_PACE,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(EU_PASSPORT_REGULATION, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(EU_ID_CARD_REGULATION, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
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
        summary = "A German ePass test configuration: BAC, PACE on Brainpool, Chip Authentication and protected " +
            "fingerprints. Exact generation algorithms remain ASSUMED. Germany's MRZ code is \"D\".",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = germanSigner,
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample,
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(BSI_TR_03116_2, BSI_TR_03110, EU_PASSPORT_REGULATION, "Passgesetz § 5 (validity)", ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            "Active Authentication is disabled in this emulator configuration; its absence is ASSUMED for this generation.",
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(
            BSI_TR_03116_2, BSI_TR_03110, EU_ID_CARD_REGULATION, "Personalausweisgesetz § 6 (validity)", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
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
        summary = "A first-generation German passport test configuration: BAC, with fingerprints and anti-copy features disabled. " +
            "Every real one expired by 2017, so the ready-made one is dated as if new.",
        accessControl = AccessControl.BAC_ONLY,
        sod = germanSigner,
        mrz = germanMrz,
        errorResponses = ErrorResponses(outOfSequence = 0x6985),
        validity = germanValidity,
        sample = germanSample,
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(FINGERPRINTING_STUDY, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            "Fingerprints were added from November 2007, so this generation has no DG3.",
            "The 2008 study reports 6985 for EXTERNAL AUTHENTICATE with Lc=40 before a challenge. Its Table 1 " +
                "value 6700 is for a different malformed probe, not this request.",
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
        summary = "A Dutch 2014 passport test configuration: BAC and PACE, assumed AA and CA algorithms, and protected " +
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(
            "Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)",
            EU_PASSPORT_REGULATION,
            ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf("Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)", ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
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
        summary = "A first-generation Dutch passport test configuration: BAC and assumed Active Authentication with a 1024-bit " +
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf(FINGERPRINTING_STUDY, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            "Active Authentication signs with ISO/IEC 9796-2 and SHA-1, the scheme RSA chips of the time used.",
            "6982 is an ASSUMED response for EXTERNAL AUTHENTICATE with Lc=40 before a challenge. The 2008 " +
                "paper does not measure this Dutch request; its 6982 entry is for INTERNAL AUTHENTICATE.",
            SIGNER_NOTE
        )
    )

    private val britishPassport = ChipProfile(
        id = "gb-passport",
        title = "United Kingdom · passport (2020 design)",
        shortTitle = "a British passport",
        country = "GBR",
        documentType = DocumentType.PASSPORT,
        summary = "A British 2020 passport test configuration: an ICAO chip with fingerprints disabled. " +
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf("HM Passport Office: 10 years' validity from age 16, 5 years below", ICAO_9303),
        notes = listOf(ASSUMPTION_NOTE, SIGNER_NOTE)
    )

    private val americanPassport = ChipProfile(
        id = "us-passport-2021",
        title = "United States · passport (Next Generation, 2021)",
        shortTitle = "a US passport",
        country = "USA",
        documentType = DocumentType.PASSPORT,
        summary = "A US Next Generation Passport test configuration: assumed AA and access control, and document numbers of a " +
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
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        sources = listOf("22 CFR 51.4 (validity: 10 years from age 16, 5 years below)", ICAO_9303),
        notes = listOf(ASSUMPTION_NOTE, SIGNER_NOTE)
    )

    /** Country-labelled test PKI. Its key is a stand-in unless the evidence explicitly names the DS key. */
    private fun countrySigner(country: String, key: KeySpec = KeySpec.Ec(EcCurve.NIST_P256), digest: String = "SHA-256") =
        SodSpec(
            digestAlgorithm = digest,
            signerKey = key,
            passportSigner = "smartemu-test-ds-${country.lowercase()}",
            cardSigner = "smartemu-test-ds-${country.lowercase()}",
            csca = "PassportEmu TEST CSCA $country"
        )

    private fun studyBacEvidence(country: String) = ProfileEvidence(
        claim = "BAC is described for the sampled $country passport; no current-generation algorithms are established.",
        sourceTitle = "Richter, Mostowski and Poll, Fingerprinting Passports (2008)",
        url = FINGERPRINTING_URL,
        locator = "Sections 1–3, pp. 1–5; Table 1 identifies the tested countries",
        aspect = ProfileAspect.ACCESS_CONTROL
    )

    private fun historicalPassport(
        id: String,
        countryName: String,
        country: String,
        pkiCountry: String,
        firstName: String,
        lastName: String,
        number: String,
        errorResponses: ErrorResponses = ErrorResponses(),
        evidence: List<ProfileEvidence> = listOf(studyBacEvidence(countryName)),
        provenance: Map<ProfileAspect, Provenance> = emptyMap()
    ) = ChipProfile(
        id = id,
        title = "$countryName · passport (2008 study sample)",
        shortTitle = "a historical $countryName passport test chip",
        country = country,
        documentType = DocumentType.PASSPORT,
        summary = "BAC-era study sample. BAC is sourced; AA, EAC, DS algorithms and optional files are ASSUMED " +
            "emulator choices. This profile does not describe current passports.",
        accessControl = AccessControl.BAC_ONLY,
        sod = countrySigner(pkiCountry),
        sample = ProfileSample(firstName, lastName, "F", LocalDate(1985, 6, 15), number),
        errorResponses = errorResponses,
        provenance = ProfileAspect.entries.associateWith { provenance[it] ?: Provenance.ASSUMED },
        evidence = evidence,
        sources = evidence.map { "${it.sourceTitle} — ${it.url}" }.distinct(),
        notes = listOf(ASSUMPTION_NOTE, HISTORICAL_NOTE, SIGNER_NOTE)
    )

    private val frenchPassport2008 = historicalPassport(
        "fr-passport-2008-study", "France", "FRA", "FR", "Camille", "Exemple", "08FR12345"
    )

    private val italianPassport2008 = historicalPassport(
        "it-passport-2008-study", "Italy", "ITA", "IT", "Giulia", "Esempio", "IT1234567"
    )

    private val spanishPassport2008 = historicalPassport(
        "es-passport-2008-study", "Spain", "ESP", "ES", "Lucia", "Ejemplo", "ESP123456",
        errorResponses = ErrorResponses(outOfSequence = 0x6300),
        provenance = mapOf(ProfileAspect.ERROR_RESPONSES to Provenance.REPORTED),
        evidence = listOf(
            studyBacEvidence("Spanish"),
            ProfileEvidence(
                "The sampled Spanish chip returns 6300 for EXTERNAL AUTHENTICATE with Lc=40 before BAC.",
                "Richter, Mostowski and Poll, Fingerprinting Passports (2008)", FINGERPRINTING_URL,
                "Section 3, p. 5, paragraph below Table 1 (not the table's malformed 6700 probe)",
                ProfileAspect.ERROR_RESPONSES
            )
        )
    )

    private val belgianPassport2006 = ChipProfile(
        id = "be-passport-2006-study",
        title = "Belgium · passport (mid-2006 generation, 2007 study)",
        shortTitle = "a second-generation Belgian passport test chip",
        country = "BEL",
        documentType = DocumentType.PASSPORT,
        summary = "The BAC generation studied by Avoine, Kalach and Quisquater: RSA-1024 AA and RSA-2048 DS " +
            "are reported separately. Optional DG7 and exact signature encoding are not reproduced.",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Rsa(1024),
        dataGroups = setOf(11, 12),
        sod = countrySigner("BE", KeySpec.Rsa(2048), "SHA-1"),
        validity = Validity(adultYears = 5, childYears = 5),
        sample = ProfileSample("Elise", "Exemple", "F", LocalDate(1985, 6, 15), "EG123456"),
        provenance = ProfileAspect.entries.associateWith {
            if (it == ProfileAspect.ACTIVE_AUTHENTICATION) Provenance.REPORTED else Provenance.ASSUMED
        },
        evidence = listOf(
            ProfileEvidence(
                "Second-generation Belgian ePassports began in mid-2006 and implemented BAC.",
                "Avoine, Kalach and Quisquater, ePassport: Securing International Contacts with Contactless Chips (2008)",
                BELGIAN_STUDY_URL, "Section 3.3, PDF p. 10", ProfileAspect.ACCESS_CONTROL
            ),
            ProfileEvidence(
                "The studied Belgian passports used RSA-1024 for AA; RSA-2048 for DS is a separate key.",
                "Avoine, Kalach and Quisquater (2008)", BELGIAN_STUDY_URL,
                "Table 2, PDF p. 14, Belgian ePassport column", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            ProfileEvidence(
                "Table 2 reports RSA-2048 DS and SHA-1 signature hashing; it does not identify RSA padding or LDS hash separately.",
                "Avoine, Kalach and Quisquater (2008)", BELGIAN_STUDY_URL,
                "Table 2, PDF p. 14 (not Table 3's recommendations)", ProfileAspect.SIGNATURE
            ),
            ProfileEvidence(
                "The study describes DG7, DG11 and DG12 in addition to DG1 and DG2.",
                "Avoine, Kalach and Quisquater (2008)", BELGIAN_STUDY_URL,
                "Section 4, PDF p. 11", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf("Avoine, Kalach and Quisquater (2008) — $BELGIAN_STUDY_URL", ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE, HISTORICAL_NOTE,
            "The pre-mid-2006 generation had no BAC and is not represented by this profile.",
            "DG7 is omitted because PassportEmu does not generate it. RSA PKCS#1 v1.5 SOD padding and the SHA-1 " +
                "LDS hash are ASSUMED; the paper reports a signature hash but does not resolve those choices.",
            SIGNER_NOTE
        )
    )

    private val swissPassport2007 = ChipProfile(
        id = "ch-passport-2007-study",
        title = "Switzerland · passport (Eurocrypt 2007 study sample)",
        shortTitle = "a historical Swiss passport test chip",
        country = "CHE",
        documentType = DocumentType.PASSPORT,
        summary = "The Swiss chip sampled at Eurocrypt 2007: BAC was implemented and AA was absent. This " +
            "limited snapshot does not establish the behaviour of later Swiss passports.",
        accessControl = AccessControl.BAC_ONLY,
        sod = countrySigner("CH"),
        sample = ProfileSample("Anna", "Muster", "F", LocalDate(1985, 6, 15), "CH1234567"),
        provenance = ProfileAspect.entries.associateWith {
            if (it == ProfileAspect.ACTIVE_AUTHENTICATION) Provenance.REPORTED else Provenance.ASSUMED
        },
        evidence = listOf(
            ProfileEvidence(
                "The survey examined one passport each from Switzerland, the UK and France.",
                "Vaudenay and Vuagnoux, E-Passport Survey (Eurocrypt 2007)", SWISS_STUDY_URL, "Slide 7 of 13"
            ),
            ProfileEvidence(
                "BAC was implemented on the Swiss study sample.",
                "Vaudenay and Vuagnoux (2007)", SWISS_STUDY_URL, "Slide 10 of 13", ProfileAspect.ACCESS_CONTROL
            ),
            ProfileEvidence(
                "AA was absent on the Swiss study sample.",
                "Vaudenay and Vuagnoux (2007)", SWISS_STUDY_URL, "Slide 12 of 13", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf("Vaudenay and Vuagnoux (2007) — $SWISS_STUDY_URL", ICAO_9303),
        notes = listOf(ASSUMPTION_NOTE, HISTORICAL_NOTE, SIGNER_NOTE)
    )

    private val canadianPassport2013 = ChipProfile(
        id = "ca-passport-2013",
        title = "Canada · passport (2013 ePassport generation)",
        shortTitle = "a Canadian 2013-generation passport test chip",
        country = "CAN",
        documentType = DocumentType.PASSPORT,
        summary = "Canada's issuer documents BAC and AA for this generation. The AA key type is ASSUMED. " +
            "RSA-PSS-2048/SHA-256 are signature examples; the emulator uses an ASSUMED PKCS#1 v1.5 stand-in.",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        sod = countrySigner("CA", KeySpec.Rsa(2048)),
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample("Alex", "Example", "F", LocalDate(1985, 6, 15), "CA1234567"),
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        evidence = listOf(
            ProfileEvidence(
                "The 10-year ePassport became available to Canadian adults on 1 July 2013.",
                "Government of Canada, History of the ePassport (2014)", CANADIAN_LAUNCH_URL,
                "The ePassport project and the User Fees Act, final paragraph"
            ),
            ProfileEvidence(
                "The issuer documents BAC protecting personal information on the 2013-generation chip.",
                "Government of Canada, Technical information about the Canadian ePassport (2014)", CANADIAN_TECH_URL,
                "Unauthorized reading: Basic access control", ProfileAspect.ACCESS_CONTROL
            ),
            ProfileEvidence(
                "The issuer gives RSA-PSS-2048 and SHA-256 as passive-authentication examples, not an exhaustive algorithm list.",
                "Government of Canada (2014)", CANADIAN_TECH_URL,
                "Tampering and inauthenticity: Passive authentication", ProfileAspect.SIGNATURE
            ),
            ProfileEvidence(
                "The Canadian technical backgrounder states that AA is implemented; it does not specify the AA key type.",
                "Government of Canada (2014)", CANADIAN_TECH_URL,
                "Cloning: Active authentication, second paragraph", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf("Government of Canada (2014) — $CANADIAN_TECH_URL", "Generation — $CANADIAN_LAUNCH_URL"),
        notes = listOf(
            ASSUMPTION_NOTE,
            "BAC-only and CA disabled are emulator choices; the issuer page does not establish absence of other " +
                "protocols. AA is enabled with ASSUMED ECDSA P-256; the DS example does not identify its AA key. " +
                "RSA-PSS is not supported by this SOD generator; the test SOD uses SHA256withRSA (PKCS#1 v1.5).",
            "This profile does not describe Canada's redesigned 2023 passport. The preset chooses 10 years; " +
                "other adult validity options are not modelled.",
            SIGNER_NOTE
        )
    )

    private fun australianPassport(id: String, series: String, year: Int, aa: Boolean) = ChipProfile(
        id = id,
        title = "Australia · passport ($series Series, $year)",
        shortTitle = "an Australian $series Series passport test chip",
        country = "AUS",
        documentType = DocumentType.PASSPORT,
        summary = if (aa) {
            "The ANAO audit documents BAC and the addition of AA to the N Series in 2009. AA key type, curves " +
                "and DS algorithms remain ASSUMED emulator choices."
        } else {
            "The ANAO audit documents BAC on the M Series introduced in 2005, and explicitly states that " +
                "this generation had no AA. Other algorithms and optional files remain ASSUMED."
        },
        accessControl = AccessControl.BAC_ONLY,
        // The audit establishes AA presence, not its key type; P-256 is an explicitly assumed emulator default.
        activeAuthentication = if (aa) KeySpec.Ec(EcCurve.NIST_P256) else null,
        sod = countrySigner("AU"),
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample("Jane", "Example", "F", LocalDate(1985, 6, 15), "${series}12345678"),
        provenance = ProfileAspect.entries.associateWith {
            if (!aa && it == ProfileAspect.ACTIVE_AUTHENTICATION) Provenance.REPORTED else Provenance.ASSUMED
        },
        evidence = listOf(
            ProfileEvidence(
                "The M Series began on 24 October 2005; the N Series replaced it in May 2009.",
                "Australian National Audit Office, Management of ePassports (2012)", AUSTRALIAN_AUDIT_URL,
                "Summary paragraph 6, p. 14"
            ),
            ProfileEvidence(
                "BAC was included in each Australian ePassport version covered by the 2012 audit.",
                "Australian National Audit Office (2012)", AUSTRALIAN_AUDIT_URL,
                "Paragraph 5.6, p. 74", ProfileAspect.ACCESS_CONTROL
            ),
            ProfileEvidence(
                if (aa) "AA was introduced in 2009 for the N Series; the audit does not specify its key type." else
                    "M Series ePassports issued before 2009 did not have AA.",
                "Australian National Audit Office (2012)", AUSTRALIAN_AUDIT_URL,
                "Paragraph 5.16, p. 78", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf("Australian National Audit Office (2012) — $AUSTRALIAN_AUDIT_URL"),
        notes = listOf(
            ASSUMPTION_NOTE, HISTORICAL_NOTE,
            "These audit findings apply to M/N Series, not today's R Series. BAC-only, CA disabled, " +
                "optional files and DS algorithms are emulator choices; no algorithms are inferred from CSCA keys.",
            SIGNER_NOTE
        )
    )

    private val australianPassport2005 = australianPassport("au-passport-2005-m", "M", 2005, aa = false)
    private val australianPassport2009 = australianPassport("au-passport-2009-n", "N", 2009, aa = true)

    private val spanishPassportThirdGeneration = ChipProfile(
        id = "es-passport-third-generation",
        title = "Spain · passport (third electronic generation)",
        shortTitle = "a third-generation Spanish passport test chip",
        country = "ESP",
        documentType = DocumentType.PASSPORT,
        summary = "The Spanish police's official reader documentation explicitly supports third-generation " +
            "passports using PACE. BAC fallback, mapping, curves, AA and EAC algorithms remain ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = countrySigner("ES"),
        sample = ProfileSample("Lucia", "Ejemplo", "F", LocalDate(1985, 6, 15), "ESP654321"),
        provenance = ProfileAspect.entries.associateWith { Provenance.ASSUMED },
        evidence = listOf(
            ProfileEvidence(
                "The official example reader supports Spanish third-generation electronic passports over a PACE secure channel.",
                "Spanish Policía Nacional, DNIe portal, Cuáles son", SPANISH_PACE_URL,
                "Ejemplo DNIe Lectura Datos", ProfileAspect.ACCESS_CONTROL
            )
        ),
        sources = listOf("Policía Nacional — $SPANISH_PACE_URL", ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            "The page identifies a generation but gives no start date or passport-specific PACE suite. DNIe card " +
                "algorithms and vendor security-target capabilities are not evidence of passport personalisation.",
            "CA/TA and DG3 are EU-style emulator defaults here; the linked reader page does not verify them.",
            FINGERPRINT_NOTE, SIGNER_NOTE
        )
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
        americanPassport,
        frenchPassport2008,
        belgianPassport2006,
        spanishPassport2008,
        italianPassport2008,
        swissPassport2007,
        canadianPassport2013,
        australianPassport2005,
        australianPassport2009,
        spanishPassportThirdGeneration
    )

    val default: ChipProfile get() = generic

    /** The profile with the given id, or the generic one for an id no profile has, as one saved by an older version. */
    fun byId(id: String): ChipProfile = all.find { it.id == id } ?: generic

    /** The profiles that fit a document of this type, generic first. */
    fun forDocumentType(type: DocumentType): List<ChipProfile> = all.filter { it.fits(type) }
}
