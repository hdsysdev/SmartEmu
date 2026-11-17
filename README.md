# PassportEmu

<img src="./composeApp/branding/passportemu-icon-preview.png" alt="The PassportEmu logo: an emu holding a passport" width="160">

PassportEmu turns an Android phone into an emulated eMRTD (electronic passport or ID card chip), so you can test
NFC document readers and verification software without a real document. It answers a reader the way the chip of the
chosen profile would: access control (BAC or PACE), secure messaging, the data groups, Active, Chip and Terminal
Authentication where the profile has them, and a signed EF.SOD for passive authentication. You choose the holder
data, and you can make the chip fail exactly one check on purpose, to see how a reader copes.

## Intended use and disclaimer

**PassportEmu is a test tool.** Use it to test readers, apps and verification software that you own or are
authorised to test, to study ICAO 9303 and the behaviour of the protocols, and to teach.

- **It cannot produce a document that passes real verification.** Every emulated document is signed by the project's
  own test PKI (see [`test-pki/`](./test-pki/README.md)). Its private keys are in this repository, and its CSCA
  certificates are not issued by or linked to any state. A verifier that checks passive authentication against real
  CSCA certificates rejects every document this app emulates.
- **Do not present an emulated document as a genuine one.** Using it to deceive a border officer, an employer, a bank,
  an identity-verification service or anyone else may be a criminal offence where you are. Do not use it to test or
  attack a system you have no permission to test.
