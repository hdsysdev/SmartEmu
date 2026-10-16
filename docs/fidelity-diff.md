# Measured profiles, replay and the fidelity diff (Phase 4)

> MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures.

This page covers what happens to captures after they are taken: merging into a profile, replay by the emulator, the diff that checks the replay, and the hold-out rule that decides which fields earn MEASURED. Plan: [capture-mode-plan.md](capture-mode-plan.md). Capture format: [capture-schema.md](capture-schema.md).

## The pieces

| Piece | Where | Does |
|---|---|---|
| `MeasuredProfile` | `commonMain/.../capture/profile/MeasuredProfile.kt` | JSON of one generation: merged observations, per-field fidelity and MEASURED status, disagreements. Schema: [measured-profile.schema.json](measured-profile.schema.json). File: `profiles/<id>.json`. |
| `MeasuredProfileJson` | same package | `encode` and strict `decode` (unknown field or `schemaVersion` refused). |
| `ProfileMerger` | same package | `merge(captures)` for one generation, `mergeAll(captures)` groups by generation key (`CaptureRecord.fingerprint`). |
| `ProfileRepository` | `androidMain/.../capture/profile/` | Loads and saves a directory of `<id>.json`; bad files are reported, not loaded. |
| `MeasuredProfileRegistry`, `MeasuredChipProfile` | `commonMain` | Link a measured profile to a curated `ChipProfile` (`ChipProfile.measured`) or make one of its own; `ChipProfiles.byId` finds linked ones. |
| `ProbeReplay`, `ProbeSituations` | `androidMain` | The emulator's lookup from a command to the probe step it is, and the measured status word for it. |
| `FidelityDiff`, `DiffReport` | `androidMain` / `commonMain` | Run a probe pack against the emulator, compare field by field, print `toTable()`. |
| `HoldOut`, `HoldOutEvaluator` | `commonMain` / `androidMain` | Build from the other captures, check on a held-out one, award MEASURED per field; four folds of withheld steps classify what the replay adds. |
| `FidelityBadge`, `EmulationLimits` | `commonMain` | Pure UI models: "Measured, n=3" and the emulation limits list. The Compose screens wire them later. |
| `ChipProfileEvidenceDoc` | `commonMain` | Renders [chip-profile-evidence.measured.md](chip-profile-evidence.measured.md) from `profiles/*.json`. |

## Merge rules

- Captures are grouped by the generation key (the `fingerprint`: chip-only, see [capture-schema.md](capture-schema.md); probe answers are not in it, so captures of one generation can disagree, which is what the merge and the hold-out are there to find). Within a group a field is merged only where every capture that has it agrees. A field they disagree on is **flagged** in `disagreements`, left out of `observations`, never averaged. A disagreement whose values split along reader models is marked `acrossDevices`: the phone is the more likely cause.
- Reader-side confounders (`controllerMaxTransceiveLength`, `controllerExtendedLengthSupported`) are never merged and have no field in a profile.
- A step whose repeats disagreed inside one capture is flagged, not merged.
- Timing is aggregated (minimum of minimums, median of medians, maximum of maximums, summed samples) and is informational.
- A profile of one capture has `needsReview = true` and `needsMoreCaptures = true`. A capture can't say how rare its document class is, so every single-capture profile is held for a steward, which is stricter than the plan's "rare classes".
- The largest READ BINARY (`maxReadLength`) is the largest probed Le (223, 224, 255, 256) answered in full for an Le the captured EF.CardAccess is long enough to fill. It is a lower bound. A short EF.CardAccess leaves it undetermined, and then the emulator keeps its own limit.
- What authenticated captures (Phase 2) showed is kept as informational `auth:` keys: algorithms, versions, which data groups, status words, sizes in buckets. It is behind authentication, so the diff can't check it and it can never be MEASURED.

## Field fidelity

Every field is one of:

- **COMPARED**: matched exactly (status words, EF.CardAccess bytes, EF.ATR/INFO, EF.DIR, size buckets, the largest read).
- **STRUCTURAL**: random or cryptographic, matched by shape. Today: the response size bucket of GET CHALLENGE (eight random bytes, never stored).
- **NOT_COMPARABLE**, with a reason: timing, the link layer ("not emulatable on Android HCE"), authenticated structure.

PACE cryptography is never MEASURED: no unauthenticated probe runs PACE. EF.CardAccess shows what the chip advertises, and the emulator's PACE follows the profile's modelled suites.

## Replay

When a profile has measured data (`ChipProfile.measured`, `ChipConfiguration.measured`) the emulator:

1. serves **EF.CardAccess exactly as captured** (bytes and order), or none if the chip was measured to have none;
2. serves **EF.ATR/INFO (2F01) and EF.DIR (2F00)** from the captured bytes, public like EF.CardAccess;
3. answers each probe step with its measured status word, from `ErrorResponses.byProbe` (keyed "probeId/stepId"), found by `ProbeSituations`: the same command bytes in the state the step starts from. **Only error words are replayed** (SW1 in 67, 68, 69, 6A, 6B, 6C, 6D, 6E, 6F; 6C is included because it carries no data and is probed). A 9000 or another non-error word is not looked up: the emulator's own handling answers it (rule 4 below makes it refuse where the chip does). From a measured profile only a step whose `step:<id>:statusWord` field is MEASURED is installed, and the out-of-sequence EXTERNAL AUTHENTICATE answer only if `bac:externalAuthenticate` is MEASURED and GET CHALLENGE answered 9000. `ErrorResponses.outOfSequence` stays the default otherwise;
4. where the measured word is 9000 but the emulator's own handling would refuse, does it: GET CHALLENGE with an Le other than 8, SELECT by a partial AID, an extended-length Le on READ BINARY;
5. answers **READ BINARY with INS B1** (DO'54' offset in, DO'53' out) only if the measured map says B1 on EF.CardAccess answered 9000; otherwise 6D00 as before;
6. caps a plain READ BINARY at the measured max read length (under secure messaging the emulator's own 223 still applies, so the response fits a short APDU);
7. answers a malformed command that is a probe step (the Lc longer than the data) as measured; without replay the SCUBA validation answers 6A80 as it always has.

The replay's state is per session: `ProbeReplay.reset()` runs on deactivation and at the first valid command of a new session, so one session's SELECT never carries into the next. EF.CardAccess, EF.ATR/INFO and EF.DIR contents (rules 1 and 2) are served from the profile's observations whether or not their fields earned MEASURED; only status words are gated. The fidelity diff and the hold-out replay everything (`MeasuredProfile.forEvaluation()` treats every checkable field as measured for the emulator they configure), because they are the test of the claim, not its consumer.

A hand-written `ErrorResponses(byProbe = ...)` on a curated profile replays the same way. With no measured data and no `byProbe` entries, nothing above applies and the emulator is byte-for-byte what it was.

Steps with a preamble (some Tier B probes) start from a state the emulator doesn't track, so they are not replayed; they are still compared and show as deviations if the emulator differs.

## Running the diff

```
./gradlew :composeApp:testDebugUnitTest --tests 'com.hddev.smartemu.capture.profile.*'
```

`GoldenCaptureDiffTest` runs the diff over every capture in `composeApp/src/androidUnitTest/resources/capture/golden/` (see its README: the `synthetic-*` files prove the pipeline and nothing about real chips; real captures dropped in are picked up). It prints the table for each generation and fails on any deviation not allowed below, and on any hold-out rejection not allowed.

The table:

```
FIELD                                    | KIND       | PROFILE | EMULATOR | RESULT    | NOTE
step:select-fid/...:statusWord           | compared   | 9000    | 6A82     | DEVIATION |
...
```

Deviations come first. The JVM diff bypasses Android's HCE stack: it is necessary, not sufficient. A second-phone run is the release gate.

## Allowed deviations

`docs/fidelity-allowed-deviations.json`, reviewed like code. Each entry has `field` (a key; `*` matches any run of characters), `reason`, `owner` (`TODO` until somebody takes it), optional `expires` (`YYYY-MM-DD`, last day honoured) and optional `profile`. Used entries are listed in the report; expired ones allow nothing and are listed as expired. The file is empty: nothing has needed allowing yet.

## Hold-out rule

`HoldOutEvaluator.evaluate(captures, emulatorFor)` decides which fields earn MEASURED. The generation key is chip-only, so captures of one generation really can disagree, and the rule works from that:

1. **Choose the held-out capture.** One capture is held out and the profile is built from all the others. The held-out one must have a different capture id from every capture the profile is built from, and the choice prefers the last capture whose reader model no other capture used, so that independence includes the phone. `MeasuredEvidence.heldOutFromDifferentReader` and the page say whether it was a different reader model.
2. **The full-table run decides.** The emulator is configured from the built profile (`forEvaluation()`, every checkable field replayed), run through the held-out capture's probe packs, and a field is **awarded** only if the built profile **and** the replay both equal the held-out capture on it.
3. **Four folds classify.** Steps are split into `HoldOut.FOLDS` = 4 deterministic folds (FNV-1a of the step id, modulo 4). In each fold the steps of that fold are withheld from the replay table and the emulator is run again. A field the emulator then still answers right is marked **matches emulator default** (the generic rules of the emulator already get it right; the replay adds nothing there); one it answers wrong is **needs replay**. These runs only classify awarded fields; they never award or remove one. A single evaluation is five emulator runs.

Then:

- Fewer than two distinct captures: nothing earns MEASURED, `needsMoreCaptures` is set, and the badge says "Captured, n=1: needs more captures".
- A held-out capture of another generation, or one the profile was built from, earns nothing (`HoldOut.award` refuses both itself).
- Fields that can't be checked (NOT_COMPARABLE) and fields the captures disagree on never earn it.
- The result carries `MeasuredEvidence`: all capture ids, the sample count, the probe packs, the held-out capture id and whether its reader differed.

An aspect (say error responses) shows as MEASURED only when every checkable field of it earned MEASURED.

### What this does and does not show

It shows: an independent capture (another capture id, preferably another phone) of the same generation gave the same answers as the captures the profile was built from, so the profile is not one capture's accident; and the emulator's replay reproduces what the profile claims, so the lookup works end to end. The contract MEASURED makes is **APDU-level indistinguishability on the compared fields, on that probe pack, from n captures**, and nothing wider.

It does not show:

- how the chip answers a command no probe sent. A probe pack is a finite list.
- that the emulator can **predict** an unseen step. The replay is a lookup keyed by probe step, so a step with no entry is answered by the emulator's own rules. The folds measure how often those rules happen to be right (`matches emulator default`), which is the honest limit; they are not a prediction test.
- anything about real chips, where the captures are the synthetic goldens. Those are produced by the emulator itself, so the replay reproduces them by construction. On them the hold-out is **circular**: the captures cannot disagree and `needsReplay` is empty, and a `MEASURED` there only proves the pipeline works. `GoldenCaptureDiffTest` labels them so and asserts exactly this. Only real captures from real chips, ideally on more than one phone, carry evidence.

## Regenerating

| What | Command |
|---|---|
| Synthetic golden captures | `GENERATE_SYNTHETIC_GOLDEN=1 ./gradlew :composeApp:testDebugUnitTest --tests '*SyntheticGoldenGenerator*'` |
| `docs/chip-profile-evidence.measured.md` | `UPDATE_GENERATED_DOCS=1 ./gradlew :composeApp:testDebugUnitTest --tests '*ChipProfileEvidenceDocTest*'` |
| The golden measured profile JSON | `UPDATE_GOLDEN=1 ./gradlew :composeApp:testDebugUnitTest --tests '*MeasuredProfileGoldenFileTest*'` (a schema change also needs a version bump and a migration note) |
| The golden capture records | `UPDATE_GOLDEN=1 ./gradlew :composeApp:testDebugUnitTest --tests '*CaptureGoldenFileTest*'` (the version 1 file is a fixed legacy record and is never regenerated) |
