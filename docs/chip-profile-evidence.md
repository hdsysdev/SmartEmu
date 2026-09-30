# PassportEmu chip profile evidence

Research checked on 2026-09-30. Nine passport profiles were added to the existing eleven. The findings below use issuer publications, an official national audit, and the original authors' firsthand experiments. None of the emulator profiles was measured against a physical passport in this task.

## How confidence is represented

`ProfileEvidence` records a specific claim, primary URL, page/section locator, and its scope. The UI exposes these links under **Primary evidence for this generation**. It distinguishes this evidence from the actual emulator configuration.

An aspect remains **ASSUMED** when any material choice in that row is unverified. For example, confirmation of BAC support does not establish PACE's absence; confirmation of AA does not establish its key type. An assumed disabled feature means “disabled in this emulator”, not “absent from real passports”. Changing settings or adapting a curve also changes the corresponding UI badge to ASSUMED. “Exact cryptography” means the configured preset curve, not proof that it matches an issuer's chip.

CSCA certificates and master lists establish PKI authorities. They do **not** establish DS key type, SOD signature/digest/padding, AA keys, CA keys, or PACE suites. No profile algorithm here was derived from a CSCA key. Algorithms permitted by a standard, or supported by a certified vendor product, do not establish national passport personalisation or deployment.

## Added profiles and generation boundaries

