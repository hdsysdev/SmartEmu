# profiles/

Merged, redacted chip profiles built from captures. These are the files the emulator replays and the fidelity diff checks.

## What lives here and what doesn't

| Here (`profiles/`, in git) | Not here (restricted per-capture store) |
|---|---|
| Merged profiles, one per country, document and generation | Individual `CaptureRecord` files |
| Counts and random capture IDs only | Anything that could link a capture to a person or device |
| Redacted by construction | Raw intake folder (Drive or Slack) |

Per-capture records are kept in a separate restricted store for 12 months, then deleted. See [docs/capture-consent-and-retention.md](../docs/capture-consent-and-retention.md) (draft). Never commit a per-capture record, an MRZ, a CAN, a UID, a photo or a name here.

The app ships every `*.json` here (not `pending/`) as an asset, and installs them at start-up (`BundledProfiles`): each shows under **Captured with this app**, first in the preset list and in the chip profile picker (profiles built from published real-chip samples follow under **Sampled from real chips**), and the emulator replays it. A new profile reaches testers with the next build.

Until profiles move to data, the existing profiles stay in Kotlin (`composeApp/.../data/ChipProfiles.kt`). The loader supports both during the migration.

## Naming

`<country>-<document>-<year>.json`, lower case, matching the existing profile IDs: `de-passport-2017.json`, `nl-id-card.json`, `sg-passport-pa-2023.json`. A generation can have several profiles; the suffix distinguishes them. See [docs/glossary.md](../docs/glossary.md).

## Review process

1. A maintainer takes a submitted capture from the intake folder, runs the schema validation and redaction lint ([docs/capture-schema.md](../docs/capture-schema.md)), and stores it in the restricted store.
2. The maintainer merges captures per generation and opens a PR here with the merged profile. Fields that disagree across captures are flagged in the PR.
3. The PR uses the template, including the redaction checklist.
4. The profile steward reviews ([docs/profile-steward.md](../docs/profile-steward.md)). CODEOWNERS requires their approval.
5. CI runs the schema check, redaction lint and, when available, the fidelity diff with the hold-out rule. MEASURED is awarded per field only if that passes.
6. After merge, the coverage matrix ([docs/coverage-matrix.md](../docs/coverage-matrix.md)) updates.

## Pending profiles and the intake script

`tools/capture-intake/intake.sh` is how a maintainer turns shared capture files into the profiles here ([docs/profile-steward.md](../docs/profile-steward.md#intake-flow)). A generation with a single capture is written to `profiles/pending/` instead, which is not part of the shared set and is not committed, until a second capture arrives or the steward releases it. CI runs a redaction lint over every file in this folder (`ProfileLint`).