- **Never trust the test CSCA in production.** Install it only in test and development verifiers (see
  [Quick start](#quick-start)), never in a production trust store or master list.
- **No warranty.** PassportEmu is free software under the GNU General Public License, version 3 or later, and is
  provided as is, without warranty of any kind, and without any liability for what you do with it. The profiles are
  built from public sources and measurements, and some of what they claim is assumed; see
  [How far the profiles can be trusted](#how-far-the-profiles-can-be-trusted).

The app shows this notice on first run and under Settings > About.

## Install

Download the APK from the [GitHub Releases](https://github.com/hdsysdev/PassportEmu/releases) page, check its
SHA-256 against the value in the release notes, and install it (Android asks you to allow installs from the app you
open it with). Releases are the only official distribution channel at the moment.

Every official APK is signed with the same key. Its certificate is `CN=PassportEmu, O=hdsysdev`, with this SHA-256
fingerprint:

```
79:73:16:FF:45:D3:09:E9:22:4A:B3:6B:E6:D6:83:FF:50:E6:A8:2E:02:21:20:96:A4:38:2A:E1:78:46:E7:0A
```

Check it with `apksigner verify --print-certs PassportEmu-1.0.0.apk` (from the Android SDK build tools), or
`keytool -printcert -jarfile PassportEmu-1.0.0.apk`. An APK signed with any other key is not from this project.

Requirements:

- Android 7.0 (API 24) or later.
- A phone with NFC and **host card emulation** (HCE). Most phones with NFC have it; a few budget models do not.
- NFC switched on, and a reader or app to test against.

To build it yourself, see [Building](#building).

## Quick start

1. Install and open PassportEmu. Read the notice.
2. Pick a chip profile: in the guided screens, **Choose a ready-made one**; in advanced mode, **Chip > Change profile**.
   Optionally load a preset document, or change the holder data and photo.
3. Turn on NFC and hold the back of the phone to the reader. Keep the screen on. The app shows the read as it happens
   and, if you keep a history, records it.
4. To check passive authentication in your verifier, install the matching **test CSCA** there first. The generic
   one is [`test-pki/csca/passportemu-test-csca.cert.pem`](./test-pki/csca/passportemu-test-csca.cert.pem); each country
   profile has its own in the same folder. Add it to the verifier's CSCA trust store or test master list. The Document
   Signer certificate travels inside each EF.SOD, so it needs no installation. Check a chain with
   `openssl verify -CAfile csca/passportemu-test-csca.cert.pem document-signer/passportemu-test-ds.cert.pem`
   from inside `test-pki/`. Details and fingerprints are in the
   [test PKI README](./test-pki/README.md).
5. Expect a verifier set up this way to accept a normal document and to reject the injected faults (an altered hash,
   a bad SOD signature, an expired or untrusted Document Signer, a cloned chip).

## Screens, settings and advanced mode

The app opens with guided screens for people who are not developers: the emulated passport, presented through an
illustrated walkthrough in plain words. The gear icon opens **Settings**, the same in both modes:

| Setting | What it does |
|---|---|
| Appearance | Light, dark, or as the phone is set |
| Keep the screen on | Keeps the phone from locking while the chip is on (default on) |
| Keep a history of reads | Records each reader session; turning it off clears the history (default on) |
| About | The intended-use notice and disclaimer, and the open-source licences of everything the app contains |
| Advanced mode | Swaps the guided screens for the advanced ones: every chip setting, the chip profiles in detail, and each command the reader sends (default off) |

Settings are kept in `SharedPreferences` (`AndroidSettingsStore`). The guided UI lives in
[`ui/guided`](./composeApp/src/commonMain/kotlin/com/hddev/passportemu/ui/guided), the advanced one in
[`ui/screens`](./composeApp/src/commonMain/kotlin/com/hddev/passportemu/ui/screens); `PassportEmuApp` in `App.kt` picks
one from the settings.

## What the emulated chip can be

Both modes offer the same chip; advanced mode names things in ICAO 9303 terms, the guided screens in plain words.

| Feature | What it does | Where |
|---|---|---|
| Chip profiles | 29 configurations: three generic ones and 26 documents from 19 countries and territories, each backed by a published source for at least its access control or PACE suite. Published claims and assumed emulator choices are shown separately. Each sets the access control, PACE curve and cipher, Active Authentication key, Chip and Terminal Authentication, data groups, Document Signer, MRZ conventions and one error response | Advanced: Chip > Change profile, searchable and filterable by protocol and curve, with ⓘ for a profile's details. Emulate shows the chip being emulated and its cryptography. Guided: Choose a ready-made one > By country and generation |
| Presets | Ready-made documents for common and awkward cases: expired, a child's, long or accented names, sex X, a stateless holder, look-alike characters, each access control, every fault below, and a specimen for each country profile | Advanced: Passport > Quick start > Load a preset (or Sample data). Guided: Choose a ready-made one / Try another |
| ID cards and residence permits | TD1 documents (codes `ID` and `IR`, or a profile's own, such as the Dutch `I`): a three-line, 30-character MRZ in DG1, drawn on the back of the card, and signed by the ID Document Signer | Document type, on the passport form |
| Active Authentication | DG15 and INTERNAL AUTHENTICATE, under secure messaging only: ECDSA with a plain signature (and DG14 naming the algorithm), or RSA with an ISO 9796-2 SHA-1 signature, as the profile has it | Advanced: Chip > Active Authentication. Guided: More options > Anti-copy check, which also turns on PACE-CAM |
| Chip Authentication | EAC-CA version 1 with the ECDH key in DG14: MSE:Set AT and GENERAL AUTHENTICATE with AES, or MSE:Set KAT with 3DES, under secure messaging; secure messaging then restarts with the new keys | Chip profiles that have it |
| Terminal Authentication | DG3 with no fingerprints, readable only after Terminal Authentication, and EF.CVCA naming a test CVCA. No terminal certificate chains to it, so the chip refuses Terminal Authentication and DG3 stays locked (`6982`), as a real one does for a terminal without the issuer's authorisation | Chip profiles that have it |
| DG11 and DG12 | Full name, personal number and place of birth; issuing authority and date of issue. The personal number goes in the MRZ where the profile says so | Advanced: Passport > More details |
| Fault injection | A chip that fails exactly one check: altered DG1 or DG2 hashes, an invalid SOD signature, an expired or untrusted Document Signer, or a cloned chip whose Active Authentication key doesn't match DG15 | Advanced: Chip > Fault injection. Guided: More options > Simulate a failing check |
| Read history | Each reader session, kept between runs: how it ended, the protocol that unlocked the chip, the files read, and whether it asked for Active Authentication. Shareable as text | Advanced: Emulate > Read history. Guided: the history icon on the home screen |
| Terms and abbreviations | Every abbreviation and term of art on the advanced screens (61 in all, from PACE and MRZ to CSCA, DG14 and BP-256) has a dotted underline; tapping it, or a tag, explains what it stands for and means, with related terms. The full glossary is searchable | Advanced: the book icon in the top bar, or Settings > Terms and abbreviations |

### How far the profiles can be trusted

[Profile evidence and generation boundaries](./docs/chip-profile-evidence.md) records the primary sources and known limits. Each part of a profile is marked with where its facts come from, shown under Chip > What the profile does (and in full, with the evidence, under Details):
**Specification** (required by ICAO 9303, BSI TR-03110 or EU regulation 2252/2004 for that document),
**Reported** (in public sources, not checked against a real chip) or **Assumed** (a common choice standing in
until someone checks). Much of what varies between issuers, such as the Document Signer's key and the exact chip
answers, is Assumed. [`tools/pkd_profile.py`](./tools/pkd_profile.py) extracts key types and certificate signature
algorithms from local ICAO PKD downloads or published master lists, by country and year. A CSCA key does not
establish the Document Signer's key or any chip protocol, and a certificate's signature algorithm does not
establish the signature algorithm in EF.SOD. The tool extracts metadata; it does not verify certificate or
master-list signatures. See the script for the download terms and `python3 -m unittest discover -s tools/tests`
for its focused parser tests.

A phone can't copy everything about a chip: Android's HCE fixes the ATS, the UID and the NFC type, so readers that
tell chips apart by those still see a phone.

### Cryptography

The chip always runs its profile's cryptography as it is, Brainpool curves and Integrated Mapping included, so a
reader meets what the real document would offer it. Only the advanced settings for PACE domain parameters, cipher
and extra suites change it, and ICAO 9303 rules (Chip Authentication Mapping with ECDH and AES only, Integrated
Mapping only on a curve whose prime is 3 mod 4) apply to those choices; the event log says when a chip differs from
its profile.

## Limits

- **A phone is not a chip.** Android's host card emulation fixes the ATS, the UID and the NFC type, so a reader that
  tells chips apart by those still sees a phone. Link-layer behaviour before SELECT is not emulated, and the profiles
  never claim it.
- **The chip answers on the lock screen.** The service is declared with `requireDeviceUnlock="false"`
  ([`passport_aid_list.xml`](./composeApp/src/androidMain/res/xml/passport_aid_list.xml)), so the emulated chip
  responds to a reader while the phone is locked. This is intended for a test tool. Remember it if you load personal
  details into the form.
- **Test signatures only.** See the disclaimer above: documents verify against the test CSCAs, not against real ones.
- **Not every profile fact is measured.** Many parts of a profile are published claims or assumptions; the evidence
  is shown per field in the app and in [`docs/chip-profile-evidence.md`](./docs/chip-profile-evidence.md).
- The app does not generate RSASSA-PSS signatures or China's `id-data` content type; profiles for documents that use
  them sign PKCS#1 v1.5 and `ldsSecurityObject` instead.

## Privacy

The data you enter (holder details, photo, preset choices) and the read history stay on the phone. The app has no
account and no analytics, and it does not request the Android INTERNET permission. Entered data and the history are
excluded from Android cloud backup and device-to-device transfer, and release builds strip debug logging. Data only
leaves the phone if you share a file yourself, such as a read-history export. Because the phone is a test device, do
not enter a real person's real identity details.

## Capture mode (developer builds only)

Capture mode reads a **real** passport or ID card with the phone and saves a redacted record of how the chip behaves,
which is how measured profiles are made. It is not in published APKs. It is compiled in only when you build with
`-Ppassportemu.captureMode=true`, and it is meant for people who capture their own document, or one whose holder has
agreed in writing. Start with the [capture guide](./docs/capture-guide.md) and the
[privacy notice for capture mode](./docs/capture-consent-and-retention.md). Merged profiles live in
[`profiles/`](./profiles/README.md).

## Building

Requirements: JDK 17, and the Android SDK with platform 36 (`compileSdk`). Android is the only target.

```shell
./gradlew :composeApp:assembleDebug      # debug APK in composeApp/build/outputs/apk/debug/
./gradlew :composeApp:assembleRelease    # release APK, unsigned unless signing is configured
```

On Windows use `.\gradlew.bat`.

**Signing a release.** With no signing properties, `assembleRelease` works and produces an *unsigned* APK, which
Android will not install until you sign it. To sign it during the build, add these to `local.properties` (which is
git-ignored) and keep the keystore in the git-ignored `keystore/` folder:

```properties
passportemu.signing.storeFile=keystore/release.jks
passportemu.signing.storePassword=...
passportemu.signing.keyAlias=...
passportemu.signing.keyPassword=...
```

**Capture mode** is a separate build option:

```shell
./gradlew :composeApp:assembleDebug -Ppassportemu.captureMode=true
```

**Checks:**

```shell
./gradlew :composeApp:testDebugUnitTest          # unit and integration tests, on the JVM
./gradlew :composeApp:lintDebug                  # Android lint
python3 -m unittest discover -s tools/tests      # Python tool tests
```

## Project layout

- [`/composeApp`](./composeApp/src) holds the app.
  - [`commonMain`](./composeApp/src/commonMain/kotlin) is the UI and chip configuration, kept free of Android APIs.
  - [`androidMain`](./composeApp/src/androidMain/kotlin) is the HCE service and the chip protocols (and, in developer
    builds, capture mode).
- [`/test-pki`](./test-pki/README.md): the test certificates and keys.
- [`/profiles`](./profiles/README.md): merged, redacted measured chip profiles.
- [`/docs`](./docs): evidence, schemas, probe packs, glossary and capture documentation.
- [`/tools`](./tools): the ICAO PKD metadata extractor and the capture-intake tooling.

### Logo and mascot

The PassportEmu mark is an emu holding a passport. The same graphic appears on the launcher and in the animated
welcome screen. [Artwork sources, provenance and export instructions](./composeApp/branding/README.md) include the
transparent logo, a one-color SVG, the generation prompt, and a macOS script to regenerate the Android icon sizes.

## Contributing and security

- Read [CONTRIBUTING.md](./CONTRIBUTING.md) before opening an issue or pull request. Never attach a real MRZ, photo
  or chip dump.
- Report a vulnerability privately, as described in [SECURITY.md](./SECURITY.md).
- Everyone taking part is expected to follow the [Code of Conduct](./CODE_OF_CONDUCT.md).
- Changes are listed in the [changelog](./CHANGELOG.md).

## Licence

Copyright (C) 2025-2026 hdsysdev and PassportEmu contributors.

PassportEmu is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](./LICENSE) file for the full text.

The app includes third-party software and a font under their own licences, listed in
[THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md) and shown in the app under Settings > About > Open-source
licences. The logo is covered by [its own note](./composeApp/branding/README.md).

## Compatibility notes

PassportEmu was developed under the working name SmartEmu and renamed before its first release. Nothing public
carries the old name: the app identity is `com.hddev.passportemu`, the Logcat tag is `PassportEmuEvents`, and the
test PKI was re-issued as `passportemu-test-*` with `O=PassportEmu` subjects. A test CSCA from a pre-release build
does not verify documents from this one; install the current one from [`test-pki/`](./test-pki/README.md).
