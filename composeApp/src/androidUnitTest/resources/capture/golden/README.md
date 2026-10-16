# Golden captures for the fidelity diff and the hold-out rule

`GoldenCaptureDiffTest` reads every `*.json` file in this directory as a `CaptureRecord`, groups the captures by generation
(their fingerprint), builds a profile per generation, runs the fidelity diff against the emulator configured from it, and
runs the hold-out rule where a generation has two captures or more. It fails on any deviation that
`docs/fidelity-allowed-deviations.json` does not allow.

## Synthetic captures: they establish nothing about real chips

Files named `synthetic-<profile-id>-<N>.json` are **SYNTHETIC**. They were produced by running the JVM emulator
(`PassportHceService`) of a profile through the capture runner, from a pretend reader whose model is labelled
`SYNTHETIC ... (JVM emulator not a phone)`. They are the emulator's own behaviour, read back. They exist so the
hold-out and diff pipeline has inputs before any real chip has been captured, and they prove the pipeline works, not that
any profile matches any passport. No file here claims to come from a real chip, and none may be renamed to look like one.

| File | Profile run | Pretend reader |
|---|---|---|
| `synthetic-de-passport-2017-1.json`, `-2.json` | `de-passport-2017` | reader A, reader B (same generation) |
| `synthetic-de-passport-2017-3.json` | `de-passport-2017` | reader C, no extended length: a different generation, n=1 |
| `synthetic-de-id-card-1.json`, `-2.json` | `de-id-card` | reader A, reader B |
| `synthetic-us-passport-2021-1.json`, `-2.json` | `us-passport-2021` | reader B, reader A |

The test links each synthetic capture to the curated profile in its file name, so the emulator is replayed on that profile's modelled cryptography and files with the measured fields on top. A real capture names no curated profile and is replayed on the generic one.

Regenerate with `GENERATE_SYNTHETIC_GOLDEN=1 ./gradlew :composeApp:testDebugUnitTest --tests '*SyntheticGoldenGenerator*'`.
Regenerating gives new capture ids and a new month, so the files change; that is expected.

## Real captures

A real capture dropped in here (any other file name, a valid `CaptureRecord` that passes the redaction lint) is picked up
automatically. Real captures belong in the restricted per-capture store (see `profiles/README.md` and
`docs/capture-consent-and-retention.md`), not in git, unless the profile steward has approved committing a redacted one as a golden file.

## Generation key and circularity

Captures are grouped by `fingerprint`, which is the chip-only generation key (see `docs/capture-schema.md`), so two
captures of one generation can disagree on probe answers. The synthetic captures are emulator output, so they never do,
and the hold-out on them proves only that the pipeline runs: nothing in them is evidence about a real chip. A real
capture that lands in a synthetic generation would be a mismatch, not a confirmation. Files here are schemaVersion 2
(upgraded in memory if 1). Regenerating the synthetic files after a probe pack change is the command above; the
`capture/golden-capture-v1.json` and `golden-capture-v2.json` records are a different pair (see `CaptureGoldenFileTest`).
