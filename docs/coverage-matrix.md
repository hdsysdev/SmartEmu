# Coverage matrix

Which documents are covered, and by how much, so the next captures go where they matter. The table at the end of this page is **generated**; everything above it is hand-written. A machine-readable copy is in [coverage-matrix.json](coverage-matrix.json).

## Purpose

To show maintainers which documents are covered, and by how much, so the next captures go where they matter. Rows with no captures come first, the least established among them first: those are the documents to capture next.

## Shape

One row per document class, from the curated profiles (`ChipProfiles.all`) and the measured profiles in `profiles/`:

- **Capture next**: 1 is the document to capture first. Rows that already have captures show `-`.
- **Country**: ICAO 9303 (ISO 3166 alpha-3) code of the issuing state from the profile, or `ANY` for an EU stand-in that fits any member state. The generic ICAO profile is not a document and has no row.
- **Document**: passport or ID card and its design variant (for example `passport (2017 design)`).
- **Status**, the strongest evidence any aspect of the document has, with these labels (strongest first):
  - `Measured (n=k)`: at least one field passed the hold-out rule on k captures.
  - `Captured (n=k)`: a real chip was captured, nothing has earned MEASURED yet. With n=1 nothing can be held out, so a single capture is always `Captured (n=1)`.
  - `Specification`: the curated profile's best aspect is required by a standard or regulation.
  - `Reported`: described in public sources, not checked against a real chip.
  - `Assumed`: an emulator default.
- **n**: the number of distinct captures merged into the profiles linked to this row. Rows with n below 3 are marked `(thin)`.
- **Provenance of the aspects**: how many of the profile's eight aspects have each provenance.

A measured profile belongs to a row through its `chipProfileId`. A captured generation that no curated profile claims is listed under the table, so that it is not lost.

## How it is generated

1. `CoverageMatrix.render(profiles, curatedProfiles)` (pure, in `commonMain`) reads the profile ids, the provenance of each aspect, and the **count** of captures merged. It never sees a per-capture record, so the page cannot expose anything restricted.
2. `CoverageMatrixTest` writes the table between the markers below and `docs/coverage-matrix.json` when run with `UPDATE_GENERATED_DOCS=1`, and fails when the committed files differ from what the profiles render to. The maintainer's intake script (`tools/capture-intake/intake.sh`) regenerates both. CI regenerates them and fails on any difference (`.github/workflows/capture-checks.yml`).
3. Profiles held back in `profiles/pending/` (a single capture that a steward has not released, see [profile-steward.md](profile-steward.md)) are not in the shared set and so are not counted.

```
UPDATE_GENERATED_DOCS=1 ./gradlew :composeApp:testDebugUnitTest --tests '*CoverageMatrixTest*'
```

<!-- BEGIN GENERATED: coverage matrix (CoverageMatrix.render; regenerate, do not edit between the markers) -->
<!-- Written by CoverageMatrix.render from ChipProfiles.all and profiles/*.json; CoverageMatrixTest regenerates it. -->

**GENERATED. Do not edit this table.**

28 documents: 0 with captures, 28 without. Capture the top rows first.

| Capture next | Country | Document | Profile | Status | n | Provenance of the aspects |
|---|---|---|---|---|---|---|
| 1 | ANY | EU ID card (2021 design) | `eu-id-card` | Assumed | 0 | 8 assumed |
| 2 | ANY | EU passport (current design) | `eu-passport` | Assumed | 0 | 8 assumed |
| 3 | AUS | passport (P Series, 2016) | `au-passport-2016-p` | Reported | 0 | 4 reported, 4 assumed |
| 4 | AUT | passport (2023) | `at-passport-2023` | Reported | 0 | 3 reported, 5 assumed |
| 5 | CAN | passport (2023 design) | `ca-passport-2023` | Reported | 0 | 1 reported, 7 assumed |
| 6 | CHN | passport (README type PO, 2018) | `cn-passport-po-2018` | Reported | 0 | 3 reported, 5 assumed |
| 7 | FIN | ID card (2023) | `fi-id-card-2023` | Reported | 0 | 2 reported, 6 assumed |
| 8 | FIN | passport (2024) | `fi-passport-2024` | Reported | 0 | 1 reported, 7 assumed |
| 9 | FRA | ID card (2024) | `fr-id-card-2024` | Reported | 0 | 1 reported, 7 assumed |
| 10 | FRA | passport (2017) | `fr-passport-2017` | Reported | 0 | 2 reported, 6 assumed |
| 11 | GBR | passport (2020 design) | `gb-passport` | Reported | 0 | 3 reported, 5 assumed |
| 12 | HKG | passport (2025) | `hk-passport-2025` | Reported | 0 | 1 reported, 7 assumed |
| 13 | IDN | passport (2025) | `id-passport-2025` | Reported | 0 | 2 reported, 6 assumed |
| 14 | MYS | passport (2023) | `my-passport-2023` | Reported | 0 | 3 reported, 5 assumed |
| 15 | NLD | ID card (2014 design) | `nl-id-card` | Reported | 0 | 1 reported, 7 assumed |
| 16 | NLD | passport (2014 design) | `nl-passport` | Reported | 0 | 1 reported, 7 assumed |
| 17 | NLD | passport (2024 design) | `nl-passport-2024` | Reported | 0 | 3 reported, 5 assumed |
| 18 | NZL | passport (2017) | `nz-passport-2017` | Reported | 0 | 2 reported, 6 assumed |
| 19 | PHL | passport (2020) | `ph-passport-2020` | Reported | 0 | 2 reported, 6 assumed |
| 20 | PRT | passport (README type PP, 2026) | `pt-passport-2026` | Reported | 0 | 1 reported, 7 assumed |
| 21 | RUS | passport (2020) | `ru-passport-2020` | Reported | 0 | 3 reported, 5 assumed |
| 22 | SGP | passport (README type PA, 2023) | `sg-passport-pa-2023` | Reported | 0 | 2 reported, 6 assumed |
| 23 | SGP | passport (README type PP, 2025) | `sg-passport-pp-2025` | Reported | 0 | 1 reported, 7 assumed |
| 24 | TWN | passport (2024) | `tw-passport-2024` | Reported | 0 | 2 reported, 6 assumed |
| 25 | USA | passport (Next Generation, 2021) | `us-passport-2021` | Reported | 0 | 6 reported, 2 assumed |
| 26 | DEU | ID card (Personalausweis) | `de-id-card` | Specification | 0 | 2 specification, 6 assumed |
| 27 | DEU | passport (2017 design) | `de-passport-2017` | Specification | 0 | 3 specification, 5 assumed |
| 28 | DEU | passport (2023 design) | `de-passport-2023` | Specification | 0 | 2 specification, 4 reported, 2 assumed |

<!-- END GENERATED: coverage matrix -->
