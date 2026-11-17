# Contributing to PassportEmu

Thank you for helping. PassportEmu is a test tool for NFC document readers; read the
[intended use and disclaimer](./README.md#intended-use-and-disclaimer) first. By taking part you agree to follow the
[Code of Conduct](./CODE_OF_CONDUCT.md). Security problems go through [SECURITY.md](./SECURITY.md), not public issues.

## No real document data, ever

This is the one hard rule. In an issue, a pull request, a comment, a test, a fixture or a screenshot, **do not
include**:

- an MRZ, a document number, a personal number, a CAN, a name or a date of birth taken from a real document;
- a photo of a real person, or a real passport or ID card image;
- a raw chip dump, an APDU log or an EF.SOD from a real document (they carry personal data and per-chip values);
- a chip UID, or per-chip Active or Chip Authentication public keys.

Use synthetic data. The app's presets use specimen and invented names, and the unit tests use generated documents.
If you made a mistake, say so in a private security report, so it can be removed from the history, and do not just edit
the comment. Screenshots must show only emulated, synthetic data.

When you describe a reader problem, give the reader or library name and version, the phone model and Android
version, the profile you used and what failed. That is almost always enough.

## Building and testing

You need JDK 17 and the Android SDK with platform 36.

```shell
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest          # all JVM tests
./gradlew :composeApp:lintDebug                  # Android lint; new errors fail CI
python3 -m unittest discover -s tools/tests      # Python tool tests
```

Capture mode is compiled in only with `-Ppassportemu.captureMode=true`. Build and run the tests that way too if you
touch anything under `capture/` or `ui/capture/`.

Some docs are generated from the code and the profiles, and CI fails if the committed copy is stale
(`docs/coverage-matrix.*`, `docs/chip-profile-evidence.measured.md`, `docs/reader-compatibility.*`). After a change that
affects them, regenerate and commit them:

```shell
UPDATE_GENERATED_DOCS=1 ./gradlew :composeApp:testDebugUnitTest \
  --tests '*CoverageMatrixTest*' --tests '*ChipProfileEvidenceDocTest*' --tests '*ReaderCompatibilityTest*'
```

Do not edit those files by hand.

## Pull requests

- Open an issue first for anything large, so you do not build something that will not be merged.
- Keep a pull request to one change. Add or update tests; a bug fix needs a test that fails without the fix.
- Run the four commands above and say in the pull request what you ran.
- Follow the style of the code around you. Comments say why, not what.
- Do not add a dependency without saying why, and with its licence: it must be compatible with GPL-3.0-or-later, and
  it must be added to [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).
- By contributing you agree that your work is released under the repository's [LICENSE](./LICENSE),
  GPL-3.0-or-later. There is no DCO and no CLA.
- The default pull request template is short. For a profile change use the profile template: add
  `?template=profile.md` to the "new pull request" URL.

## Chip profile pull requests

Profiles describe how a document's chip behaves and what is known about it. There are two kinds of change:

1. **Curated profiles in Kotlin** (`composeApp/.../data/ChipProfiles.kt`): every claim needs a published source, and
   each part of a profile is marked Specification, Reported or Assumed. See
   [`docs/chip-profile-evidence.md`](./docs/chip-profile-evidence.md) for the rules and the sources already used.
   Never claim more than the source says.
2. **Measured profiles in [`profiles/`](./profiles/README.md)**: redacted JSON merged from captures by the profile
   steward. These arrive as a pull request that uses the profile template, with its redaction checklist ticked. Raw
   captures do not belong in a pull request. If you have captured a document and want to offer it, open an issue to say
   so and do not attach the file; the steward will arrange how to hand it over. Read the
   [capture guide](./docs/capture-guide.md) and [profile steward page](./docs/profile-steward.md).

The steward (currently the maintainer, `hdsysdev`) reviews profile changes. A profile pull request merges only when the
checks in [`docs/profile-steward.md`](./docs/profile-steward.md#merge-criteria) hold.

## Adding a country or a document

Open an issue with the country, the document type and generation, and public sources for its chip (the issuer's
documentation, a standard, or a published sample). Without a source a profile can only be Assumed, and may be
declined.
