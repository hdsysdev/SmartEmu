# Reader compatibility matrix

Which open-source readers can complete which profiles. It tests the emulator against readers the team already uses, in the JVM. It is not a claim about real documents. The matrix at the end of this page is **generated**; everything above it is hand-written.

## Readers

| Reader | Version | Status | Notes |
|---|---|---|---|
| JMRTD with SCUBA and Bouncy Castle | JMRTD 0.8.8, `scuba-sc-android` 0.0.21, Bouncy Castle 1.85 (as in `gradle/libs.versions.toml`) | Runs in CI (`ReaderCompatibilityTest`) | Integrated Mapping needs Bouncy Castle as the first JCE provider. JMRTD's `CardAccessFile` stores SecurityInfos in a HashSet, so file order is lost; the harness uses the ordered ASN.1 reader. |
| r2w nfc-library (internal, not in this repo) | _TBD_ | **To do.** No results: it lives outside this repo and has not been run here | Supports PACE GM and CAM on ECDH. Does **not** support Integrated Mapping or DH. Curves: NIST P-256/384/521 and brainpoolP192r1 to P512r1. A good second reader to check profiles against. Its column in the matrix is `not run` for every profile until somebody runs it and records the results; nothing is assumed from its documentation. |

## How it runs

`ReaderCompatibilityTest` (`composeApp/src/androidUnitTest/.../capture/readers/`) drives the emulator (`PassportHceService`, through the `HceCardService` adapter the integration tests use) with the reader, for every profile in `ChipProfiles.all` and every measured profile in `profiles/`:

1. read EF.CardAccess for the PACE suites on offer;
2. each offered PACE suite, in order, with the MRZ as the password (Integrated Mapping with Bouncy Castle first in the provider list), then DG1;
3. BAC, if the profile offers it;
4. with the first access method that works: EF.COM, EF.SOD, DG1, passive authentication against the test CSCA (certificate chain, signature, data group hashes), Chip Authentication if the profile has it, Active Authentication if the profile has it.

A second table runs PACE on every domain parameter, mapping and cipher the developer settings can select, to find where a reader's limits are.

## Baseline and regressions

`docs/reader-compatibility.json` pins the result. The test fails if a step that passed there no longer passes, if a pinned profile or variant was not run, if a failure appears that the baseline doesn't record, or if a profile is not in the baseline. An improvement does not fail the test, but the page then differs from the committed one and has to be regenerated.

| What | Command |
|---|---|
| Run the matrix | `./gradlew :composeApp:testDebugUnitTest --tests '*ReaderCompatibilityTest*'` |
| Regenerate the page only | `UPDATE_GENERATED_DOCS=1 ./gradlew :composeApp:testDebugUnitTest --tests '*ReaderCompatibilityTest*'` |
| Update the baseline and the page (after triage, on purpose) | `UPDATE_READER_BASELINE=1 ./gradlew :composeApp:testDebugUnitTest --tests '*ReaderCompatibilityTest*'` |

## Labelling rules

- **`reader unsupported: <reason>`** when the reader cannot do something the profile uses and the reader's documentation or code says so. The reason is mandatory and names the feature, for example `reader unsupported: PACE Integrated Mapping with AES-192`. In the harness this is a `ReaderLimit` with a mandatory `source`, and it applies only when it matches **the exception the reader actually threw**: a step that passes is never relabelled, and a failure of another kind stays a `fail`. The exception is printed under the matrix as the evidence.
- It is **not** a failure of the profile and does not lower its fidelity badge. It is not a pass either.
- **`fail: <reason>`** only when the reader claims support and the run breaks. The reason is the exception. Triage to decide whether the profile, the emulator or the reader is wrong. A fail on a MEASURED field blocks a release.
- A reader's unsupported list is kept beside it (`KnownReaderLimits`, rendered in the generated section) and reviewed when the reader updates. A cell is never relabelled from `fail` to `unsupported` to make CI green without a source for the reader's limitation.
- `n/a` means the profile has nothing for that step (no BAC, no Chip Authentication).
- The run is in the JVM; there are no per-cell logs. The exception text is the log.

<!-- BEGIN GENERATED: reader matrix (ReaderMatrix.render; regenerate, do not edit between the markers) -->
<!-- Written by ReaderCompatibilityTest from JMRTD 0.8.8 + SCUBA 0.0.21 + Bouncy Castle 1.85; regenerate with UPDATE_GENERATED_DOCS=1 -->

**GENERATED. Do not edit between the markers.** Reader: JMRTD 0.8.8 + SCUBA 0.0.21 + Bouncy Castle 1.85. Pinned in [reader-compatibility.json](reader-compatibility.json).

### Known limits of this reader

