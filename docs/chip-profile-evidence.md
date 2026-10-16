# PassportEmu chip profile evidence

Research checked on 2026-09-30. Nine passport profiles were added to the existing eleven. The findings below use issuer publications, an official national audit, and the original authors' firsthand experiments. None of the emulator profiles was measured against a physical passport in this task.

## How confidence is represented

`ProfileEvidence` records a specific claim, primary URL, page/section locator, and its scope. The UI exposes these links under **Primary evidence for this generation**. It distinguishes this evidence from the actual emulator configuration.

An aspect remains **ASSUMED** when any material choice in that row is unverified. For example, confirmation of BAC support does not establish PACE's absence; confirmation of AA does not establish its key type. An assumed disabled feature means “disabled in this emulator”, not “absent from real passports”. Changing settings or adapting a curve also changes the corresponding UI badge to ASSUMED. “Exact cryptography” means the configured preset curve, not proof that it matches an issuer's chip.

CSCA certificates and master lists establish PKI authorities. They do **not** establish DS key type, SOD signature/digest/padding, AA keys, CA keys, or PACE suites. No profile algorithm here was derived from a CSCA key. Algorithms permitted by a standard, or supported by a certified vendor product, do not establish national passport personalisation or deployment.

## Added profiles and generation boundaries

| Profile ID | Bounded primary claim | Locator | Emulator limits |
|---|---|---|---|

All samples are synthetic holders and document numbers, not issuer specimens. Historical presets receive recent issue/expiry dates to exercise readers; their validity settings are defaults, not evidence that old-generation passports remain valid. New profiles include at least one actual chip protocol/feature claim. An R Series profile supported only by a visual design/launch page was discarded.

## Corrections to the existing eleven profiles

- The generic preset's NIST P-256/AES-128/DS choices are now ASSUMED rather than labelled requirements of ICAO. [ICAO Doc 9303](https://www.icao.int/publications/doc-series/doc-9303) is a protocol/format reference, not proof that every compliant chip selects the same suite.
- Coarse legacy aspect badges were conservatively downgraded to ASSUMED where the available citations do not substantiate the complete configured row. This includes German Brainpool DS/PACE/CA sizes, Dutch ECDSA AA, optional files and combined MRZ/validity settings. Their runtime choices are retained; the downgrade does not assert that they are wrong. (Germany, the Netherlands, the UK, the US and Australia were later revisited: see the 2026-10-12 section below.)
- The EU aggregate preset no longer claims that every selected protocol and algorithm is required for every current member-state passport. Jurisdictional requirements are not evidence of exact national suites.
- The German historical preset now returns `6985` for correctly sized EXTERNAL AUTHENTICATE before a challenge. The primary 2008 study reports this below Table 1; `6700` was a different malformed probe. Its applicability to the named 2005 design remains ASSUMED because the paper does not give the German sample's issue date.
- Dutch historical `6982` remains an emulator assumption. In the cited paper, `6982` is the INTERNAL AUTHENTICATE entry, not evidence for the implemented EXTERNAL AUTHENTICATE request. Unsupported or erroneous responses to malformed requests are not proof of AA's absence.
- “Real curve” UI wording was replaced with “preset curve”. Absence is displayed as “disabled in this configuration”. The signer and implementation descriptions say PassportEmu; legacy certificate CNs and resource filenames retain SmartEmu.

## Test PKI and signature limits

Seven new test authorities/signers cover FR, BE, ES, IT, CH, CA, AU. Three more Document Signers were added on 2026-10-12 (listed below). Their new CNs contain **PassportEmu TEST**, with `O=PassportEmu, OU=Test PKI`. Resource basenames remain `smartemu-test-*`. Previously committed subjects, keys and certificates remain unchanged.

