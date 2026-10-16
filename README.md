# PassportEmu

PassportEmu emulates passport and ID card chips on Android for testing document readers.
This is a Kotlin Multiplatform project targeting Android.

The documents use test certificates whose subjects and resource names retain the legacy SmartEmu branding.
Existing test CSCA trust anchors, app identity (`com.hddev.smartemu`), saved preferences and exported JSON fields
stay compatible. New text reports and export filenames use PassportEmu; the legacy `SmartEmuEvents` Logcat tag
remains available to existing tools.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Settings and developer mode

The app opens with guided screens for people who aren't developers: the emulated passport, presented through an
illustrated walkthrough in plain words. The gear icon opens **Settings**, the same in both modes:

| Setting | What it does |
|---|---|
| Appearance | Light, dark, or as the phone is set |
| Keep the screen on | Keeps the phone from locking while the chip is on (default on) |
| Keep a history of reads | Records each reader session; turning it off clears the history (default on) |
| Developer mode | Swaps the guided screens for the developer ones: every chip setting, the chip profiles in detail, and each command the reader sends (default off) |

Settings are kept in `SharedPreferences` (`AndroidSettingsStore`). The guided UI lives in
[`ui/guided`](./composeApp/src/commonMain/kotlin/com/hddev/smartemu/ui/guided), the developer one in
[`ui/screens`](./composeApp/src/commonMain/kotlin/com/hddev/smartemu/ui/screens); `SmartEmuApp` in `App.kt` picks
one from the settings.

### What the emulated chip can be

Both modes offer the same chip; developer mode names things in ICAO 9303 terms, the guided screens in plain words.

| Feature | What it does | Where |
|---|---|---|
| Chip profiles | 29 configurations: three generic ones and 26 documents from 19 countries and territories, each backed by a published source for at least its access control or PACE suite. Published claims and assumed emulator choices are shown separately. Each sets the access control, PACE curve and cipher, Active Authentication key, Chip and Terminal Authentication, data groups, Document Signer, MRZ conventions and one error response | Developer: Chip > Change profile, searchable and filterable by protocol and curve, with ⓘ for a profile's details. Emulate shows the chip being emulated and its cryptography. Guided: Choose a ready-made one > By country and generation |
| Presets | Ready-made documents for common and awkward cases: expired, a child's, long or accented names, sex X, a stateless holder, look-alike characters, each access control, every fault below, and a specimen for each country profile | Developer: Passport > Quick start > Load a preset (or Sample data). Guided: Choose a ready-made one / Try another |
| ID cards and residence permits | TD1 documents (codes `ID` and `IR`, or a profile's own, such as the Dutch `I`): a three-line, 30-character MRZ in DG1, drawn on the back of the card, and signed by the ID Document Signer | Document type, on the passport form |
| Active Authentication | DG15 and INTERNAL AUTHENTICATE, under secure messaging only: ECDSA with a plain signature (and DG14 naming the algorithm), or RSA with an ISO 9796-2 SHA-1 signature, as the profile has it | Developer: Chip > Active Authentication. Guided: More options > Anti-copy check, which also turns on PACE-CAM |
| Chip Authentication | EAC-CA version 1 with the ECDH key in DG14: MSE:Set AT and GENERAL AUTHENTICATE with AES, or MSE:Set KAT with 3DES, under secure messaging; secure messaging then restarts with the new keys | Chip profiles that have it |
| Terminal Authentication | DG3 with no fingerprints, readable only after Terminal Authentication, and EF.CVCA naming a test CVCA. No terminal certificate chains to it, so the chip refuses Terminal Authentication and DG3 stays locked (`6982`), as a real one does for a terminal without the issuer's authorisation | Chip profiles that have it |
| DG11 and DG12 | Full name, personal number and place of birth; issuing authority and date of issue. The personal number goes in the MRZ where the profile says so | Developer: Passport > More details |
| Fault injection | A chip that fails exactly one check: altered DG1 or DG2 hashes, an invalid SOD signature, an expired or untrusted Document Signer, or a cloned chip whose Active Authentication key doesn't match DG15 | Developer: Chip > Fault injection. Guided: More options > Make it a fake |
| Read history | Each reader session, kept between runs: how it ended, the protocol that unlocked the chip, the files read, and whether it asked for Active Authentication. Shareable as text | Developer: Emulate > Read history. Guided: the history icon on the home screen |
| Terms and abbreviations | Every abbreviation and term of art on the developer screens (61 in all, from PACE and MRZ to CSCA, DG14 and BP-256) has a dotted underline; tapping it, or a tag, explains what it stands for and means, with related terms. The full glossary is searchable | Developer: the book icon in the top bar, or Settings > Terms and abbreviations |

#### How far the profiles can be trusted

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

#### Cryptography

The chip always runs its profile's cryptography as it is, Brainpool curves and Integrated Mapping included, so a
reader meets what the real document would offer it. Only the developer settings for PACE domain parameters, cipher
and extra suites change it, and ICAO 9303 rules (Chip Authentication Mapping with ECDH and AES only, Integrated
Mapping only on a curve whose prime is 3 mod 4) apply to those choices; the event log says when a chip differs from
its profile.

### Logo and mascot

The PassportEmu mark is an emu holding a passport. The same graphic appears on the launcher and in the animated
welcome screen. [Artwork sources and export instructions](./composeApp/branding/README.md) include the transparent
logo, a one-color SVG, the generation prompt, and a macOS script to regenerate the Android icon sizes.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
