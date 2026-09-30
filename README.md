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
| Exact cryptography | Developer mode only, and on by default there: the chip uses its profile's cryptography as it is. Off, or out of developer mode, the chip adapts it so that apps built on the r2w nfc-library can read it (see below) |

Settings are kept in `SharedPreferences` (`AndroidSettingsStore`). The guided UI lives in
[`ui/guided`](./composeApp/src/commonMain/kotlin/com/hddev/smartemu/ui/guided), the developer one in
[`ui/screens`](./composeApp/src/commonMain/kotlin/com/hddev/smartemu/ui/screens); `SmartEmuApp` in `App.kt` picks
one from the settings.

### What the emulated chip can be

Both modes offer the same chip; developer mode names things in ICAO 9303 terms, the guided screens in plain words.

| Feature | What it does | Where |
|---|---|---|
| Chip profiles | 20 generation-specific and generic configurations, including France, Belgium, Spain, Italy, Switzerland, Canada and Australia alongside the existing EU, German, Dutch, UK and US presets. Published claims and assumed emulator choices are shown separately. Each sets the access control, PACE curve and cipher, Active Authentication key, Chip and Terminal Authentication, data groups, Document Signer, MRZ conventions and one error response | Developer: Chip > Chip profile. Guided: Choose a ready-made one > By country and generation |
| Presets | Ready-made documents for common and awkward cases: expired, a child's, long or accented names, sex X, a stateless holder, look-alike characters, each access control, every fault below, and a specimen for each country profile | Developer: ⋮ > Load a preset. Guided: Choose a ready-made one / Try another |
| ID cards and residence permits | TD1 documents (codes `ID` and `IR`, or a profile's own, such as the Dutch `I`): a three-line, 30-character MRZ in DG1, drawn on the back of the card, and signed by the ID Document Signer | Document type, on the passport form |
| Active Authentication | DG15 and INTERNAL AUTHENTICATE, under secure messaging only: ECDSA with a plain signature (and DG14 naming the algorithm), or RSA with an ISO 9796-2 SHA-1 signature, as the profile has it | Developer: Chip > Active Authentication. Guided: More options > Anti-copy check, which also turns on PACE-CAM |
| Chip Authentication | EAC-CA version 1 with the ECDH key in DG14: MSE:Set AT and GENERAL AUTHENTICATE with AES, or MSE:Set KAT with 3DES, under secure messaging; secure messaging then restarts with the new keys | Chip profiles that have it |
| Terminal Authentication | DG3 with no fingerprints, readable only after Terminal Authentication, and EF.CVCA naming a test CVCA. No terminal certificate chains to it, so the chip refuses Terminal Authentication and DG3 stays locked (`6982`), as a real one does for a terminal without the issuer's authorisation | Chip profiles that have it |
| DG11 and DG12 | Full name, personal number and place of birth; issuing authority and date of issue. The personal number goes in the MRZ where the profile says so | Developer: Passport > More details |
| Fault injection | A chip that fails exactly one check: altered DG1 or DG2 hashes, an invalid SOD signature, an expired or untrusted Document Signer, or a cloned chip whose Active Authentication key doesn't match DG15 | Developer: Chip > Fault injection. Guided: More options > Make it a fake |
| Read history | Each reader session, kept between runs: how it ended, the protocol that unlocked the chip, the files read, and whether it asked for Active Authentication. Shareable as text | Developer: Emulate > Read history. Guided: the history icon on the home screen |

#### How far the profiles can be trusted

[Profile evidence and generation boundaries](./docs/chip-profile-evidence.md) records the primary sources and known limits. Each part of a profile is marked with where its facts come from, shown under Chip > What the profile does:
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

#### Adapted cryptography

Out of developer mode, or with exact cryptography off, the chip runs PACE on the NIST curve of the same strength
where the profile has a Brainpool curve (brainpoolP256r1 becomes P-256, brainpoolP384r1 P-384, brainpoolP512r1
P-521), because the r2w nfc-library supports NIST curves only. The event log says so when a chip is adapted. The
Document Signer, Active Authentication and Chip Authentication keep the profile's curves either way, as the library
doesn't verify them.

### Logo and mascot

The PassportEmu mark is an emu holding a passport. The same graphic appears on the launcher and in the animated
welcome screen. [Artwork sources and export instructions](./composeApp/branding/README.md) include the transparent
logo, a one-color SVG, the generation prompt, and a macOS script to regenerate the Android icon sizes.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
