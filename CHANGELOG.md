# Changelog

All notable changes to PassportEmu are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - Unreleased

First public release. The version name is `1.0.0` and the version code is `1`.

### Added

- An Android app (Android 7.0, API 24, or later; a phone with NFC host card emulation) that emulates an eMRTD chip to
  test document readers and verification software:
  - 29 chip profiles: three generic ones and 26 documents from 19 countries and territories, each backed by a published
    source for at least its access control or PACE suite, with published claims and assumed emulator choices shown
    separately.
  - Access control with BAC or PACE (Generic, Integrated and Chip Authentication Mapping, on NIST and Brainpool curves
    and Diffie-Hellman groups), secure messaging, Active Authentication, Chip Authentication, and Terminal
    Authentication with DG3 locked.
  - Passports, ID cards and residence permits (TD1), the DG11 and DG12 data groups, and ready-made presets for
    awkward cases.
  - Passive authentication data signed by a public test PKI (`test-pki/`), with a test CSCA and Document Signer per
    country profile.
  - Fault injection: altered DG1 or DG2 hashes, an invalid SOD signature, an expired or untrusted Document Signer, and
    a cloned chip.
  - A history of reader sessions, and a searchable glossary of terms and abbreviations.
  - Guided screens for non-developers and an advanced mode with every chip setting.
- Capture mode, which reads a real passport or ID card and saves a redacted record of how the chip behaves, used to
  build measured profiles. It is compiled in only with `-Ppassportemu.captureMode=true` and is never in published
  APKs. The tooling around it: probe packs, a redaction lint, the capture-intake script, profile merging, the fidelity
  diff, the coverage matrix and the reader compatibility matrix.
- A first-run notice, and Settings > About with the intended-use disclaimer and an "Open-source licences" view of
  `THIRD_PARTY_LICENSES.md`.
- README, `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue templates, default and profile pull request
  templates, `CODEOWNERS`, `THIRD_PARTY_LICENSES.md` and the licence text of the bundled OCR-B font (`licenses/OCR-B.txt`).
- Licence: GPL-3.0-or-later.

### Changed

- The app is named PassportEmu everywhere. The package, namespace and application id are `com.hddev.passportemu`, and
  the Logcat tag is `PassportEmuEvents`. The test PKI certificate subjects and file names keep the legacy `SmartEmu`
  name on purpose, so existing trust anchors keep working.
- "Make it a fake" is renamed "Simulate a failing check".
- Capture-mode documentation is rewritten for a public project, and `docs/capture-mode-plan.md` moved to
  `docs/design/` as a historical design record. The profile steward is the maintainer, `hdsysdev`.
- `assembleRelease` works on a fresh clone and produces an unsigned APK when no signing properties are set. Signing
  uses `passportemu.signing.*` entries in `local.properties`.

### Security and privacy

- Capture mode is off in normal builds.
- Release builds strip `Log.d` and `Log.v`, so APDUs and the CAN are not logged.
- Passport form data and the read history are excluded from cloud backup and device transfer.
- Entered data never leaves the phone unless the user shares a file. The app does not request the INTERNET
  permission.

### Fixed

- Loading a Document Signer no longer crashes on Android 7.0 and 7.1.

[Unreleased]: https://github.com/hdsysdev/PassportEmu/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/hdsysdev/PassportEmu/releases/tag/v1.0.0