| Profile ID | Bounded primary claim | Locator | Emulator limits |
|---|---|---|---|
| `fr-passport-2008-study` | BAC on the historical French study population. | [Richter, Mostowski, Poll (2008)](https://cs.ru.nl/E.Poll/papers/nluug.pdf), §§1–3, pp. 1–5. | Study snapshot, not a claimed introduction year or current French passport. AA/CA/TA disabled, PACE absent, DS and optional files ASSUMED. |
| `it-passport-2008-study` | BAC on the historical Italian study population. | [Same primary paper](https://cs.ru.nl/E.Poll/papers/nluug.pdf), §§1–3, pp. 1–5. | Same scope limitation. No inference from an unsupported response to malformed INTERNAL AUTHENTICATE. |
| `es-passport-2008-study` | BAC; sampled Spanish chip returned `6300` to EXTERNAL AUTHENTICATE with `Lc=40`. | [Same paper](https://cs.ru.nl/E.Poll/papers/nluug.pdf), §3, p. 5, paragraph **below** Table 1. | Restricted to a correctly sized 40-byte request before a challenge. The malformed probe in Table 1 returned `6700`; `6300` must not apply to every nonempty length. Not Spain's later PACE generation. |
| `be-passport-2006-study` | BAC generation began mid-2006; RSA-1024 AA and RSA-2048 DS are separately reported. | [Avoine, Kalach, Quisquater (2008)](https://sites.uclouvain.be/security/download/papers/AvoineKQ-2008-fc.pdf), §3.3, PDF p. 10; Table 2, PDF p. 14. | Excludes pre-mid-2006 no-BAC generation. SHA-1 signature hashing is reported, but SOD RSA padding and separate LDS hash are unresolved. Emulator uses SHA-1/PKCS#1 v1.5 with an ASSUMED LDS hash. §4, PDF p. 11 describes DG7/11/12; DG7 is omitted. |
| `ch-passport-2007-study` | BAC implemented; AA absent on the Swiss sample. | [Vaudenay, Vuagnoux, Eurocrypt 2007](https://www.iacr.org/conferences/eurocrypt2007/slides/rumpt05.pdf), slides 7, 10, 12 of 13. | One Swiss sample; no modern Swiss claim. Unconfirmed “TBC” statements elsewhere in the slides are not evidence. |
| `ca-passport-2013` | 2013-generation issuer documents BAC and AA, and gives RSA-PSS-2048/SHA-256 **examples**. | [Canada technical information (2014)](https://www.canada.ca/en/news/archive/2014/05/technical-information-about-canadian-epassport.html), “Unauthorized reading: Basic access control”, “Cloning: Active authentication”, second paragraph, and “Tampering and inauthenticity: Passive authentication”; [generation history](https://www.canada.ca/en/news/archive/2014/05/history-epassport.html), final paragraph. | July 2013 general rollout; not Canada's redesigned 2023 passport. AA enabled with ASSUMED ECDSA P-256: its key type is not specified. “Such as” does not establish an exclusive DS suite or mandatory encoded algorithm. Emulator uses an ASSUMED RSA-2048/SHA-256 **PKCS#1 v1.5** stand-in because its SOD generator does not support PSS. CA absence and BAC-only are ASSUMED. |
| `au-passport-2005-m` | M Series began 24 October 2005; BAC present; AA absent. | [ANAO, Management of ePassports (2012)](https://www.anao.gov.au/sites/default/files/201112%20Audit%20Report%20No%2033.pdf), summary ¶6, p. 14; ¶5.6, p. 74; ¶5.16, p. 78. | Historical M Series; no extrapolation to later series. DS, optional files, PACE absence and CA absence ASSUMED. |
| `au-passport-2009-n` | N Series replaced M in May 2009; BAC and AA present. | [Same official audit](https://www.anao.gov.au/sites/default/files/201112%20Audit%20Report%20No%2033.pdf), same locators. | Audit establishes AA presence, not its key or scheme. ECDSA P-256 AA is an ASSUMED emulator default. Does not describe P or R Series. |
| `es-passport-third-generation` | Official Spanish police reader documentation identifies third-generation passports using PACE. | [Policía Nacional, DNIe portal](https://www.dnielectronico.es/PortalDNIe/PRF1_Cons02.action?id_menu=20&pag=REF_035), “Ejemplo DNIe Lectura Datos”. | Source supplies no introduction date or passport-specific curve/mapping/cipher. BAC fallback, CA/TA/DG3 and disabled AA are ASSUMED; DNIe card algorithms are not imported into a passport profile. |

All samples are synthetic holders and document numbers, not issuer specimens. Historical presets receive recent issue/expiry dates to exercise readers; their validity settings are defaults, not evidence that old-generation passports remain valid. New profiles include at least one actual chip protocol/feature claim. An R Series profile supported only by a visual design/launch page was discarded.

## Corrections to the existing eleven profiles

- The generic preset's NIST P-256/AES-128/DS choices are now ASSUMED rather than labelled requirements of ICAO. [ICAO Doc 9303](https://www.icao.int/publications/doc-series/doc-9303) is a protocol/format reference, not proof that every compliant chip selects the same suite.
- Coarse legacy aspect badges were conservatively downgraded to ASSUMED where the available citations do not substantiate the complete configured row. This includes German Brainpool DS/PACE/CA sizes, Dutch ECDSA AA, optional files and combined MRZ/validity settings. Their runtime choices are retained; the downgrade does not assert that they are wrong.
- The EU aggregate preset no longer claims that every selected protocol and algorithm is required for every current member-state passport. Jurisdictional requirements are not evidence of exact national suites.
- The German historical preset now returns `6985` for correctly sized EXTERNAL AUTHENTICATE before a challenge. The primary 2008 study reports this below Table 1; `6700` was a different malformed probe. Its applicability to the named 2005 design remains ASSUMED because the paper does not give the German sample's issue date.
- Dutch historical `6982` remains an emulator assumption. In the cited paper, `6982` is the INTERNAL AUTHENTICATE entry, not evidence for the implemented EXTERNAL AUTHENTICATE request. Unsupported or erroneous responses to malformed requests are not proof of AA's absence.
- “Real curve” UI wording was replaced with “preset curve”. Absence is displayed as “disabled in this configuration”. The signer and implementation descriptions say PassportEmu; legacy certificate CNs and resource filenames retain SmartEmu.

## Test PKI and signature limits

Seven new test authorities/signers cover FR, BE, ES, IT, CH, CA, AU. Their new CNs contain **PassportEmu TEST**, with `O=PassportEmu, OU=Test PKI`. Resource basenames remain `smartemu-test-*`. Previously committed subjects, keys and certificates remain unchanged.

All seven new **CSCA** keys are EC P-256 test choices. Belgian and Canadian **DS** keys are RSA-2048; the other new DS keys are EC P-256 stand-ins. The Belgian DS choice follows a separately labelled DS claim in the primary paper, not its CSCA size or Table 3's recommendations. Certificate issuer signatures are SHA-256/ECDSA and must not be confused with the emulated SOD signature. Every signer chains to its matching test authority. No real private key is present.

Additional limits: PassportEmu generates only supported LDS files; it cannot reproduce arbitrary optional data or vendor APDU behaviour. DG3 is a locked emulator placeholder and TA is refused. The reported Spanish status word does not reproduce the paper's complete fingerprint. Unknown attributes remain ASSUMED even if an emulator integration test passes.

## Retained evidence and research exclusions

[Source manifest](evidence/chip-profiles/manifest.json) records URLs, retrieval date, SHA-256 and locators. Downloaded original author PDFs and official Canadian/Spanish HTML snapshots are cached outside the repository at `/tmp/passportemu-source-cache`; those paths are local research conveniences, not portable repository artifacts. No redistribution licence was established, so complete third-party works are not shipped. The repository retains concise [authored source notes](evidence/chip-profiles/source-notes.md). Australian audit facts were read through the web tool; direct HTTP downloads failed, so its retained artifact is explicitly [authored notes](evidence/chip-profiles/anao-australia-2012-notes.md), not a source PDF.

The [Japanese Ministry of Foreign Affairs' 2016 protection profile](https://www.ipa.go.jp/en/security/jisec/pps/certified-cert/c0499_it5574.html) and [eTravel Essential for Japan 1.0 security target, 2020, §§3.3–3.4](https://cyber.gouv.fr/sites/default/files/2020/11/anssi-cible-2020_87en.pdf) describe PACE/AA requirements and vendor capabilities. They were not used to claim a deployed national generation or selected personalised suite. Similar generic French ANSSI product certificates were excluded. No New Zealand/Asian algorithm profile was added without concrete generation evidence.

## Verification

- OpenSSL: all seven new certificate chains verified; all fourteen certificates match their private keys, PEM/DER encodings, intended subjects and key types. All 52 previously committed PKI resources remain byte-for-byte unchanged. Rerunning the generator changed none of the 94 PKI resources.
- Common tests added to `ChipConfigurationTest`: generation IDs, direct evidence locators, automatic valid presets, partial-evidence confidence, Belgian AA/DS separation, Canadian AA enabled with an ASSUMED key, omitted DG7, and corrected error interpretation.
- `ChipProfilesIntegrationTest` already enumerates **all profiles** for JMRTD reads/passive authentication in adapted and exact modes, AA and CA. Its error cases now include Spain and the corrected German response; a new test covers the Spanish preset's ASSUMED BAC fallback.
- Debug and release builds passed. The full JVM suite completed 412 tests: 401 passed and 11 failed in the existing baseline (date/validation expectations, obsolete strings, signed SCUBA constants, JVM NFC availability, and timing/memory tests).
- All profile/protocol/branding suites passed: `ChipProfilesIntegrationTest` 11, `ChipConfigurationTest` 13, `ChipProfileResponseTest` 1, `ChipAuthenticationProtocolTest` 3, and `EventLogFormatterTest` 7. The response regression distinguishes malformed lengths from the measured 40-byte Spanish request.
- The standard-library PKI analyzer's four tests passed. Explicit curve recognition compares the full domain tuple, not only the field prime; standalone authorities are classified using basicConstraints rather than assumed to be Document Signers.

Repeat the profile checks with `./gradlew :composeApp:testDebugUnitTest --tests '*ChipConfigurationTest' --tests '*ChipProfilesIntegrationTest' --tests '*ChipProfileResponseTest'`. These verify emulator behaviour against JMRTD, not issuer fidelity. No physical two-phone NFC read was performed; Android HCE cannot reproduce a passport's UID, ATS or radio hardware.
