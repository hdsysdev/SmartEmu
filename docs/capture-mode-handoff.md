# Handoff: SmartEmu capture mode (measured chip profiles)

## Goal
Add a **capture mode** to SmartEmu (`/Users/hubertdudowicz/OtherProjects/SmartEmu`, Android HCE passport emulator, KMP/Compose, branch `master`):
the phone reads a real passport/ID card as a reader would, runs a fixed set of safe probes, and saves a redacted record of
how the chip behaved. Profiles are built from those records and shared across the team. The emulator then replays them,
and a transcript comparison proves the emulation matches the real chip. The user's requirement: "This emulator app needs
to accurately mimic the behaviour of passports it claims in profiles."

## State of the repo before you start
- **Lots of uncommitted work** (the user's own plus the previous session's) is in the working tree: profile cleanup,
  `exactCryptography` removal, `otherPaceMappings`. Run `git status` / `git diff` to see it. **Do not revert it, and do
  not commit unless asked. Never run `git push`** (user rule).
- 29 profiles remain in `composeApp/src/commonMain/kotlin/com/hddev/smartemu/data/ChipProfiles.kt` (Kotlin code, not
  data). The model is in `data/ChipProfile.kt` (`ChipProfile`, `Provenance` = SPECIFICATION/REPORTED/ASSUMED,
  `ProfileEvidence`, `ErrorResponses` with only `outOfSequence`), resolved by `data/ChipConfiguration.kt`.
- Chip files are generated in `androidMain/.../utils/PassportLdsFiles.kt`. EF.CardAccess is written as an unsorted
  `DLSet` in configured order (`cardAccess()`, ~line 370). APDUs are handled in `androidMain/.../PassportHceService.kt`.
  PACE and CA are in `utils/PaceProtocol.kt` and `utils/ChipAuthenticationProtocol.kt`.
- Evidence docs: `docs/chip-profile-evidence.md` and `docs/evidence/chip-profiles/{manifest.json,source-notes.md}`.
- **The app has no reader code.** `NfcAdapter` is only used for availability checks
  (`repository/AndroidNfcSimulatorRepository.kt`, `ui/components/DeviceNfc.android.kt`). JMRTD 0.8.8, SCUBA
  (`scuba-sc-android` 0.0.21) and Bouncy Castle 1.85 are already app dependencies (`gradle/libs.versions.toml`). There is
  no kotlinx.serialization yet.

## Tests
- `./gradlew :composeApp:testDebugUnitTest`. Parse `composeApp/build/test-results/testDebugUnitTest/TEST-*.xml` once
  with a python script; don't grep the HTML.
- **Baseline failures, unrelated (10–11):** PerformanceTest ×3 (timing-flaky), MemoryUsageValidationTest,
  PassportDataTest ×3, SimulatorErrorTest, ScubaBasicTest, DateValidationUtilsTest, PassportSimulatorViewModelTest
  "initial state". Last run: 468 tests, only baseline failures.
- Test harness: `androidUnitTest/.../HceCardService.kt` connects JMRTD's `PassportService` to `PassportHceService` in
  the JVM. This is the place to run probe scripts against the emulator.
- Gotchas learned:
  - JMRTD's `CardAccessFile` stores SecurityInfos in a **HashSet**, so the file order is lost. Use the ordered ASN.1
    reader `readPaceInfos(service)` in `PaceIntegrationTest.kt` / `ChipProfilesIntegrationTest.kt`.
  - JMRTD Integrated Mapping needs Bouncy Castle as the first JCE provider (`withBouncyCastleFirst` in the same tests).
    On a device the same applies; Android's provider order differs.

## The plan (agreed with the user; implement in this order)
1. **Reader mode + APDU logger + capture format + unauthenticated reads.**
   - `NfcAdapter.enableReaderMode` with an `IsoDep` → SCUBA `CardService`, wrapped so every command and response is
     logged with its timing.
   - Record:
     - NFC tech (A or B), ATS / historical bytes, max transceive length, extended-length support.
     - UID random or fixed: a boolean only, never the value.
     - Raw EF.CardAccess (keep its order: PACEInfo, ChipAuthenticationInfo, TA info, versions), EF.ATR/INFO (2F01),
       EF.DIR (2F00).
     - The status words for BAC commands and GET CHALLENGE before authentication.
   - Phone can't emulate and read at the same time: a separate screen/mode.
2. **Authenticated reads with MRZ or CAN, with redaction.**
   - Try every offered PACE suite, plus BAC.
   - Record:
     - EF.COM and the data groups present, with their sizes.
     - From EF.SOD: digest and signature algorithms, LDS version, the Document Signer certificate.
     - DG14/DG15 reduced to algorithms, curves and key sizes.
     - EF.CardSecurity, EF.CVCA, and the DG3/DG4 refusal status word.
     - Whether AA and CA succeed.
3. **Behaviour probe script.** Fixed and versioned, with a status word and timing per step:
   - Out-of-sequence commands, wrong lengths, the largest READ BINARY, odd-INS READ BINARY (B1).
   - SELECT variants, behaviour after a failed PACE.
   - **Exclude** anything that decrements a retry counter (PIN, PUK, repeated CAN failures).
4. **Profiles as data + replay + diff.**
   - kotlinx.serialization JSON profiles in a shared dir/repo, imported and exported through the share sheet.
   - New `Provenance.MEASURED` above SPECIFICATION, with capture IDs and sample count. Merge captures per generation and
     flag fields that disagree.
   - Emulator changes:
     - Replay the captured EF.CardAccess bytes.
     - Replace `ErrorResponses` with a per-command status-word map.
     - Max read length and file sizes.
   - Fidelity diff: run the same probe script against the emulator (JVM via `HceCardService`, or on a second phone).
     Compare exactly, except randomised or cryptographic fields, which are compared by structure. Only a clean diff
     earns MEASURED.

## Redaction rules (must happen on the phone, before saving)
- Never store DG1, DG2, DG7, DG11 or DG12 contents, the MRZ, or the CAN.
- Drop the EF.SOD data-group hash values; the DG1 hash can be brute-forced from the MRZ space.
- Drop the per-chip AA/CA public keys and the UID; each one identifies a single chip.
- The capture lists what was removed. The user should agree a team rule on holder consent.

## Not emulatable on Android HCE; capture it anyway and mark it so
- HCE only offers NFC-A; many passports are NFC-B.
- HCE can't set the UID, ATS or historical bytes.
- The reader must SELECT the eMRTD AID before the phone answers.

## Open decisions to put to the user
- Where shared profiles live: a separate repo, or `profiles/` in SmartEmu, plus the review process.
- Whether a CAN (ID cards) is in scope for phase 2.
- The consent and retention policy for captures.

## Suggested skills
- `mattpocock-skills:grilling`: settle the open decisions before coding.
- `mattpocock-skills:domain-modeling` / `mattpocock-skills:codebase-design`: the capture, profile JSON and probe-script
  model.
- `mattpocock-skills:tdd`: probe steps and redaction are very testable against `HceCardService`.
- `engineering:testing-strategy`: design the fidelity-diff CI check.
- `security-review`: before any capture leaves the phone (PII redaction).
- `code-review` (built-in): at the end of each phase.