A step is labelled `reader unsupported` only where one of these limits explains the exception the reader actually threw; each names its source. Anything else that breaks is a `fail`.

- **PACE Integrated Mapping with AES-192**. Source: JMRTD 0.8.8 PACEProtocol generates Integrated Mapping's nonce t as long as the encrypted nonce s (32 bytes for AES-192, where ICAO 9303 part 11 wants 24); reproduced by PaceIntegrationTest "JMRTD's integrated mapping with AES-192 is refused for its overlong nonce t", where a reader that follows ICAO 9303 completes the same suite on the same emulator.

### Profiles

Each profile's chip is unlocked with every access method it offers (BAC, and each PACE suite of EF.CardAccess in order, with the MRZ as the password). With the first that works the reader reads EF.COM, EF.SOD and DG1, checks passive authentication against the test CSCA, and runs Chip Authentication and Active Authentication where the profile has them.

| Profile | BAC | PACE suites | EF.COM | EF.SOD | DG1 | Passive auth | Chip Auth | Active Auth | JMRTD+SCUBA+BC total | r2w nfc-library |
|---|---|---|---|---|---|---|---|---|---|---|
| `at-passport-2023` (Austria · passport (2023)) | pass | PACE-GM ECDH brainpoolP256r1 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `au-passport-2016-p` (Australia · passport (P Series, 2016)) | pass | n/a | pass | pass | pass | pass | n/a | pass | pass | not run |
| `ca-passport-2023` (Canada · passport (2023 design)) | pass | PACE-GM ECDH NIST P-384 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `cn-passport-po-2018` (China · passport (README type PO, 2018)) | pass | n/a | pass | pass | pass | pass | n/a | pass | pass | not run |
| `de-id-card` (Germany · ID card (Personalausweis)) | n/a | PACE-CAM ECDH brainpoolP256r1 AES-128: pass<br>PACE-GM ECDH brainpoolP256r1 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `de-passport-2017` (Germany · passport (2017 design)) | pass | PACE-GM ECDH brainpoolP256r1 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `de-passport-2023` (Germany · passport (2023 design)) | n/a | PACE-CAM ECDH brainpoolP256r1 AES-128: pass<br>PACE-GM ECDH brainpoolP256r1 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `eu-id-card` (EU ID card (2021 design)) | pass | PACE-GM ECDH NIST P-256 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `eu-passport` (EU passport (current design)) | pass | PACE-GM ECDH NIST P-256 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `fi-id-card-2023` (Finland · ID card (2023)) | pass | PACE-CAM ECDH brainpoolP384r1 AES-256: pass<br>PACE-GM ECDH brainpoolP384r1 AES-256: pass | pass | pass | pass | pass | n/a | n/a | pass | not run |
| `fi-passport-2024` (Finland · passport (2024)) | pass | PACE-CAM ECDH brainpoolP384r1 AES-256: pass<br>PACE-GM ECDH brainpoolP384r1 AES-256: pass | pass | pass | pass | pass | n/a | n/a | pass | not run |
| `fr-id-card-2024` (France · ID card (2024)) | pass | PACE-GM ECDH brainpoolP256r1 AES-256: pass<br>PACE-IM ECDH brainpoolP256r1 AES-256: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `fr-passport-2017` (France · passport (2017)) | pass | PACE-GM ECDH NIST P-256 AES-256: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `gb-passport` (United Kingdom · passport (2020 design)) | pass | PACE-GM ECDH NIST P-256 AES-256: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `generic` (Generic ICAO chip) | pass | PACE-GM ECDH NIST P-256 AES-128: pass | pass | pass | pass | pass | n/a | n/a | pass | not run |
| `hk-passport-2025` (Hong Kong · passport (2025)) | pass | PACE-GM ECDH brainpoolP256r1 AES-256: pass | pass | pass | pass | pass | n/a | pass | pass | not run |
| `id-passport-2025` (Indonesia · passport (2025)) | pass | PACE-GM ECDH brainpoolP256r1 AES-256: pass | pass | pass | pass | pass | pass | pass | pass | not run |
| `my-passport-2023` (Malaysia · passport (2023)) | pass | n/a | pass | pass | pass | pass | pass | n/a | pass | not run |
| `nl-id-card` (Netherlands · ID card (2014 design)) | pass | PACE-GM ECDH NIST P-256 AES-128: pass | pass | pass | pass | pass | pass | pass | pass | not run |
| `nl-passport` (Netherlands · passport (2014 design)) | pass | PACE-GM ECDH NIST P-256 AES-128: pass | pass | pass | pass | pass | pass | pass | pass | not run |
| `nl-passport-2024` (Netherlands · passport (2024 design)) | n/a | PACE-GM ECDH brainpoolP320r1 AES-256: pass | pass | pass | pass | pass | pass | pass | pass | not run |
| `nz-passport-2017` (New Zealand · passport (2017)) | pass | PACE-GM ECDH brainpoolP256r1 3DES: pass | pass | pass | pass | pass | n/a | pass | pass | not run |
| `ph-passport-2020` (Philippines · passport (2020)) | pass | n/a | pass | pass | pass | pass | n/a | pass | pass | not run |
| `pt-passport-2026` (Portugal · passport (README type PP, 2026)) | pass | PACE-GM ECDH brainpoolP256r1 AES-128: pass | pass | pass | pass | pass | pass | n/a | pass | not run |
| `ru-passport-2020` (Russia · passport (2020)) | pass | n/a | pass | pass | pass | pass | pass | n/a | pass | not run |
| `sg-passport-pa-2023` (Singapore · passport (README type PA, 2023)) | pass | PACE-GM ECDH brainpoolP256r1 AES-256: pass | pass | pass | pass | pass | n/a | n/a | pass | not run |
| `sg-passport-pp-2025` (Singapore · passport (README type PP, 2025)) | pass | PACE-GM ECDH brainpoolP256r1 AES-256: pass | pass | pass | pass | pass | n/a | pass | pass | not run |
| `tw-passport-2024` (Taiwan · passport (2024)) | pass | PACE-GM ECDH NIST P-256 AES-256: pass | pass | pass | pass | pass | n/a | n/a | pass | not run |
| `us-passport-2021` (United States · passport (Next Generation, 2021)) | pass | n/a | pass | pass | pass | pass | n/a | n/a | pass | not run |

