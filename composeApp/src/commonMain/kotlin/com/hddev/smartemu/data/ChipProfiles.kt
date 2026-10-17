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

    private const val GMRTD_README_URL = "https://github.com/gmrtd/gmrtd/blob/main/README.md"
    private const val GMRTD_SOD_TEST_URL = "https://github.com/gmrtd/gmrtd/blob/main/document/sod_test.go"
    private const val GMRTD_PASSIVE_AUTH_TEST_URL = "https://github.com/gmrtd/gmrtd/blob/main/passiveauth/passive_auth_test.go"
    private const val BSI_TR_03116_2_URL =
        "https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03116/BSI-TR-03116-2.pdf?__blob=publicationFile&v=8"
    private const val RVIG_2014_URL =
        "https://www.rvig.nl/sites/default/files/2023-02/Kenmerkenbrochure%20paspoorten%20en%20identiteitskaarten%20model%202014%20NL.pdf"
    private const val RVIG_2024_URL =
        "https://www.rvig.nl/sites/default/files/2024-12/Kenmerkenbrochure%20Nederlands%20paspoort%20model%202024%20-%20EN.pdf"
    private const val RIJKSOVERHEID_2024_URL = "https://www.rijksoverheid.nl/actueel/nieuws/2024/09/30/nieuw-model-paspoort-en-id-kaart"
    private const val SECURITY_NL_URL =
        "https://www.security.nl/posting/756341/Chip+op+paspoort+en+id-kaart+straks+alleen+uit+te+lezen+via+veiliger+protocol"
    private const val GMRTD_NOTE =
        "The gmrtd Sample Documents table lists one real document per row and one mechanism per cell: silence in it " +
            "(for example about Active Authentication) is not evidence of absence. Parsed SODs are single samples."
    private const val AA_ABSENCE_NOTE =
        "Active Authentication is disabled here as an ASSUMED choice: the only basis is that one sampled SOD lacks " +
            "DG15, and the README row does not list it either way. That does not show the chip has no Active " +
            "Authentication key."
    private const val ASSUMPTION_NOTE =
        "ASSUMED rows are emulator defaults, including disabled features, not confirmed issuer behaviour. " +
            "Evidence supports only the specific linked claims for this generation; optional files, algorithms, " +
            "validity and errors remain unknown unless explicitly sourced."

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
                "own profile is more precise where there is one (for example Germany, the Netherlands and the United " +
                "Kingdom).",
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
        summary = "A German ePass test configuration: BAC, PACE-GM on Brainpool, Chip Authentication, protected " +
            "fingerprints and no Active Authentication. BSI TR-03116-2 specifies the PACE and signer algorithms; " +
            "the Chip Authentication cipher and whether this generation also accepts BAC are ASSUMED. Germany's " +
            "MRZ code is \"D\".",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = germanSigner,
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample,
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.SPECIFICATION,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.SPECIFICATION,
            ProfileAspect.SIGNATURE to Provenance.SPECIFICATION
        ),
        evidence = listOf(
            bsiSpecification(
                "German passports use Brainpool curves (RFC 5639) and PACE with ECKA 256, Generic Mapping and AES-CBC " +
                    "128 with CMAC 128; PACE-GM has been used since 2013.",
                "Section 1.4.2, p. 9; Table 15 in section 3.2, p. 15; section 6.1.1, p. 20", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            bsiSpecification(
                "German passports use Chip Authentication v1 and Terminal Authentication v1 (section 6.1.1), and " +
                    "Table 16 lists CA v1 with ECKA 256 and AES-128 as a national application. Table 16 is only " +
                    "\"Nationale Anwendung\", and section 4.2.1 / Table 19 say the EU additionally required 3DES for " +
                    "Chip Authentication v1 on passports until 2019, so AES-128 CA on a 2017 passport is not " +
                    "uniquely specified.",
                "Table 16, p. 17; section 4.2.1 and Table 19; section 6.1.1, p. 20", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            bsiSpecification(
                "A German passport has no Active Authentication.",
                "Section 6.1.2, p. 20", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            bsiSpecification(
                "Documents issued 2010 to 2019 have a Document Signer with ECDSA-256 and SHA-256 (the switch to " +
                    "ECDSA-384/SHA-384 came with the CSCA key length change, Table 5 footnote 4).",
                "Table 5 in section 2.1.2, p. 11", ProfileAspect.SIGNATURE
            )
        ),
        sources = listOf(
            "$BSI_TR_03116_2 (2025) — $BSI_TR_03116_2_URL", BSI_TR_03110, EU_PASSPORT_REGULATION,
            "Passgesetz § 5 (validity)", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            "BSI TR-03116-2 is a specification for German documents, not a measurement of a chip. It states that, " +
                "since July 2021, new documents must not support BAC; whether a 2017 passport accepts BAC as well as " +
                "PACE is not stated there, so BAC fallback stays ASSUMED. The 2023 design is `de-passport-2023`.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val germanPassport2023 = ChipProfile(
        id = "de-passport-2023",
        title = "Germany · passport (2023 design)",
        shortTitle = "a German passport (2023 design)",
        country = "DEU",
        documentType = DocumentType.PASSPORT,
        summary = "A German passport of the 2023 design: PACE only (no BAC), PACE-CAM on brainpoolP256r1, Chip " +
            "Authentication, protected fingerprints, no Active Authentication, and an EF.SOD signed with " +
            "ECDSA brainpoolP384r1 and SHA-384, as a real 2023 passport's is.",
        accessControl = AccessControl.PACE_ONLY,
        paceMapping = PaceMapping.CHIP_AUTHENTICATION,
        otherPaceMappings = listOf(PaceMapping.GENERIC),
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = SodSpec(
            digestAlgorithm = "SHA-384",
            signerKey = KeySpec.Ec(EcCurve.BRAINPOOL_P384R1),
            passportSigner = "smartemu-test-ds-de-p384",
            cardSigner = "smartemu-test-ds-de-p384",
            csca = "SmartEmu Test CSCA DE"
        ),
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample,
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.SPECIFICATION,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            bsiSpecification(
                "Since July 2021 new German passports must not support Basic Access Control (\"Seit Juli 2021 darf " +
                    "Basic Access Control bei neuen Dokumenten nicht mehr unterstützt werden\"); PACE-CAM is used " +
                    "since July 2021.",
                "Section 6.1.1, p. 20", ProfileAspect.ACCESS_CONTROL
            ),
            bsiSpecification(
                "A German passport has no Active Authentication.",
                "Section 6.1.2, p. 20", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            bsiSpecification(
                "Documents from 2019 have a Document Signer with ECDSA-384 and SHA-384.",
                "Table 5 in section 2.1.2, p. 11", ProfileAspect.SIGNATURE
            ),
            bsiSpecification(
                "PACE uses ECKA 256 with GM and CAM (CAM since 2019) and AES-CBC 128 with CMAC 128.",
                "Table 15 in section 3.2, p. 15", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdParsedSod(
                "The EF.CardSecurity of a real German passport (2023) advertises PACE-ECDH-GM-AES-CBC-CMAC-128 and " +
                    "PACE-ECDH-CAM-AES-CBC-CMAC-128, both on parameterId 13 (brainpoolP256r1), and CA-ECDH-AES-CBC-CMAC-128.",
                "Parsed German P 2023 EF.CardSecurity test vector", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdParsedSod(
                "The EF.SOD of that passport has a Document Signer on EC brainpoolP384r1, signature ecdsa-SHA384, " +
                    "digest SHA-384, hashes data groups 1, 2, 3 and 14, and LDS version 0108 / Unicode 040000.",
                "TestNewSod and TestPassiveAuth, German P 2023 vector", ProfileAspect.SIGNATURE
            )
        ),
        sources = listOf(
            "$BSI_TR_03116_2 (2025) — $BSI_TR_03116_2_URL",
            "gmrtd, parsed real SODs — $GMRTD_SOD_TEST_URL and $GMRTD_PASSIVE_AUTH_TEST_URL",
            BSI_TR_03110, EU_PASSPORT_REGULATION, "Passgesetz § 5 (validity)", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            "Like the real chip, EF.CardAccess offers both PACE-GM and PACE-CAM.",
            "The signer is a PassportEmu test Document Signer on brainpoolP384r1, chained to the German test CSCA.",
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
            "printed on the front, PACE-CAM on Brainpool curves, Chip Authentication and protected fingerprints.",
        accessControl = AccessControl.PACE_ONLY,
        paceMapping = PaceMapping.CHIP_AUTHENTICATION,
        otherPaceMappings = listOf(PaceMapping.GENERIC),
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = germanSigner,
        mrz = germanMrz,
        validity = germanValidity,
        sample = germanSample.copy(documentNumber = "L01X00T47"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.SPECIFICATION,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.SPECIFICATION
        ),
        evidence = listOf(
            bsiSpecification(
                "The ID card's chip offers PACE for all applications and no BAC.",
                "Sections 6.2.1 and 6.2.2, pp. 20-21", ProfileAspect.ACCESS_CONTROL
            ),
            bsiSpecification(
                "The ID card's ePass application also offers PACE-CAM since 2 August 2021, besides PACE-GM; it uses " +
                    "EAC version 2.",
                "Section 6.2.2, p. 21", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            bsiSpecification(
                "The ID card has no Active Authentication.",
                "Sections 6.2.1 and 6.2.2, pp. 20-21", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf(
            "$BSI_TR_03116_2 (2025) — $BSI_TR_03116_2_URL", BSI_TR_03110, EU_ID_CARD_REGULATION,
            "Personalausweisgesetz § 6 (validity)", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            "EF.CardAccess offers PACE-GM and PACE-CAM, as the real chip does. " +
                "The cipher and curve, the Document Signer's key (SHA-384 is probable, unconfirmed) and the data " +
                "groups are not established for the ID card and stay ASSUMED.",
            "Only the travel document application is emulated, not the eID application for online identification.",
            "Fingerprints are mandatory on cards issued from August 2021.",
            FINGERPRINT_NOTE,
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
        summary = "A Dutch 2014 passport test configuration: BAC, PACE, EAC, Active Authentication and passive " +
            "authentication, as the issuer lists them; the curves, ciphers and AA key type are ASSUMED. The citizen " +
            "service number (BSN) is in the MRZ.",
        accessControl = AccessControl.BAC_AND_PACE,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        sod = dutchSigner,
        mrz = dutchMrz,
        validity = dutchValidity,
        sample = dutchSample,
        provenance = provenanceWith(ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED),
        evidence = listOf(
            ProfileEvidence(
                "The 2014 model passport's chip offers BAC, PACE, EAC (Chip and Terminal Authentication), Active " +
                    "Authentication and Passive Authentication. The list gives no curves, ciphers or key types.",
                "RvIG, Kenmerkenbrochure paspoorten en identiteitskaarten model 2014", RVIG_2014_URL,
                "Section \"Chip\", p. 7", ProfileAspect.ACCESS_CONTROL
            )
        ),
        sources = listOf(
            "RvIG, Kenmerkenbrochure model 2014 — $RVIG_2014_URL",
            "Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)",
            EU_PASSPORT_REGULATION,
            ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            "The issuer lists the mechanisms only: the PACE and CA curves and ciphers, the AA key type, the optional " +
                "data groups and the Document Signer's algorithm are ASSUMED. The 2024 design is `nl-passport-2024`.",
            "Press reports (Security.nl, secondary) say the BAC fallback ended in July 2022 for passports, so a " +
                "current reader of a 2014 passport may only be able to use PACE.",
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
            "after the document number. The issuer lists BAC, PACE, Chip Authentication, AA and PA for the 2014 model.",
        accessControl = AccessControl.BAC_AND_PACE,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_128),
        dataGroups = setOf(11, 12),
        sod = dutchSigner,
        mrz = dutchMrz.copy(documentCode = "I"),
        validity = dutchValidity,
        sample = dutchSample,
        provenance = provenanceWith(ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED),
        evidence = listOf(
            ProfileEvidence(
                "The 2014 model ID card's chip offers BAC, PACE, EAC (Chip Authentication only), Active " +
                    "Authentication and Passive Authentication. The list gives no curves, ciphers or key types.",
                "RvIG, Kenmerkenbrochure paspoorten en identiteitskaarten model 2014", RVIG_2014_URL,
                "Section \"Chip\", p. 7", ProfileAspect.ACCESS_CONTROL
            )
        ),
        sources = listOf(
            "RvIG, Kenmerkenbrochure model 2014 — $RVIG_2014_URL",
            "Rijksdienst voor Identiteitsgegevens specimen documents (SPECI2014, BSN 999999990)",
            ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            "The issuer lists the mechanisms only: curves, ciphers, the AA key type, the optional data groups and " +
                "the Document Signer's algorithm are ASSUMED.",
            "Press reports (Security.nl, secondary) say the BAC fallback ended on 29 August 2022 for ID cards.",
            "Cards of this design carry no fingerprints; those issued from August 2021 do, and no longer the BSN.",
            SIGNER_NOTE
        )
    )

    private val dutchPassport2024 = ChipProfile(
        id = "nl-passport-2024",
        title = "Netherlands · passport (2024 design)",
        shortTitle = "a Dutch passport (2024 design)",
        country = "NLD",
        documentType = DocumentType.PASSPORT,
        summary = "A Dutch passport of the 2024 design: PACE only (no BAC), PACE-GM with AES-256 on brainpoolP320r1, " +
            "Chip Authentication with AES-256 on brainpoolP512r1, Terminal Authentication and Active Authentication. " +
            "The AA key type and the Document Signer's algorithm are ASSUMED stand-ins.",
        accessControl = AccessControl.PACE_ONLY,
        activeAuthentication = KeySpec.Ec(EcCurve.NIST_P384),
        pace = PaceSpec(EcCurve.BRAINPOOL_P320R1, SessionCipher.AES_256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P512R1, SessionCipher.AES_256),
        terminalAuthentication = true,
        sod = dutchSigner,
        mrz = dutchMrz,
        validity = dutchValidity,
        sample = dutchSample.copy(documentNumber = "SPECI2024"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED
        ),
        evidence = listOf(
            ProfileEvidence(
                "The 2024 model passport's chip offers PACE, EAC (Chip and Terminal Authentication), Active " +
                    "Authentication and Passive Authentication; BAC is not in the issuer's list. The issuer says the " +
                    "AA and CA key length \"has been increased\" without giving the key type.",
                "RvIG, Characteristics brochure Dutch passport model 2024", RVIG_2024_URL,
                "\"Chip\" box, p. 12", ProfileAspect.ACCESS_CONTROL
            ),
            ProfileEvidence(
                "The new passport model was introduced on 30 September 2024.",
                "Rijksoverheid, nieuw model paspoort en ID-kaart (30 Sep 2024)", RIJKSOVERHEID_2024_URL,
                "News item of 30 September 2024"
            ),
            gmrtdSample(
                "A real Dutch passport (NL PP 2025) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP320r1 " +
                    "(parameter id 14, BSI TR-03110 part 3 Table 4).",
                "NL PP 2025", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That passport's Chip Authentication is CA-ECDH with AES-256 on brainpoolP512r1 (parameter id 17, " +
                    "BSI TR-03110 part 3 Table 4).",
                "NL PP 2025", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            ProfileEvidence(
                "BAC fallback was removed for passports from July 2022 (secondary press report).",
                "Security.nl, Chip op paspoort en id-kaart straks alleen uit te lezen via veiliger protocol",
                SECURITY_NL_URL, "Article text", ProfileAspect.ACCESS_CONTROL
            )
        ),
        sources = listOf(
            "RvIG, Characteristics brochure Dutch passport model 2024 — $RVIG_2024_URL",
            "Rijksoverheid, 30 Sep 2024 — $RIJKSOVERHEID_2024_URL",
            "gmrtd, Sample Documents — $GMRTD_README_URL",
            BSI_TR_03110, ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "Active Authentication is on because the issuer lists it, but its key type is UNKNOWN: the issuer says " +
                "the AA and CA key length has been increased. ECDSA P-384 is an ASSUMED stand-in. " +
                "The gmrtd README lists one mechanism per row, so its silence about AA is not a claim of absence.",
            "The Document Signer's algorithm for this design is UNKNOWN: the Dutch test signer is an ASSUMED " +
                "stand-in. The optional data groups are not established either.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val britishPassport = ChipProfile(
        id = "gb-passport",
        title = "United Kingdom · passport (2020 design)",
        shortTitle = "a British passport",
        country = "GBR",
        documentType = DocumentType.PASSPORT,
        summary = "A British 2020 passport test configuration, from a real passport's sampled chip: PACE-GM with " +
            "AES-256 and Chip Authentication with AES-256, both on NIST P-256, an EF.SOD signed with ECDSA P-256 " +
            "and SHA-256. Active Authentication and fingerprints are not enabled (ASSUMED: one sampled SOD lacks DG15 and " +
            "DG3). Whether BAC is accepted as well is ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.NIST_P256, SessionCipher.AES_256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.AES_256),
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
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real UK passport (UK P 2021) uses PACE-GM with AES-CBC-CMAC-256 on secp256r1 and Chip " +
                    "Authentication (CA-ECDH) with AES-256 on secp256r1; extended length Yes; LDS 0108.",
                "UK P 2021", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdParsedSod(
                "The EF.SOD of that passport has a Document Signer on EC P-256, signature ecdsa-SHA256, digest " +
                    "SHA-256, and hashes data groups 1, 2 and 14 only: no DG3 (fingerprints), DG11, DG12 or DG15.",
                "TestNewSod and TestPassiveAuth, UK P 2021 vector", ProfileAspect.SIGNATURE
            ),
            gmrtdParsedSod(
                "Its sampled SOD lacks DG15 among the hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, UK P 2021 vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            gmrtdParsedSod(
                "The hashed data groups are 1, 2 and 14.",
                "TestNewSod, UK P 2021 vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(
            "gmrtd, Sample Documents — $GMRTD_README_URL",
            "gmrtd, parsed real SODs — $GMRTD_SOD_TEST_URL and $GMRTD_PASSIVE_AUTH_TEST_URL",
            "HM Passport Office: 10 years' validity from age 16, 5 years below", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The sample does not show whether BAC is also accepted, so BAC alongside PACE is ASSUMED. Terminal " +
                "Authentication is disabled: the sample's EF.SOD has no DG3, but nothing else is known about EAC.",
            AA_ABSENCE_NOTE,
            SIGNER_NOTE
        )
    )

    private val americanPassport = ChipProfile(
        id = "us-passport-2021",
        title = "United States · passport (Next Generation, 2021)",
        shortTitle = "a US passport",
        country = "USA",
        documentType = DocumentType.PASSPORT,
        summary = "A US Next Generation Passport test configuration, from one real passport's sampled chip: BAC only, " +
            "no Chip or Active Authentication, DG11 and DG12, and an EF.SOD signed with RSA-2048 and SHA-256. " +
            "Document numbers are a letter and eight digits.",
        accessControl = AccessControl.BAC_ONLY,
        dataGroups = setOf(11, 12),
        sod = SodSpec(
            digestAlgorithm = "SHA-256",
            signerKey = KeySpec.Rsa(2048),
            passportSigner = "smartemu-test-ds-us-rsa",
            cardSigner = "smartemu-test-ds-us-rsa",
            csca = "SmartEmu Test CSCA US"
        ),
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
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED,
            ProfileAspect.EXTENDED_ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real US passport (US P 2021) offers BAC only (PACE \"n/a (BAC)\"); it is listed \"Cloneable\", " +
                    "with no Chip Authentication and no Active Authentication; extended length No; LDS 0107.",
                "US P 2021", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "The same row lists no Active Authentication (\"Cloneable\").",
                "US P 2021", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            gmrtdSample(
                "The same row lists no Chip Authentication (\"Cloneable\"); the parsed SOD hashes no DG14.",
                "US P 2021", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            gmrtdParsedSod(
                "The EF.SOD of that passport has a Document Signer with an RSA-2048 key, signature rsaEncryption " +
                    "(PKCS#1 v1.5) and digest SHA-256, and hashes data groups 1, 2, 11 and 12. This is one sample.",
                "TestNewSod and TestPassiveAuth, US P 2021 vector", ProfileAspect.SIGNATURE
            ),
            gmrtdParsedSod(
                "The hashed data groups are 1, 2, 11 and 12.",
                "TestNewSod, US P 2021 vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(
            "gmrtd, Sample Documents — $GMRTD_README_URL",
            "gmrtd, parsed real SODs — $GMRTD_SOD_TEST_URL and $GMRTD_PASSIVE_AUTH_TEST_URL",
            "22 CFR 51.4 (validity: 10 years from age 16, 5 years below)", ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "No issuer source was found: the State Department's pages (travel.state.gov) returned 403. The access " +
                "control, the missing CA and AA and the signature all come from one sampled passport, and a " +
                "different US passport may have a different Document Signer key.",
            SIGNER_NOTE
        )
    )

    private fun provenanceWith(vararg overrides: Pair<ProfileAspect, Provenance>): Map<ProfileAspect, Provenance> {
        val chosen = overrides.toMap()
        return ProfileAspect.entries.associateWith { chosen[it] ?: Provenance.ASSUMED }
    }

    /** A claim from the gmrtd README "Sample Documents" table: observed on a real document, one sample per row. */
    private fun gmrtdSample(claim: String, row: String, aspect: ProfileAspect?) = ProfileEvidence(
        claim = claim,
        sourceTitle = "gmrtd, README, Sample Documents table",
        url = GMRTD_README_URL,
        locator = "Sample Documents table, row \"$row\"",
        aspect = aspect,
        observedOnRealChip = true
    )

    /** A fact read from a real EF.SOD parsed in gmrtd's tests: it holds for that one sample. */
    private fun gmrtdParsedSod(claim: String, locator: String, aspect: ProfileAspect?) = ProfileEvidence(
        claim = claim,
        sourceTitle = "gmrtd, parsed real EF.SOD test vectors",
        url = GMRTD_SOD_TEST_URL,
        locator = locator,
        aspect = aspect
    )

    private fun bsiSpecification(claim: String, locator: String, aspect: ProfileAspect?) = ProfileEvidence(
        claim = claim,
        sourceTitle = "BSI TR-03116-2 (2025), cryptographic requirements for German eMRTDs",
        url = BSI_TR_03116_2_URL,
        locator = locator,
        aspect = aspect,
        provenance = Provenance.SPECIFICATION
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

    private val canadianPassport2023 = ChipProfile(
        id = "ca-passport-2023",
        title = "Canada · passport (2023 design)",
        shortTitle = "a Canadian passport (2023 design)",
        country = "CAN",
        documentType = DocumentType.PASSPORT,
        summary = "A Canadian passport of the 2023 design, from one real passport's sampled chip: PACE-GM with AES-128 " +
            "and Chip Authentication with AES-128, both on NIST P-384. Whether BAC is also accepted, and Active " +
            "Authentication and Terminal Authentication, are not known and stay ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.NIST_P384, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P384, SessionCipher.AES_128),
        sod = countrySigner("CA", KeySpec.Rsa(2048)),
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample("Alex", "Example", "F", LocalDate(1985, 6, 15), "CA2345678"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real Canadian passport (CA PP 2023) uses PACE-GM with AES-CBC-CMAC-128 on secp384r1 and Chip " +
                    "Authentication (CA-ECDH) with AES-128 on secp384r1; extended length No; LDS 0108.",
                "CA PP 2023", ProfileAspect.PACE_CRYPTOGRAPHY
            )
        ),
        sources = listOf("gmrtd, Sample Documents — $GMRTD_README_URL", ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "No EF.SOD sample was found: the Document Signer is the Canadian test signer (RSA-2048, an ASSUMED " +
                "stand-in). Whether BAC is accepted alongside PACE, Active Authentication (the table does not list " +
                "it: unknown, not absent), Terminal Authentication and the data groups are ASSUMED.",
            SIGNER_NOTE
        )
    )

    private val australianRsaSigner = SodSpec(
        digestAlgorithm = "SHA-256",
        signerKey = KeySpec.Rsa(2048),
        passportSigner = "smartemu-test-ds-au-rsa",
        cardSigner = "smartemu-test-ds-au-rsa",
        csca = "PassportEmu TEST CSCA AU"
    )

    private val australianPassport2016 = ChipProfile(
        id = "au-passport-2016-p",
        title = "Australia · passport (P Series, 2016)",
        shortTitle = "an Australian P Series passport test chip",
        country = "AUS",
        documentType = DocumentType.PASSPORT,
        summary = "An Australian P Series passport test configuration, from one real passport's sampled chip: BAC " +
            "only, Active Authentication with an RSA key (size ASSUMED), no Chip Authentication, and an EF.SOD " +
            "signed with RSA-2048 and SHA-256.",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Rsa(2048),
        sod = australianRsaSigner,
        validity = Validity(adultYears = 10, childYears = 5, adultFromAge = 16),
        sample = ProfileSample("Jane", "Example", "F", LocalDate(1985, 6, 15), "PA2345678"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Australian passport (AU P 2016) offers BAC and Active Authentication with rsaEncryption " +
                    "(the key size is not given); extended length Yes; LDS 0107.",
                "AU P 2016", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "That row lists Active Authentication with rsaEncryption but gives no key size.",
                "AU P 2016", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            gmrtdSample(
                "That row's PACE column shows \"n/a (BAC)\": the sampled passport has no PACE.",
                "AU P 2016", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdParsedSod(
                "The EF.SOD of that passport hashes no DG14 (no Chip Authentication or Terminal Authentication " +
                    "info), and the row's Chip Authentication column lists only Active Authentication. That is the " +
                    "only basis for \"no Chip Authentication\": one sample's SOD, not a table entry that says so, so " +
                    "the aspect stays ASSUMED.",
                "TestNewSod, AU P 2016 vector; README row AU P 2016", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            gmrtdParsedSod(
                "The EF.SOD of that passport has a Document Signer with an RSA-2048 key, signature rsaEncryption " +
                    "(PKCS#1 v1.5) and digest SHA-256, and hashes data groups 1, 2 and 15 only (no DG14, DG11 or DG12).",
                "TestNewSod and TestPassiveAuth, AU P 2016 vector", ProfileAspect.SIGNATURE
            ),
            gmrtdParsedSod(
                "The hashed data groups are 1, 2 and 15.",
                "TestNewSod, AU P 2016 vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(
            "gmrtd, Sample Documents — $GMRTD_README_URL",
            "gmrtd, parsed real SODs — $GMRTD_SOD_TEST_URL and $GMRTD_PASSIVE_AUTH_TEST_URL",
            ICAO_9303
        ),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The AA key type (RSA) is reported, but its size is not: 2048 bits is ASSUMED, so the Active " +
                "Authentication row stays ASSUMED. The sample has no PACE (reported), and no Chip Authentication is " +
                "assumed because its SOD hashes no DG14 and the README row lists only AA; both are disabled here.",
            SIGNER_NOTE
        )
    )

    // ---- Profiles from the remaining rows of the gmrtd README "Sample Documents" table (2026-10-12) ----

    private const val GMRTD_SOURCE = "gmrtd, Sample Documents — $GMRTD_README_URL"
    private const val GMRTD_SOD_SOURCE =
        "gmrtd, parsed real SODs — $GMRTD_SOD_TEST_URL and $GMRTD_PASSIVE_AUTH_TEST_URL"
    private const val DOCUMENT_CODE_NOTE =
        "The gmrtd README does not define the document type codes in its \"Type,Year\" column (P, PP, PO, PA, I, ID): " +
            "no legend is given. The codes look like the start of an MRZ document code, but the README does not say " +
            "so. The nearest DocumentType is used and no MRZ document code is set."
    private const val LDS_COLUMNS_NOTE =
        "The README's extended-length and LDS-version columns are listed as evidence only: the emulator does not vary " +
            "either per profile (EF.COM always declares LDS 1.7 and Unicode 4.0.0)."
    private const val PSS_NOTE =
        "The real Document Signer in the parsed SOD signs with RSASSA-PSS and SHA-256. The app's SOD generator does " +
            "not support PSS, so the test SOD uses SHA256withRSA (PKCS#1 v1.5) with an RSA-2048 test key: an ASSUMED " +
            "stand-in, and the signature aspect stays ASSUMED."
    private const val DG7_NOTE =
        "The sample's SOD hashes DG7 (displayed signature or usual mark), which PassportEmu does not generate: the " +
            "emulated data groups differ from the sample's, so the data groups aspect stays ASSUMED."
    private const val SAMPLE_HOLDER_NOTE =
        "The sample holder and document number are synthetic and neutral (ASSUMED), not issuer specimens; the " +
            "validity period is the default (ASSUMED)."
    private const val NO_SOD_SAMPLE_NOTE =
        "No EF.SOD sample was found for this row: the Document Signer is a PassportEmu test signer chosen as an " +
            "ASSUMED stand-in (EC P-256, SHA-256), and the data groups and optional files are ASSUMED."

    private fun specimen(code: String) =
        ProfileSample("Alex", "Example", "F", LocalDate(1985, 6, 15), "SPEC${code}001")

    /** A fact read from a real EF.SOD in gmrtd's tests, which holds for that one sample only. */
    private fun parsedSod(claim: String, locator: String, aspect: ProfileAspect?) =
        gmrtdParsedSod("Parsed from a real SOD in gmrtd, one sample: $claim", locator, aspect)

    private val austrianPassport2023 = ChipProfile(
        id = "at-passport-2023",
        title = "Austria · passport (2023)",
        shortTitle = "an Austrian passport (2023)",
        country = "AUT",
        documentType = DocumentType.PASSPORT,
        summary = "An Austrian 2023 passport test configuration, from one real passport's sampled chip: PACE-GM with " +
            "AES-128 and Chip Authentication with AES-128, both on brainpoolP256r1, DG11 and DG12, and an EF.SOD " +
            "signed with ECDSA brainpoolP256r1 and SHA-256. Whether BAC is accepted as well, and Terminal " +
            "Authentication, are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        sod = countrySigner("AT", KeySpec.Ec(EcCurve.BRAINPOOL_P256R1)),
        sample = specimen("AT"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Austrian passport (AT P 2023) uses PACE-GM with AES-CBC-CMAC-128 on brainpoolP256r1 and Chip " +
                    "Authentication (CA-ECDH) with AES-128 on brainpoolP256r1; extended length Yes; LDS 0107.",
                "AT P 2023", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH AES-128 on brainpoolP256r1.",
                "AT P 2023", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of that passport has a Document Signer on EC brainpoolP256r1 (valid 2023-01-31 to " +
                    "2033-05-06), signature ecdsa-SHA256, digest SHA-256, no ldsVersionInfo, and hashes data groups " +
                    "1, 2, 3, 11, 12 and 14.",
                "TestNewSod and TestPassiveAuth, AT vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 11, 12 and 14: DG3 (fingerprints) is present.",
                "TestNewSod, AT vector", ProfileAspect.DATA_GROUPS
            ),
            parsedSod(
                "that sampled SOD lacks DG15 among its hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, AT vector", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The README row does not show whether BAC is also accepted, so BAC alongside PACE is ASSUMED. Terminal " +
                "Authentication is ASSUMED on because the sampled SOD hashes DG3; the Chip Authentication suite is " +
                "reported but the aspect stays ASSUMED with it. " + AA_ABSENCE_NOTE,
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val chinesePassport2018 = ChipProfile(
        id = "cn-passport-po-2018",
        title = "China · passport (README type PO, 2018)",
        shortTitle = "a Chinese passport (README type PO, 2018)",
        country = "CHN",
        documentType = DocumentType.PASSPORT,
        summary = "A Chinese passport (README type PO, 2018) test configuration, from one real document's sampled " +
            "chip: BAC only, Active Authentication with an RSA key (size ASSUMED), DG11 and DG12, and an EF.SOD " +
            "signed with RSA-2048 and SHA-256.",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Rsa(2048),
        dataGroups = setOf(11, 12),
        sod = countrySigner("CN", KeySpec.Rsa(2048)),
        sample = specimen("CN"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Chinese document (CN PO 2018) offers BAC only (PACE \"n/a (BAC)\") and Active Authentication " +
                    "with rsaEncryption (the key size is not given); extended length No; LDS 0107.",
                "CN PO 2018", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "That row's PACE column shows \"n/a (BAC)\": the sampled document has no PACE.",
                "CN PO 2018", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row lists Active Authentication with rsaEncryption but gives no key size.",
                "CN PO 2018", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the EF.SOD of that document has a Document Signer with an RSA-2048 key, signature " +
                    "sha256WithRSAEncryption (PKCS#1 v1.5), digest SHA-256, eContentType id-data (1.2.840.113549.1.7.1) " +
                    "instead of the ICAO LDS Security Object type, and hashes data groups 1, 2, 11, 12 and 15.",
                "TestNewSod, CN vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 11, 12 and 15.",
                "TestNewSod, CN vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            "The real SOD's eContentType is id-data rather than the ICAO LDS Security Object type; PassportEmu " +
                "cannot reproduce that, so the signature aspect stays ASSUMED although the key, digest and padding " +
                "match the sample.",
            "The Active Authentication key type (RSA) is reported, but its size is not: 2048 bits is ASSUMED, so the " +
                "Active Authentication aspect stays ASSUMED. Chip Authentication is not listed (not evidence of absence) " +
                "and is disabled here, as an ASSUMED choice; the sampled SOD hashes no DG14.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val finnishIdCard2023 = ChipProfile(
        id = "fi-id-card-2023",
        title = "Finland · ID card (2023)",
        shortTitle = "a Finnish ID card (2023)",
        country = "FIN",
        documentType = DocumentType.ID_CARD,
        summary = "A Finnish 2023 ID card test configuration, from one real card's sampled chip: PACE-CAM with " +
            "AES-256 on brainpoolP384r1, protected fingerprints, and an EF.SOD signed with ECDSA brainpoolP512r1 " +
            "and SHA-512. Whether BAC is accepted as well is ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        paceMapping = PaceMapping.CHIP_AUTHENTICATION,
        otherPaceMappings = listOf(PaceMapping.GENERIC),
        pace = PaceSpec(EcCurve.BRAINPOOL_P384R1, SessionCipher.AES_256),
        terminalAuthentication = true,
        sod = SodSpec(
            digestAlgorithm = "SHA-512",
            signerKey = KeySpec.Ec(EcCurve.BRAINPOOL_P512R1),
            passportSigner = "smartemu-test-ds-fi-id",
            cardSigner = "smartemu-test-ds-fi-id",
            csca = "PassportEmu TEST CSCA FI"
        ),
        sample = specimen("FI"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Finnish ID card (FI I 2023) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP384r1, and its " +
                    "chip authentication column lists PACE-CAM with AES-CBC-CMAC-256 on brainpoolP384r1; extended " +
                    "length Yes; LDS 0108.",
                "FI I 2023", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row's chip authentication column lists PACE-CAM only, with no separate CA-ECDH entry (one " +
                    "mechanism per cell: not evidence that none exists).",
                "FI I 2023", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of that card (Document Signer subject \"ICAO Compliant Document Signer for Identity " +
                    "Cards\", valid 2023-07-30 to 2028-10-30) has an EC brainpoolP512r1 key, signature ecdsa-SHA512, " +
                    "digest SHA-512, LDS version 0108 / Unicode 040000, and hashes data groups 1, 2, 3, 7 and 14.",
                "TestNewSod and TestPassiveAuth, FI vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "that sampled SOD lacks DG15 among its hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, FI vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 7 and 14: DG3 (fingerprints) is present.",
                "TestNewSod, FI vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, EU_ID_CARD_REGULATION, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            "The README row lists both PACE-GM and PACE-CAM, and EF.CardAccess offers both. The " +
                "row does not show whether BAC is also accepted (ASSUMED). Terminal Authentication is ASSUMED on " +
                "because the sampled SOD hashes DG3; there is no separate Chip Authentication, as the row lists " +
                "PACE-CAM only, but that is not evidence of absence, so the aspect stays ASSUMED.",
            AA_ABSENCE_NOTE,
            DG7_NOTE,
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            "The signer is a PassportEmu test Document Signer on brainpoolP512r1 for ID documents (`smartemu-test-ds-fi-id`), " +
                "chained to the Finnish test CSCA.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val finnishPassport2024 = ChipProfile(
        id = "fi-passport-2024",
        title = "Finland · passport (2024)",
        shortTitle = "a Finnish passport (2024)",
        country = "FIN",
        documentType = DocumentType.PASSPORT,
        summary = "A Finnish 2024 passport test configuration, from one real passport's sampled chip: PACE-CAM with " +
            "AES-256 on brainpoolP384r1. Whether BAC is accepted as well, Terminal Authentication and the Document " +
            "Signer's algorithm are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        paceMapping = PaceMapping.CHIP_AUTHENTICATION,
        otherPaceMappings = listOf(PaceMapping.GENERIC),
        pace = PaceSpec(EcCurve.BRAINPOOL_P384R1, SessionCipher.AES_256),
        terminalAuthentication = true,
        sod = countrySigner("FI"),
        sample = specimen("FI").copy(documentNumber = "SPECFI002"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real Finnish passport (FI P 2024) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP384r1, and its " +
                    "chip authentication column lists PACE-CAM with AES-CBC-CMAC-256 on brainpoolP384r1; extended " +
                    "length Yes; LDS 0108.",
                "FI P 2024", ProfileAspect.PACE_CRYPTOGRAPHY
            )
        ),
        sources = listOf(GMRTD_SOURCE, BSI_TR_03110, EU_PASSPORT_REGULATION, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            NO_SOD_SAMPLE_NOTE,
            "The README row lists both PACE-GM and PACE-CAM, and EF.CardAccess offers both. Whether " +
                "BAC is accepted alongside PACE is ASSUMED. Terminal Authentication is ASSUMED on, as EU passports " +
                "carry protected fingerprints; nothing in the evidence says so for this row.",
            "The Finnish ID card sample's signer is brainpoolP512r1 (see `fi-id-card-2023`); nothing says the " +
                "passport Document Signer has the same key.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val frenchPassport2017 = ChipProfile(
        id = "fr-passport-2017",
        title = "France · passport (2017)",
        shortTitle = "a French passport (2017)",
        country = "FRA",
        documentType = DocumentType.PASSPORT,
        summary = "A French 2017 passport test configuration, from one real passport's sampled chip: PACE-GM with " +
            "AES-256 on NIST P-256, Chip Authentication with 3DES on NIST P-256, DG11 and DG12, and an EF.SOD " +
            "signed with RSA-2048 and SHA-256. Whether BAC is accepted as well, and Terminal Authentication, are " +
            "ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.NIST_P256, SessionCipher.AES_256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P256, SessionCipher.TDES),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        sod = SodSpec(
            digestAlgorithm = "SHA-256",
            signerKey = KeySpec.Rsa(2048),
            passportSigner = "smartemu-test-ds-fr-rsa",
            cardSigner = "smartemu-test-ds-fr-rsa",
            csca = "PassportEmu TEST CSCA FR"
        ),
        sample = specimen("FR"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real French passport (FR P 2017) uses PACE-GM with AES-CBC-CMAC-256 on secp256r1 and Chip " +
                    "Authentication (CA-ECDH) with 3DES-CBC-CBC on secp256r1; extended length Yes; LDS 0107.",
                "FR P 2017", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH 3DES-CBC-CBC on secp256r1.",
                "FR P 2017", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of that passport has a Document Signer with an RSA-2048 key (valid from 2017-07-18), " +
                    "signature sha256WithRSAEncryption (PKCS#1 v1.5), digest SHA-256, and hashes data groups 1, 2, 3, " +
                    "11, 12, 13 and 14.",
                "TestNewSod and TestPassiveAuth, FR vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "that sampled SOD lacks DG15 among its hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, FR vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 11, 12, 13 and 14.",
                "TestNewSod, FR vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, EU_PASSPORT_REGULATION, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The README row does not show whether BAC is also accepted, so BAC alongside PACE is ASSUMED. Terminal " +
                "Authentication is ASSUMED on because the sampled SOD hashes DG3.",
            AA_ABSENCE_NOTE,
            "The sampled SOD also hashes DG13 (optional national data), which PassportEmu does not generate: the " +
                "emulated data groups differ from the sample's, so the data groups aspect stays ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            "The signer is `smartemu-test-ds-fr-rsa`, a PassportEmu test Document Signer with an RSA-2048 key, " +
                "chained to the French test CSCA.",
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val frenchIdCard2024 = ChipProfile(
        id = "fr-id-card-2024",
        title = "France · ID card (2024)",
        shortTitle = "a French ID card (2024)",
        country = "FRA",
        documentType = DocumentType.ID_CARD,
        summary = "A French 2024 ID card test configuration, from one real card's sampled chip: PACE-GM with AES-256 " +
            "and PACE-IM on the same parameters, and Chip Authentication with AES-256, all on brainpoolP256r1. " +
            "Everything else is ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        otherPaceMappings = listOf(PaceMapping.INTEGRATED),
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        terminalAuthentication = true,
        sod = countrySigner("FR"),
        sample = specimen("FR").copy(documentNumber = "SPECFR002"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real French ID card (FR ID 2024) offers PACE-GM and PACE-IM, both with AES-CBC-CMAC-256 on " +
                    "brainpoolP256r1 (the README marks PACE-IM \"not supported\" by gmrtd), and Chip Authentication " +
                    "(CA-ECDH) with AES-CBC-CMAC-256 on brainpoolP256r1; extended length Yes; LDS 0108.",
                "FR ID 2024", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH AES-256 on brainpoolP256r1.",
                "FR ID 2024", ProfileAspect.EXTENDED_ACCESS_CONTROL
            )
        ),
        sources = listOf(GMRTD_SOURCE, BSI_TR_03110, EU_ID_CARD_REGULATION, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            NO_SOD_SAMPLE_NOTE,
            "EF.CardAccess offers PACE-GM and PACE-IM, as the README row lists (it marks PACE-IM \"not supported\" " +
                "by gmrtd).",
            "Whether BAC is accepted alongside PACE, Active Authentication and Terminal Authentication are ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val hongKongPassport2025 = ChipProfile(
        id = "hk-passport-2025",
        title = "Hong Kong · passport (2025)",
        shortTitle = "a Hong Kong passport (2025)",
        country = "HKG",
        documentType = DocumentType.PASSPORT,
        summary = "A Hong Kong 2025 passport test configuration, from one real passport's sampled chip: PACE-GM with " +
            "AES-256 on brainpoolP256r1 and Active Authentication with an ECDSA key on brainpoolP256r1. Whether BAC " +
            "is accepted as well and everything else are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        activeAuthentication = KeySpec.Ec(EcCurve.BRAINPOOL_P256R1),
        sod = countrySigner("HK"),
        sample = specimen("HK"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real Hong Kong passport (HK P 2025) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP256r1; extended " +
                    "length Yes; LDS 0108.",
                "HK P 2025", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row lists Active Authentication with an elliptic curve key (\"AA-ecc\") on brainpoolP256r1 and " +
                    "no Chip Authentication. It does not give the AA signature's hash algorithm.",
                "HK P 2025", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf(GMRTD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            NO_SOD_SAMPLE_NOTE,
            "Active Authentication is on with an ECDSA key on brainpoolP256r1, as the row lists it. The row gives " +
                "no hash algorithm for the AA signature, so the SHA-256 the emulator uses and the aspect stay ASSUMED. " +
                "No Chip Authentication is listed (one mechanism per cell: not evidence of absence); it and Terminal " +
                "Authentication are disabled here, as ASSUMED choices.",
            "Whether BAC is accepted alongside PACE is ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val indonesianPassport2025 = ChipProfile(
        id = "id-passport-2025",
        title = "Indonesia · passport (2025)",
        shortTitle = "an Indonesian passport (2025)",
        country = "IDN",
        documentType = DocumentType.PASSPORT,
        summary = "An Indonesian 2025 passport test configuration (2010 CSCA Series), from one real passport's " +
            "sampled chip: PACE-GM with AES-256 and Chip Authentication with AES-256, both on brainpoolP256r1, and " +
            "an Active Authentication key (type ASSUMED). Whether BAC is accepted as well and the signer are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        activeAuthentication = KeySpec.Rsa(2048),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        terminalAuthentication = true,
        sod = countrySigner("IDN", KeySpec.Rsa(2048)),
        sample = specimen("ID"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Indonesian passport (ID P 2025, note \"2010 CSCA Series\") uses PACE-GM with AES-CBC-CMAC-256 on " +
                    "brainpoolP256r1 and Chip Authentication (CA-ECDH) with AES-256 on brainpoolP256r1; extended " +
                    "length Yes; LDS 0107.",
                "ID P 2025", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH AES-256 on brainpoolP256r1; it lists no " +
                    "Active Authentication.",
                "ID P 2025", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of an Indonesian passport (2010 CSCA Series; TestPassiveAuth case \"ID\") has a Document " +
                    "Signer with an RSA-2048 key, signature RSASSA-PSS, digest SHA-256, and hashes data groups 1, 2, " +
                    "3, 14 and 15.",
                "TestPassiveAuth, ID vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "DG15 is present among the hashed data groups, so an Active Authentication key exists in that " +
                    "sample, although the README row lists none; the key type is unknown.",
                "TestPassiveAuth, ID vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 14 and 15.",
                "TestPassiveAuth, ID vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The row does not show whether BAC is also accepted, so BAC alongside PACE is ASSUMED. Terminal " +
                "Authentication is ASSUMED on because the sampled SOD hashes DG3.",
            "The sampled SOD hashes DG15, so an Active Authentication key is present in that sample (reported as " +
                "one SOD's data group), but the README row lists only Chip Authentication and gives no AA key type: " +
                "RSA-2048 is an ASSUMED stand-in and the aspect stays ASSUMED.",
            PSS_NOTE,
            "The ISO alpha-3 code is IDN; the test PKI files and the test CSCA use \"idn\" (its certificates say C=ID) " +
                "because `smartemu-test-ds-id` is the generic ID card signer.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val malaysianPassport2023 = ChipProfile(
        id = "my-passport-2023",
        title = "Malaysia · passport (2023)",
        shortTitle = "a Malaysian passport (2023)",
        country = "MYS",
        documentType = DocumentType.PASSPORT,
        summary = "A Malaysian 2023 passport test configuration, from one real passport's sampled chip: BAC only and " +
            "Chip Authentication with 3DES on brainpoolP256r1, DG11 and DG12, and an RSA-2048 signed EF.SOD " +
            "(PKCS#1 v1.5 here, RSASSA-PSS on the real one).",
        accessControl = AccessControl.BAC_ONLY,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.TDES),
        terminalAuthentication = true,
        dataGroups = setOf(11, 12),
        sod = countrySigner("MY", KeySpec.Rsa(2048)),
        sample = specimen("MY"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.DATA_GROUPS to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Malaysian passport (MY P 2023) offers BAC only (PACE \"n/a (BAC)\") and Chip Authentication " +
                    "(CA-ECDH) with 3DES-CBC-CBC on brainpoolP256r1; extended length Yes; LDS 0107.",
                "MY P 2023", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "That row's PACE column shows \"n/a (BAC)\": the sampled passport has no PACE.",
                "MY P 2023", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH 3DES-CBC-CBC on brainpoolP256r1.",
                "MY P 2023", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of that passport has a Document Signer with an RSA-2048 key, signature RSASSA-PSS, digest " +
                    "SHA-256, and hashes data groups 1, 2, 3, 11, 12 and 14.",
                "TestNewSod and TestPassiveAuth, MY vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "that sampled SOD lacks DG15 among its hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, MY vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 11, 12 and 14.",
                "TestNewSod, MY vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "Terminal Authentication is ASSUMED on because the sampled SOD hashes DG3, so the Chip and Terminal " +
                "Authentication aspect stays ASSUMED although the Chip Authentication suite is reported.",
            AA_ABSENCE_NOTE,
            PSS_NOTE,
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val newZealandPassport2017 = ChipProfile(
        id = "nz-passport-2017",
        title = "New Zealand · passport (2017)",
        shortTitle = "a New Zealand passport (2017)",
        country = "NZL",
        documentType = DocumentType.PASSPORT,
        summary = "A New Zealand 2017 passport test configuration, from one real passport's sampled chip: PACE-GM " +
            "with 3DES on brainpoolP256r1, Active Authentication with an RSA key (size ASSUMED), DG12, and an EF.SOD " +
            "signed with RSA-2048 and SHA-256. Whether BAC is accepted as well is ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.TDES),
        activeAuthentication = KeySpec.Rsa(2048),
        dataGroups = setOf(12),
        sod = countrySigner("NZ", KeySpec.Rsa(2048)),
        sample = specimen("NZ"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real New Zealand passport (NZ P 2017) uses PACE-GM with 3DES-CBC-CBC on brainpoolP256r1 and lists " +
                    "Active Authentication with rsaEncryption (no key size); extended length No; LDS 0107.",
                "NZ P 2017", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row lists Active Authentication with rsaEncryption but gives no key size.",
                "NZ P 2017", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the EF.SOD of that passport (Document Signer valid 2017-10-01 to 2028-02-12) has an RSA-2048 key, " +
                    "signature rsaEncryption (PKCS#1 v1.5), digest SHA-256, and hashes data groups 1, 2, 12, 13, 14 " +
                    "and 15.",
                "TestNewSod and TestPassiveAuth, NZ vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 12, 13, 14 and 15: DG3 (fingerprints) is absent.",
                "TestNewSod, NZ vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The row does not show whether BAC is also accepted, so BAC alongside PACE is ASSUMED. PACE runs with " +
                "3DES, as the row says; PACE-GM is the only mapping defined with 3DES.",
            "The AA key type (RSA) is reported, but its size is not: 2048 bits is ASSUMED, so the Active " +
                "Authentication aspect stays ASSUMED. No Chip Authentication is listed (one mechanism per cell: not " +
                "evidence of absence); it is disabled here as an ASSUMED choice, and so is Terminal Authentication " +
                "(the sampled SOD hashes no DG3).",
            "The sampled SOD also hashes DG13 (optional national data), which PassportEmu does not generate: the " +
                "emulated data groups differ from the sample's, so the data groups aspect stays ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val russianPassport2020 = ChipProfile(
        id = "ru-passport-2020",
        title = "Russia · passport (2020)",
        shortTitle = "a Russian passport (2020)",
        country = "RUS",
        documentType = DocumentType.PASSPORT,
        summary = "A Russian 2020 passport test configuration, from one real passport's sampled chip: BAC only and " +
            "Chip Authentication with 3DES on NIST P-192 (an assumed reading of the README's \"secp192\"), and an EF.SOD signed with " +
            "ECDSA P-256 and SHA-1.",
        accessControl = AccessControl.BAC_ONLY,
        chipAuthentication = ChipAuthenticationSpec(EcCurve.NIST_P192, SessionCipher.TDES),
        terminalAuthentication = true,
        sod = SodSpec(
            digestAlgorithm = "SHA-1",
            signerKey = KeySpec.Ec(EcCurve.NIST_P256),
            passportSigner = "smartemu-test-ds-ru",
            cardSigner = "smartemu-test-ds-ru",
            csca = "PassportEmu TEST CSCA RU"
        ),
        sample = specimen("RU"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.SIGNATURE to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Russian passport (RU P 2020) offers BAC only (PACE \"n/a (BAC)\") and Chip Authentication " +
                    "(CA-ECDH) with 3DES-CBC-CBC on \"secp192\"; extended length Yes; LDS 0107.",
                "RU P 2020", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "That row's PACE column shows \"n/a (BAC)\": the sampled passport has no PACE.",
                "RU P 2020", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH 3DES-CBC-CBC on a curve named \"secp192\" (a " +
                    "192-bit curve; the README does not say which).",
                "RU P 2020", ProfileAspect.EXTENDED_ACCESS_CONTROL
            ),
            parsedSod(
                "the EF.SOD of that passport (Document Signer valid 2019-11-13 to 2031-11-10) has an EC P-256 key, " +
                    "signature ecdsa-SHA1, digest SHA-1, and hashes data groups 1, 2, 3, 13 and 14.",
                "TestNewSod and TestPassiveAuth, RU vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "that sampled SOD lacks DG15 among its hashed data groups; this single sample does not show whether the " +
                    "chip has Active Authentication.",
                "TestNewSod, RU vector", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 3, 13 and 14.",
                "TestNewSod, RU vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, BSI_TR_03110, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The README gives only \"secp192\" for the Chip Authentication curve. gmrtd's own curve table treats a " +
                "192-bit curve as ambiguous between domain parameter id 8 (NIST P-192, secp192r1) and id 9 " +
                "(brainpoolP192r1). NIST P-192 (TR-03110 id 8) is the assumed reading and brainpoolP192r1 (id 9) the " +
                "alternative, so the curve is ASSUMED and the Chip and Terminal Authentication aspect stays ASSUMED. " +
                "P-192 is used here for Chip Authentication only; PACE never runs on it.",
            "Terminal Authentication is ASSUMED on because the sampled SOD hashes DG3.",
            AA_ABSENCE_NOTE,
            "The sampled SOD also hashes DG13 (optional national data), which PassportEmu does not generate: the " +
                "emulated data groups differ from the sample's, so the data groups aspect stays ASSUMED.",
            "The Document Signer's signature and SOD digest are SHA-1, as parsed from one sample; SHA-1 is weak, " +
                "and a reader that refuses it will reject this SOD.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val philippinePassport2020 = ChipProfile(
        id = "ph-passport-2020",
        title = "Philippines · passport (2020)",
        shortTitle = "a Philippine passport (2020)",
        country = "PHL",
        documentType = DocumentType.PASSPORT,
        summary = "A Philippine 2020 passport test configuration, from one real passport's sampled chip: BAC only, " +
            "Active Authentication with an RSA key (size ASSUMED), DG11 and DG12, and an RSA-2048 signed EF.SOD " +
            "(PKCS#1 v1.5 here, RSASSA-PSS on the real one).",
        accessControl = AccessControl.BAC_ONLY,
        activeAuthentication = KeySpec.Rsa(2048),
        dataGroups = setOf(11, 12),
        sod = countrySigner("PH", KeySpec.Rsa(2048)),
        sample = specimen("PH"),
        provenance = provenanceWith(
            ProfileAspect.ACCESS_CONTROL to Provenance.REPORTED,
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Philippine passport (PH P 2020) offers BAC only (PACE \"n/a (BAC)\") and Active " +
                    "Authentication with rsaEncryption (the key size is not given); extended length Yes; LDS 0107.",
                "PH P 2020", ProfileAspect.ACCESS_CONTROL
            ),
            gmrtdSample(
                "That row's PACE column shows \"n/a (BAC)\": the sampled passport has no PACE.",
                "PH P 2020", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row lists Active Authentication with rsaEncryption but gives no key size.",
                "PH P 2020", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            parsedSod(
                "the EF.SOD of that passport (Document Signer valid 2019-11-25 to 2030-04-01) has an RSA-2048 key, " +
                    "signature RSASSA-PSS, digest SHA-256, and hashes data groups 1, 2, 7, 11, 12 and 15.",
                "TestNewSod and TestPassiveAuth, PH vector", ProfileAspect.SIGNATURE
            ),
            parsedSod(
                "the hashed data groups are 1, 2, 7, 11, 12 and 15.",
                "TestNewSod, PH vector", ProfileAspect.DATA_GROUPS
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            "The AA key type (RSA) is reported, but its size is not: 2048 bits is ASSUMED, so the Active " +
                "Authentication aspect stays ASSUMED. No Chip Authentication is listed (one mechanism per cell: not " +
                "evidence of absence); it is disabled here as an ASSUMED choice.",
            DG7_NOTE,
            PSS_NOTE,
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val portuguesePassport2026 = ChipProfile(
        id = "pt-passport-2026",
        title = "Portugal · passport (README type PP, 2026)",
        shortTitle = "a Portuguese passport (README type PP, 2026)",
        country = "PRT",
        documentType = DocumentType.PASSPORT,
        summary = "A Portuguese passport (README type PP, 2026) test configuration, from one real passport's sampled " +
            "chip: PACE-GM with AES-128 and Chip Authentication with AES-128, both on brainpoolP256r1. Whether BAC " +
            "is accepted as well, Terminal Authentication and the signer are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        chipAuthentication = ChipAuthenticationSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_128),
        terminalAuthentication = true,
        sod = countrySigner("PT"),
        sample = specimen("PT"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real Portuguese passport (PT PP 2026) uses PACE-GM with AES-CBC-CMAC-128 on brainpoolP256r1 and " +
                    "Chip Authentication (CA-ECDH) with AES-128 on brainpoolP256r1; extended length Yes; LDS 0107.",
                "PT PP 2026", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "The same row's Chip Authentication column lists CA-ECDH AES-128 on brainpoolP256r1.",
                "PT PP 2026", ProfileAspect.EXTENDED_ACCESS_CONTROL
            )
        ),
        sources = listOf(GMRTD_SOURCE, BSI_TR_03110, EU_PASSPORT_REGULATION, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            NO_SOD_SAMPLE_NOTE,
            "Whether BAC is accepted alongside PACE is ASSUMED. Terminal Authentication is ASSUMED on, as EU " +
                "passports carry protected fingerprints; nothing in the evidence says so for this row.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            FINGERPRINT_NOTE,
            SIGNER_NOTE
        )
    )

    private val singaporePassportPa2023 = ChipProfile(
        id = "sg-passport-pa-2023",
        title = "Singapore · passport (README type PA, 2023)",
        shortTitle = "a Singapore passport (README type PA, 2023)",
        country = "SGP",
        documentType = DocumentType.PASSPORT,
        summary = "A Singapore document of README type PA (2023), test configuration, from one real document's " +
            "sampled chip: PACE-GM with AES-256 on brainpoolP256r1 and no Chip or Active Authentication (\"Cloneable\"). " +
            "Whether BAC is accepted as well and the signer are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        sod = countrySigner("SG", KeySpec.Rsa(2048)),
        sample = specimen("SG"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Singapore document (SG PA 2023) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP256r1; " +
                    "extended length Yes; LDS 0108.",
                "SG PA 2023", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row is listed \"Cloneable\": no Chip Authentication and no Active Authentication.",
                "SG PA 2023", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            gmrtdSample(
                "The same \"Cloneable\" entry reports no Chip Authentication for that row.",
                "SG PA 2023", ProfileAspect.EXTENDED_ACCESS_CONTROL
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            "The README lists two Singapore rows (PA 2023 and PP 2025); this is the PA row, `sg-passport-pp-2025` the " +
                "other. Which kind of document each is cannot be told from the README.",
            "Parsed from a real SOD in gmrtd, one sample (RSA-2048 key, RSASSA-PSS, SHA-256, data groups 1, 2, 3, 4, " +
                "13 and 14, LDS 0108 / Unicode 040000, no DG15), which does not say which kind of document it is from, " +
                "so it is not attached to either row here. It is the only basis for the RSA-2048 test signer, an ASSUMED stand-in, " +
                "and for the signature (PSS: not supported by the SOD generator, PKCS#1 v1.5 is used) and the data " +
                "groups, which stay ASSUMED. Terminal Authentication is not enabled (ASSUMED).",
            "BAC alongside PACE is ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val singaporePassportPp2025 = ChipProfile(
        id = "sg-passport-pp-2025",
        title = "Singapore · passport (README type PP, 2025)",
        shortTitle = "a Singapore passport (README type PP, 2025)",
        country = "SGP",
        documentType = DocumentType.PASSPORT,
        summary = "A Singapore document of README type PP (2025), test configuration, from one real document's " +
            "sampled chip: PACE-GM with AES-256 on brainpoolP256r1 and Active Authentication with an RSA key (size " +
            "ASSUMED). Whether BAC is accepted as well and the signer are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.BRAINPOOL_P256R1, SessionCipher.AES_256),
        activeAuthentication = KeySpec.Rsa(2048),
        sod = countrySigner("SG", KeySpec.Rsa(2048)),
        sample = specimen("SG").copy(documentNumber = "SPECSG002"),
        provenance = provenanceWith(ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED),
        evidence = listOf(
            gmrtdSample(
                "A real Singapore document (SG PP 2025) uses PACE-GM with AES-CBC-CMAC-256 on brainpoolP256r1 and " +
                    "lists Active Authentication with rsaEncryption (no key size); extended length Yes; LDS 0108.",
                "SG PP 2025", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row lists Active Authentication with rsaEncryption but gives no key size, and no Chip " +
                    "Authentication.",
                "SG PP 2025", ProfileAspect.ACTIVE_AUTHENTICATION
            )
        ),
        sources = listOf(GMRTD_SOURCE, GMRTD_SOD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            DOCUMENT_CODE_NOTE,
            "The README lists two Singapore rows (PA 2023 and PP 2025); this is the PP row, `sg-passport-pa-2023` the " +
                "other. Which kind of document each is cannot be told from the README.",
            "The AA key type (RSA) is reported, but its size is not: 2048 bits is ASSUMED, so the Active " +
                "Authentication aspect stays ASSUMED. No Chip Authentication is listed (one mechanism per cell: not " +
                "evidence of absence); it and Terminal Authentication are disabled here as ASSUMED choices.",
            "Parsed from a real SOD in gmrtd, one sample (RSA-2048 key, RSASSA-PSS, SHA-256, data groups 1, 2, 3, 4, " +
                "13 and 14, LDS 0108 / Unicode 040000, no DG15), which does not say which kind of document it is from, " +
                "so it is not attached to this row; a DG15 in this row's SOD would be expected from its AA entry, which that " +
                "sample lacks. It is the only basis for the RSA-2048 test signer, an ASSUMED stand-in, and the " +
                "signature (PSS: not supported by the SOD generator, PKCS#1 v1.5 is used) and the data groups " +
                "stay ASSUMED.",
            "BAC alongside PACE is ASSUMED.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    private val taiwanesePassport2024 = ChipProfile(
        id = "tw-passport-2024",
        title = "Taiwan · passport (2024)",
        shortTitle = "a Taiwanese passport (2024)",
        country = "TWN",
        documentType = DocumentType.PASSPORT,
        summary = "A Taiwanese 2024 passport test configuration, from one real passport's sampled chip: PACE-GM with " +
            "AES-256 on NIST P-256 and no Chip or Active Authentication (\"Cloneable\"). Whether BAC is accepted as " +
            "well and the signer are ASSUMED.",
        accessControl = AccessControl.BAC_AND_PACE,
        pace = PaceSpec(EcCurve.NIST_P256, SessionCipher.AES_256),
        sod = countrySigner("TW"),
        sample = specimen("TW"),
        provenance = provenanceWith(
            ProfileAspect.PACE_CRYPTOGRAPHY to Provenance.REPORTED,
            ProfileAspect.ACTIVE_AUTHENTICATION to Provenance.REPORTED
        ),
        evidence = listOf(
            gmrtdSample(
                "A real Taiwanese passport (TW P 2024) uses PACE-GM with AES-CBC-CMAC-256 on secp256r1; extended " +
                    "length Yes; LDS 0107.",
                "TW P 2024", ProfileAspect.PACE_CRYPTOGRAPHY
            ),
            gmrtdSample(
                "That row is listed \"Cloneable\": no Chip Authentication and no Active Authentication.",
                "TW P 2024", ProfileAspect.ACTIVE_AUTHENTICATION
            ),
            gmrtdSample(
                "The same \"Cloneable\" entry reports no Chip Authentication for that row.",
                "TW P 2024", ProfileAspect.EXTENDED_ACCESS_CONTROL
            )
        ),
        sources = listOf(GMRTD_SOURCE, ICAO_9303),
        notes = listOf(
            ASSUMPTION_NOTE,
            GMRTD_NOTE,
            NO_SOD_SAMPLE_NOTE,
            "Whether BAC is accepted alongside PACE is ASSUMED. Terminal Authentication is not enabled (ASSUMED): " +
                "nothing is known about fingerprints.",
            LDS_COLUMNS_NOTE,
            SAMPLE_HOLDER_NOTE,
            SIGNER_NOTE
        )
    )

    val all: List<ChipProfile> = listOf(
        generic,
        euPassport,
        euIdCard,
        germanPassport,
        germanPassport2023,
        germanIdCard,
        dutchPassport,
        dutchPassport2024,
        dutchIdCard,
        britishPassport,
        americanPassport,
        frenchPassport2017,
        frenchIdCard2024,
        canadianPassport2023,
        australianPassport2016,
        austrianPassport2023,
        chinesePassport2018,
        finnishIdCard2023,
        finnishPassport2024,
        hongKongPassport2025,
        indonesianPassport2025,
        malaysianPassport2023,
        newZealandPassport2017,
        philippinePassport2020,
        portuguesePassport2026,
        russianPassport2020,
        singaporePassportPa2023,
        singaporePassportPp2025,
        taiwanesePassport2024
    )

    val default: ChipProfile get() = generic

    /** The profile with the given id, or the generic one for an id no profile has, as one saved by an older version. */
    fun byId(id: String): ChipProfile =
        com.hddev.smartemu.capture.profile.MeasuredProfileRegistry.profileFor(id) ?: all.find { it.id == id } ?: generic

    /** The profiles that fit a document of this type, generic first. */
    fun forDocumentType(type: DocumentType): List<ChipProfile> = all.filter { it.fits(type) }
}