All seven new **CSCA** keys are EC P-256 test choices. Belgian and Canadian **DS** keys are RSA-2048; the other new DS keys are EC P-256 stand-ins (Australia's first signer remains one; the `-rsa` signers added on 2026-10-12 are described below). The Belgian DS choice follows a separately labelled DS claim in the primary paper, not its CSCA size or Table 3's recommendations. Certificate issuer signatures are SHA-256/ECDSA and must not be confused with the emulated SOD signature. Every signer chains to its matching test authority. No real private key is present.

Additional limits: PassportEmu generates only supported LDS files; it cannot reproduce arbitrary optional data or vendor APDU behaviour. DG3 is a locked emulator placeholder and TA is refused. The reported Spanish status word does not reproduce the paper's complete fingerprint. Unknown attributes remain ASSUMED even if an emulator integration test passes.

## 2026-10-12 correction against gmrtd samples and issuer/BSI sources

Research date 2026-10-12. New sources, and what each can and cannot establish:

- **gmrtd "Sample Documents" table**, [README.md](https://github.com/gmrtd/gmrtd/blob/main/README.md). Observations of real documents by the gmrtd author. Provenance: **REPORTED** (one document per row; a column that is not listed is not evidence of absence, but a cell that says "n/a" is reported absence of that mechanism). Used for access control, PACE/CA suites and curves, and the extended-length and LDS-version columns.
- **Parsed real EF.SODs** in gmrtd, [`document/sod_test.go`](https://github.com/gmrtd/gmrtd/blob/main/document/sod_test.go) (`TestNewSod`) and [`passiveauth/passive_auth_test.go`](https://github.com/gmrtd/gmrtd/blob/main/passiveauth/passive_auth_test.go) (`TestPassiveAuth`). Provenance: **REPORTED**, and the text of each claim says it is "parsed from a real SOD in gmrtd, one sample" (DS key type, signature algorithm, digest algorithm, hashed data groups). A single sample does not prove the same holds for every document of the generation.
- **BSI TR-03116-2 (2025)**, [PDF](https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03116/BSI-TR-03116-2.pdf?__blob=publicationFile&v=8). A technical guideline for German documents: **SPECIFICATION** for what German documents must or may do where it is unambiguous, still not a measurement of any chip.
- **RvIG (Dutch issuer) brochures**: [model 2014](https://www.rvig.nl/sites/default/files/2023-02/Kenmerkenbrochure%20paspoorten%20en%20identiteitskaarten%20model%202014%20NL.pdf), section "Chip", p. 7; [model 2024](https://www.rvig.nl/sites/default/files/2024-12/Kenmerkenbrochure%20Nederlands%20paspoort%20model%202024%20-%20EN.pdf), "Chip" box, p. 12; [introduction announcement, 30 Sep 2024](https://www.rijksoverheid.nl/actueel/nieuws/2024/09/30/nieuw-model-paspoort-en-id-kaart). Issuer text listing mechanisms (REPORTED in the code); no curves, ciphers or key types.
- [Security.nl, 756341](https://www.security.nl/posting/756341/Chip+op+paspoort+en+id-kaart+straks+alleen+uit+te+lezen+via+veiliger+protocol): secondary news report that BAC fallback ends in July 2022 (passports) and 29 August 2022 (ID cards). Mentioned in notes only.
- The US State Department page (travel.state.gov) returned 403, so **no US issuer source was found**; US claims rest on the gmrtd sample and its parsed SOD only.

### Changed profiles (IDs unchanged)

| Profile ID | Change | Claim and source | Provenance in code |
|---|---|---|---|
| `us-passport-2021` | Access control BAC and PACE to BAC only; Active Authentication removed; no CA/TA; DG11/12 kept; SOD now RSA-2048/SHA-256 (`smartemu-test-ds-us-rsa`, CSCA "SmartEmu Test CSCA US"). | gmrtd README Sample Documents, row US P 2021: "n/a (BAC)", "Cloneable" (no CA, no AA), extended length No, LDS 0107. Parsed SOD: DS RSA-2048, SHA-256, rsaEncryption PKCS#1 v1.5, DGs hashed {1,2,11,12}. | Access control, AA absent, CA absent: REPORTED (README row). SOD facts: REPORTED, parsed from a real SOD in gmrtd, one sample. |
| `gb-passport` | PACE-GM AES-256 on NIST P-256 and CA AES-256 on NIST P-256 (were generic AES-128 defaults); data groups now empty (DG11/12 dropped); TA off; AA off (ASSUMED, since one sampled SOD lacking DG15 does not show absence). Access control stays BAC and PACE with the BAC part ASSUMED. | gmrtd README, row UK P 2021: PACE-GM AES-CBC-CMAC-256 on secp256r1, CA-ECDH AES-256 secp256r1, extended length Yes, LDS 0108. Parsed SOD: DS EC P-256, ecdsa-SHA256, digest SHA-256, DGs {1,2,14} (no DG3, DG11, DG12, DG15). | PACE, CA, data groups: REPORTED (README row). SOD facts: REPORTED, parsed from a real SOD in gmrtd, one sample. Whether BAC is also accepted: ASSUMED. |
| `nl-passport` | Notes and evidence only: mechanism list confirmed, algorithms still ASSUMED. | RvIG brochure "Kenmerken ... model 2014", section "Chip", p. 7: BAC, PACE, EAC (CA and TA), AA, PA. | Access control REPORTED from the issuer's brochure (mechanism list only). Curves, ciphers and AA key type are not stated: those aspects stay ASSUMED. |
| `nl-id-card` | Notes and evidence only. | Same brochure and page: BAC, PACE, EAC (CA only), AA, PA. | Same as `nl-passport`. |
| `de-passport-2017` | Provenance upgraded where BSI specifies it (PACE, AA absence, SOD signature); Chip Authentication stays ASSUMED; runtime choices unchanged. | TR-03116-2 (2025): section 1.4.2 p. 9 Brainpool (RFC 5639); Table 5 section 2.1.2 p. 11 DS ECDSA-256/SHA-256 for 2010-2019 documents (footnote 4: switch to 384 with the CSCA key length change); Table 15 section 3.2 p. 15 PACE ECKA 256 with GM, AES-CBC 128/CMAC 128; section 6.1.1 p. 20 PACE-GM since 2013, CA v1 and TA v1; section 6.1.2 p. 20 no Active Authentication; section 6.1.1 p. 20 CA v1 and TA v1. Table 16 p. 17 lists CA v1 ECKA 256 AES-128 only as "Nationale Anwendung"; section 4.2.1 and Table 19 say the EU also required 3DES for CA v1 on passports until 2019, so AES-128 CA for a 2017 passport is not uniquely specified. | PACE cryptography, AA absence and SOD signature: SPECIFICATION (guideline for German documents, not a chip measurement). Chip and Terminal Authentication (EAC): ASSUMED; the BSI evidence entry is kept (it is SPECIFICATION as a statement of what the guideline says, and its text says what it does not settle). Aspects with any unspecified choice stay ASSUMED. |
| `de-id-card` | Mapping GM to CAM; rest kept. | TR-03116-2 sections 6.2.1-6.2.2 pp. 20-21: PACE-GM for all applications plus PACE-CAM for ePass since 2 Aug 2021; EAC v2; no BAC; no AA. | Access control, AA absence: SPECIFICATION. The card's DS key is not sample-proven; SHA-384 is probable but unconfirmed, so its signer is unchanged. |

### Added profiles

| Profile ID | Claim | Source and locator | Provenance in code |
|---|---|---|---|
| `nl-passport-2024` | PACE only (no BAC), CA and TA, AA, PA on the 2024 model. PACE-GM AES-256 on brainpoolP320r1 (parameter id 14), CA AES-256 on brainpoolP512r1 (id 17, BSI TR-03110 part 3 Table 4). | RvIG model-2024 brochure, "Chip" box, p. 12; introduction 30 Sep 2024 (rijksoverheid.nl); gmrtd README row NL PP 2025 for the suites and curves. | Mechanisms (access control): REPORTED from issuer text. Suites and curves: REPORTED (README, one sample). **AA key type not established**: the issuer says the AA/CA key length "has been increased"; the emulator uses ECDSA P-384 as an ASSUMED stand-in. **DS algorithm not established**: the existing NL signer is an ASSUMED stand-in. gmrtd's README lists one mechanism per row, so its silence about AA is not treated as absence. |
| `au-passport-2016-p` | BAC, AA with an RSA key, extended length Yes, LDS 0107; no CA, no PACE. SOD DS RSA-2048/SHA-256, DGs {1,2,15}. | gmrtd README row AU P 2016 and parsed SOD (see above). | Access control, PACE absence and AA type: REPORTED (README row: PACE column "n/a (BAC)", AA rsaEncryption). **AA key size: ASSUMED** (2048). SOD facts: REPORTED, parsed from a real SOD in gmrtd, one sample. **No Chip Authentication: ASSUMED** (the aspect badge; the evidence entry only reports the observation): the README CA column lists only AA, and the sampled SOD hashes no DG14, which is the only basis for "no CA". |
| `de-passport-2023` | PACE only; PACE-CAM on brainpoolP256r1 AES-128; CA brainpoolP256r1 AES-128; TA; no AA. SOD DS EC brainpoolP384r1, ecdsa-SHA384, digest SHA-384 (`smartemu-test-ds-de-p384`). | TR-03116-2 (2025) section 2.1.2 Table 5 p. 11 (DS ECDSA-384/SHA-384 from 2019); Table 15 p. 15 (PACE CAM since 2019); section 6.1.1 p. 20 (CAM since July 2021, "Seit Juli 2021 darf Basic Access Control bei neuen Dokumenten nicht mehr unterstützt werden"); section 6.1.2 p. 20 (no AA). Real EF.CardSecurity of a German P 2023 (parsed in gmrtd): PACE-ECDH-GM-AES-CBC-CMAC-128 and PACE-ECDH-CAM-AES-CBC-CMAC-128 on parameterId 13, CA-ECDH-AES-CBC-CMAC-128; SOD DGs {1,2,3,14}, LDS 0108/040000. | Access control and AA absence: SPECIFICATION (BSI). PACE, CA, data groups and SOD signature: REPORTED (the BSI Table 5/15 entries are SPECIFICATION, but the aspect stays REPORTED), with the facts parsed from a real EF.CardSecurity and SOD in gmrtd, one sample. The real chip advertises both GM and CAM; the emulator advertises one (CAM). |
| `ca-passport-2023` | PACE-GM AES-128 on NIST P-384; CA AES-128 on NIST P-384; extended length No; LDS 0108. | gmrtd README row CA PP 2023. | PACE and CA suites REPORTED (README row, one sample). No SOD sample: DS is the existing Canadian RSA-2048 signer as an ASSUMED stand-in. AA is not listed (unknown, not absent) and TA is unknown: both ASSUMED disabled. BAC alongside PACE is ASSUMED. |

Limits that remain: every sample is one document; the chip always runs the profile's PACE suite as it is; no profile here was measured against a physical chip.

### Test PKI additions

Three Document Signers were added under the existing country CSCAs (the CSCA key type does not limit the DS key type; no CSCA was changed). Each has `.key.pem`, `.cert.pem` and `.cert.der`, and is valid for 3652 days from the notBefore of its CSCA (2026-09-30), like the first signer of that country; `generate.sh` produces the same.

| Resource | Subject CN | DS key | Chains to | Used by |
|---|---|---|---|---|
| `document-signer/smartemu-test-ds-us-rsa` | SmartEmu TEST Document Signer US RSA | RSA-2048 | SmartEmu Test CSCA US | `us-passport-2021` |
| `document-signer/smartemu-test-ds-au-rsa` | PassportEmu TEST Document Signer AU RSA | RSA-2048 | PassportEmu TEST CSCA AU | `au-passport-2016-p` |
| `document-signer/smartemu-test-ds-de-p384` | SmartEmu TEST Document Signer DE P-384 | EC brainpoolP384r1 | SmartEmu Test CSCA DE | `de-passport-2023` |

The RSA signers sign SOD with SHA256withRSA (PKCS#1 v1.5); the DE P-384 signer signs with SHA384withECDSA. The certificates' own issuer signatures are ECDSA/SHA-256 and say nothing about the SOD.

### Verification of this correction

`ChipProfilesIntegrationTest` (14 tests) reads every profile, old and new, with JMRTD, including passive authentication against the profile's signer, and checks the PACE parameters the new profiles advertise and that a PACE-only chip answers BAC commands with `6D00`, one of the four status words (`6D00`, `6A81`, `6982`, `6A88`) after which a BAC-first reader falls back to PACE. `ChipConfigurationTest` (21 tests) covers each corrected aspect. A full JVM run has the same 11 baseline failures as before.

One production change came with it: the APDU parser now accepts an extended-length INTERNAL AUTHENTICATE (`00 Lc1 Lc2 data Le1 Le2`), which a JMRTD reader sends for RSA-2048 Active Authentication (the signature is too long for a short Le). Before, such a request answered a wrong-length error.

## 2026-10-12 remaining gmrtd README samples

Source: the [gmrtd README "Sample Documents" table](https://github.com/gmrtd/gmrtd/blob/main/README.md) (one real document per row, one mechanism per cell) and the real EF.SOD vectors in gmrtd's [`sod_test.go`](https://github.com/gmrtd/gmrtd/blob/main/document/sod_test.go) and [`passive_auth_test.go`](https://github.com/gmrtd/gmrtd/blob/main/passiveauth/passive_auth_test.go). Every parsed-SOD claim is worded "parsed from a real SOD in gmrtd, one sample". No issuer source was found for any of these rows, and none of the profiles was checked against a real chip.

Sixteen profiles were added, completing the table (existing ids unchanged; France's two sit next to its study sample, the others follow the Australian ones): `at-passport-2023`, `cn-passport-po-2018`, `fi-id-card-2023`, `fi-passport-2024`, `fr-passport-2017`, `fr-id-card-2024`, `hk-passport-2025`, `id-passport-2025`, `my-passport-2023`, `nz-passport-2017`, `ph-passport-2020`, `pt-passport-2026`, `ru-passport-2020`, `sg-passport-pa-2023`, `sg-passport-pp-2025`, `tw-passport-2024`.

| Profile | Access / PACE (README) | CA / AA (README) | SOD sample | Stand-ins |
|---|---|---|---|---|
| `at-passport-2023` | BAC+PACE-GM brainpoolP256r1 AES-128 | CA AES-128 brainpoolP256r1 | ECDSA brainpoolP256r1, SHA-256, DG 1,2,3,11,12,14 | BAC alongside PACE, TA |
| `cn-passport-po-2018` | BAC only | AA RSA (size not given) | RSA-2048, SHA-256, eContentType id-data, DG 1,2,11,12,15 | AA key size, id-data |
| `fi-id-card-2023` | BAC+PACE-CAM brainpoolP384r1 AES-256 | PACE-CAM only | ECDSA brainpoolP512r1, SHA-512, DG 1,2,3,7,14 | BAC alongside PACE, TA, DG7 omitted |
| `fi-passport-2024` | BAC+PACE-CAM brainpoolP384r1 AES-256 | PACE-CAM only | none | whole SOD, TA |
| `fr-passport-2017` | BAC+PACE-GM secp256r1 AES-256 | CA 3DES secp256r1 | RSA-2048, SHA-256 (PKCS#1 v1.5), DG 1,2,3,11,12,13,14 | BAC alongside PACE, TA, DG13 omitted |
| `fr-id-card-2024` | BAC+PACE-GM brainpoolP256r1 AES-256 (PACE-IM also listed, unsupported) | CA AES-256 brainpoolP256r1 | none | whole SOD, AA, TA |
| `hk-passport-2025` | BAC+PACE-GM brainpoolP256r1 AES-256 | AA ECDSA brainpoolP256r1, no CA | none | AA hash, whole SOD |
| `id-passport-2025` | BAC+PACE-GM brainpoolP256r1 AES-256 | CA AES-256 brainpoolP256r1 | RSA-2048, RSASSA-PSS, SHA-256, DG 1,2,3,14,15 | PSS, AA key type, TA |
| `my-passport-2023` | BAC only | CA 3DES brainpoolP256r1 | RSA-2048, RSASSA-PSS, SHA-256, DG 1,2,3,11,12,14 | PSS, TA |
| `nz-passport-2017` | BAC+PACE-GM brainpoolP256r1 3DES | AA RSA (size not given) | RSA-2048, SHA-256 (PKCS#1 v1.5), DG 1,2,12,13,14,15 | BAC alongside PACE, AA key size, DG13 omitted |
| `ph-passport-2020` | BAC only | AA RSA (size not given) | RSA-2048, RSASSA-PSS, SHA-256, DG 1,2,7,11,12,15 | PSS, AA key size, DG7 omitted |
| `pt-passport-2026` | BAC+PACE-GM brainpoolP256r1 AES-128 | CA AES-128 brainpoolP256r1 | none | whole SOD, TA |
| `ru-passport-2020` | BAC only | CA 3DES "secp192" | ECDSA P-256, SHA-1, DG 1,2,3,13,14 | curve reading, TA, DG13 omitted |
| `sg-passport-pa-2023` | BAC+PACE-GM brainpoolP256r1 AES-256 | "Cloneable" (no CA, no AA) | none attached | SOD, BAC alongside PACE |
| `sg-passport-pp-2025` | BAC+PACE-GM brainpoolP256r1 AES-256 | AA RSA (size not given) | none attached | SOD, AA key size |
| `tw-passport-2024` | BAC+PACE-GM secp256r1 AES-256 | "Cloneable" (no CA, no AA) | none | whole SOD |

Rules applied. Rows with PACE are `BAC_AND_PACE` with access control ASSUMED (the README never shows whether BAC is also accepted); "n/a (BAC)" rows are `BAC_ONLY` with the absence of PACE REPORTED. An aspect is REPORTED only when every choice in it is covered by its evidence; otherwise it stays ASSUMED even where some of its claims are sourced (for example RSA AA with an assumed 2048-bit size, or a sampled SOD whose DG7/DG13 the emulator does not generate). A "Cloneable" row reports no CA and no AA.

Document type codes. The README does not define the "Type" column's codes (P, PP, PO, PA, I, ID); each affected profile's notes say so, and the nearest `DocumentType` is used: ID card for `fi-id-card-2023` and `fr-id-card-2024`, passport for the rest. The README lists Singapore twice (PA 2023, PP 2025) without saying what the two kinds of document are; gmrtd parses one Singapore SOD without saying which, so it is only used as the basis of the RSA-2048 test signer and is attached to neither row.

Stand-ins and limits.

- Real RSASSA-PSS SODs (Indonesia, Malaysia, Philippines, Singapore) are replaced by PKCS#1 v1.5 with SHA-256, because the SOD generator has no PSS; each note says so and the signature aspect stays ASSUMED. China's real SOD uses eContentType id-data, which cannot be reproduced; its signature stays ASSUMED too.
- Indonesia's ISO code is IDN, and its test PKI files use "idn" (the certificates say C=ID) so as not to clash with the generic `smartemu-test-ds-id`. Finland's ID card has its own brainpoolP512r1 signer `smartemu-test-ds-fi-id`, chained to the Finnish test CSCA; the integration test's `cscaFor()` maps `-fi-id` to `csca-fi` and `-idn` to `csca-idn`, and the generic `-id` signer resolves as before.
- Russia: the README gives only "secp192". gmrtd's own `cms/curves.go` treats a 192-bit curve as ambiguous between domain parameter id 8 (NIST P-192, secp192r1) and id 9 (brainpoolP192r1). NIST P-192 (TR-03110 id 8) is the assumed reading and brainpoolP192r1 (id 9) the alternative, so the curve is ASSUMED and Chip and Terminal Authentication (EXTENDED_ACCESS_CONTROL) stays ASSUMED. `EcCurve.NIST_P192` was added for Chip Authentication only (`usableForPace` is false, and tests assert that no profile runs PACE on it and that no resolved configuration ever does); JMRTD's `doEACCA` over 3DES passes on it. Russian SOD digest and signature are SHA-1, as parsed from one sample.
- **Untested on device**: the integration tests run on the JVM with Bouncy Castle, which implements P-192. Whether the Android device's JCA provider supports P-192 key generation for the Russian Chip Authentication key is untested, so that profile's Chip Authentication may need on-device testing.
- Active Authentication absence: for `at-passport-2023`, `fi-id-card-2023`, `fr-passport-2017`, `my-passport-2023`, `ru-passport-2020` (and `gb-passport` from the earlier round) the only basis is that one sampled SOD hashes no DG15. The README rows do not list Active Authentication for them either, and gmrtd's table is silent, not negative, so that aspect is ASSUMED (not REPORTED), as the CA-absence badge of `au-passport-2016-p` is; the evidence entries say only that the sampled SOD lacks DG15. `sg-passport-pa-2023`, `tw-passport-2024` and `us-passport-2021` stay REPORTED because the README row itself says "Cloneable". No profile's CA absence is badged on a missing DG14 alone.
- A BAC-only profile switched to PACE in the document settings has no PACE suite of its own, so it uses the emulator's default (NIST P-256, AES-128); the chip configuration's adaptation list now says so.
- PACE runs on the profile's curve as it is, Brainpool included; Finnish PACE-CAM uses AES-256 on brainpoolP384r1. The French ID card's PACE-IM suite is not offered: the profile advertises only Generic Mapping, which the row also lists.
- The README's extended-length and LDS-version columns are evidence only: the emulator does not vary them per profile.
- Sample holders, document numbers and validity periods are synthetic, ASSUMED values.

Production change: `EcCurve.NIST_P192` (with `usableForPace`) and its JCA name `secp192r1` in `PassportLdsFiles.kt`, and the adaptation message above in `ChipConfiguration.kt`. `ChipProfilesIntegrationTest.verifiesPlain` now uses Bouncy Castle, because the JDK provider does not know brainpoolP256r1 (Hong Kong's AA). Tests: `ChipProfilesIntegrationTest` 14 and `ChipConfigurationTest` 32 pass, with the `ChipProfileResponseTest` 1, `ChipAuthenticationProtocolTest` 3 and `ApduParserTest` 8. The full JVM suite ran 436 tests with the same 11 baseline failures.

## Retained evidence and research exclusions

[Source manifest](evidence/chip-profiles/manifest.json) records URLs, retrieval date, SHA-256 and locators. Downloaded original author PDFs and official Canadian/Spanish HTML snapshots are cached outside the repository at `/tmp/passportemu-source-cache`; those paths are local research conveniences, not portable repository artifacts. No redistribution licence was established, so complete third-party works are not shipped. The repository retains concise [authored source notes](evidence/chip-profiles/source-notes.md). Australian audit facts were read through the web tool; direct HTTP downloads failed, so its retained artifact is explicitly [authored notes](evidence/chip-profiles/anao-australia-2012-notes.md), not a source PDF.

The [Japanese Ministry of Foreign Affairs' 2016 protection profile](https://www.ipa.go.jp/en/security/jisec/pps/certified-cert/c0499_it5574.html) and [eTravel Essential for Japan 1.0 security target, 2020, §§3.3–3.4](https://cyber.gouv.fr/sites/default/files/2020/11/anssi-cible-2020_87en.pdf) describe PACE/AA requirements and vendor capabilities. They were not used to claim a deployed national generation or selected personalised suite. Similar generic French ANSSI product certificates were excluded. The 2026-10-12 gmrtd round below adds New Zealand and Asian profiles from the README's samples; none is backed by an issuer source.

## Verification

- PKI resources (`test-pki`, counted 2026-10-12): 184 files now, 97 of them in the last commit and 87 new and uncommitted. `git diff` shows none of the 97 committed files changed except `README.md` and `generate.sh` (so the 95 certificate and key resources are byte-for-byte unchanged), and the 87 new files are the certificates, keys and DER encodings of the new country CAs and Document Signers. The earlier OpenSSL chain/key/encoding verification and generator-rerun check were done for the 2026-09-30 round and were not repeated in this fix-up.
- Common tests added to `ChipConfigurationTest`: generation IDs, direct evidence locators, automatic valid presets, partial-evidence confidence, Belgian AA/DS separation, Canadian AA enabled with an ASSUMED key, omitted DG7, and corrected error interpretation.
- `ChipProfilesIntegrationTest` already enumerates **all profiles** for JMRTD reads/passive authentication, AA and CA. Its error cases now include Spain and the corrected German response; a new test covers the Spanish preset's ASSUMED BAC fallback.
- Debug and release builds passed. The full JVM suite completed 412 tests: 401 passed and 11 failed in the existing baseline (date/validation expectations, obsolete strings, signed SCUBA constants, JVM NFC availability, and timing/memory tests).
- Latest profile/protocol runs (2026-10-12, `--rerun`): `ChipProfilesIntegrationTest` 14, `ChipConfigurationTest` 32, `ChipProfileResponseTest` 1, `ChipAuthenticationProtocolTest` 3 and `ApduParserTest` 8, all passing. Earlier in the same section: `EventLogFormatterTest` 7. The response regression distinguishes malformed lengths from the measured 40-byte Spanish request.
- The standard-library PKI analyzer's four tests passed. Explicit curve recognition compares the full domain tuple, not only the field prime; standalone authorities are classified using basicConstraints rather than assumed to be Document Signers.

Repeat the profile checks with `./gradlew :composeApp:testDebugUnitTest --tests '*ChipConfigurationTest' --tests '*ChipProfilesIntegrationTest' --tests '*ChipProfileResponseTest'`. These verify emulator behaviour against JMRTD, not issuer fidelity. No physical two-phone NFC read was performed; Android HCE cannot reproduce a passport's UID, ATS or radio hardware.

## Profiles removed and PACE mappings completed (2026-10-16)

The emulator must behave like the documents its profiles name, so eleven profiles whose access control and PACE suite were both ASSUMED were removed: `de-passport-2005`, `nl-passport-2006`, `fr-passport-2008-study`, `it-passport-2008-study`, `es-passport-2008-study`, `be-passport-2006-study`, `ch-passport-2007-study`, `ca-passport-2013`, `au-passport-2005-m`, `au-passport-2009-n` and `es-passport-third-generation`. Their sources left the manifest with them. A document saved with one of their ids opens on the generic profile, as `ChipProfiles.byId` already does for an unknown id. No remaining profile has a sourced error response, so every chip answers EXTERNAL AUTHENTICATE out of sequence with the default `6985`.

The exact-cryptography setting was removed too: every chip runs its profile's PACE suite as it is, and Brainpool curves are no longer swapped for NIST ones.

Where the source lists two PACE mappings, EF.CardAccess now offers both on the same domain parameters and cipher (`ChipProfile.otherPaceMappings`): PACE-GM and PACE-CAM for `de-passport-2023`, `de-id-card`, `fi-id-card-2023` and `fi-passport-2024`, and PACE-GM and PACE-IM for `fr-id-card-2024`. EF.CardAccess lists them in that order, the profile's primary mapping first: the emulator writes the SET OF unsorted (`DLSet`), and the order on the real documents is not established. A reader that takes the first suite it supports picks the primary one. `ChipProfilesIntegrationTest` unlocks each of these chips with each suite through JMRTD.