### PACE variants the developer settings can ask for

The emulator can be set to any ICAO PACE domain parameters, mapping and cipher. The reader runs PACE with the MRZ on the first suite EF.CardAccess then offers, and reads DG1. `pass` means all four ciphers pass. Where the emulator can't offer the requested combination it offers another suite (Integrated Mapping on NIST P-224, PACE-CAM on a DH group, PACE-CAM with 3DES), and the cell says which: that is the emulator's choice, not the reader's.

| Domain parameters | GM | IM | CAM |
|---|---|---|---|
| NIST P-192 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH NIST P-192 AES-128 |
| brainpoolP192r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP192r1 AES-128 |
| NIST P-224 | pass | 3DES: emulator offers PACE-GM ECDH NIST P-224 3DES<br>AES-128: emulator offers PACE-GM ECDH NIST P-224 AES-128<br>AES-192: emulator offers PACE-GM ECDH NIST P-224 AES-192<br>AES-256: emulator offers PACE-GM ECDH NIST P-224 AES-256 | 3DES: emulator offers PACE-CAM ECDH NIST P-224 AES-128 |
| brainpoolP224r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP224r1 AES-128 |
| NIST P-256 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH NIST P-256 AES-128 |
| brainpoolP256r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP256r1 AES-128 |
| brainpoolP320r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP320r1 AES-128 |
| NIST P-384 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH NIST P-384 AES-128 |
| brainpoolP384r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP384r1 AES-128 |
| brainpoolP512r1 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH brainpoolP512r1 AES-128 |
| NIST P-521 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-CAM ECDH NIST P-521 AES-128 |
| DH 1024/160 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-GM DH DH 1024/160 3DES<br>AES-128: emulator offers PACE-GM DH DH 1024/160 AES-128<br>AES-192: emulator offers PACE-GM DH DH 1024/160 AES-192<br>AES-256: emulator offers PACE-GM DH DH 1024/160 AES-256 |
| DH 2048/224 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-GM DH DH 2048/224 3DES<br>AES-128: emulator offers PACE-GM DH DH 2048/224 AES-128<br>AES-192: emulator offers PACE-GM DH DH 2048/224 AES-192<br>AES-256: emulator offers PACE-GM DH DH 2048/224 AES-256 |
| DH 2048/256 | pass | AES-192: reader unsupported: PACE Integrated Mapping with AES-192 [1] | 3DES: emulator offers PACE-GM DH DH 2048/256 3DES<br>AES-128: emulator offers PACE-GM DH DH 2048/256 AES-128<br>AES-192: emulator offers PACE-GM DH DH 2048/256 AES-192<br>AES-256: emulator offers PACE-GM DH DH 2048/256 AES-256 |

### Evidence for each `reader unsupported`

1. The exception the reader threw: `CardServiceProtocolException: PICC side exception in mapping nonce step (SW = 0x6A80: WRONG DATA or FILEHEADER INCONSISTENT) (step: 2) [SW=6A80] <- CardServiceException: Sending general authenticate failed (SW = 0x6A80: WRONG DATA or FILEHEADER INCONSISTENT)`

<!-- END GENERATED: reader matrix -->
