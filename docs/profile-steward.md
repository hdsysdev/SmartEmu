# Profile steward

**Owner: TODO, not yet named.** Plan section 3, item 5. Name a person (and a deputy) and put them in `.github/CODEOWNERS`.

## Role

The steward is accountable for what enters `profiles/` and for the claims profiles make. They are the contact for consent questions, deletions and "the chip reported it is blocked" reports.

## Responsibilities

- Review and merge profile PRs. CODEOWNERS makes their approval required.
- Own the restricted per-capture store: access list, 12-month deletion, deletion requests.
- Keep the redaction manifest, schema and probe packs in step ([capture-schema.md](capture-schema.md), [probe-packs.md](probe-packs.md)).
- Decide what to capture next from the [coverage matrix](coverage-matrix.md).
- Approve Tier C results, and any new probe, before a pack version is released.
- Keep the consent and retention page current, and follow up the DPO review.

## Merge criteria

A profile PR merges only if all of these hold:

1. Ingest lint passed on every capture merged, and the PR's redaction checklist is complete.
2. Each field's provenance is correct: MEASURED only on fields marked compared that pass the hold-out rule (built from captures 1..N-1, diffed against N), with capture IDs, n and probe pack version stated.
3. Fields that disagree across captures or across devices are flagged and left at their previous provenance, not averaged.
4. Not-emulatable properties are not claimed.
5. The fidelity diff has no unreviewed deviations. Any deviation is in the allowed-deviation file with a reason.
6. The CI table is attached to the PR.

## Intake flow

Contributors need no git. They share the capture JSON their phone made (or one a lab imported, see [lab-captures.md](lab-captures.md)) to the Drive or Slack folder. A **maintainer** then runs the intake script on a download of that folder:

```
tools/capture-intake/intake.sh --input ~/Downloads/captures            # dry run: reports, writes nothing
tools/capture-intake/intake.sh --input ~/Downloads/captures --apply    # writes
tools/capture-intake/intake.sh --input ~/Downloads/captures --apply --branch intake-2026-10
```

What it does, in order (the checks are the app's own Kotlin: `RedactionLint`, the strict `CaptureJson.decode` and `CaptureFingerprint.isConsistent`; there is no second implementation in the script):

1. **Validate** each file: strict schema, the redaction lint (MRZ patterns, unknown fields, high-entropy blobs), a consistent fingerprint, a published probe pack, a completed run. A file that fails is **rejected with the list of violations** (fixed wording and schema paths, never the offending text) and, with `--apply`, **quarantined** in `rejected/` next to the inbox with a `.reason.txt`. A rejected file may still hold personal data: do not forward it, tell the contributor, delete it.
2. **Store** each accepted capture in the restricted per-capture store, `<store>/<generation>/<capture id>.json`. The store defaults to `~/captures-store` (or `--store`, or `CAPTURE_STORE`). It is **outside the repository** (the tool refuses a store inside it) and is **never committed**: per-capture records do not go to git. It is mode 700/600 and has the 12-month retention in [capture-consent-and-retention.md](capture-consent-and-retention.md). The same capture sent twice counts once.
3. **Merge** the stored captures per generation (`ProfileMerger`), run the hold-out rule and the fidelity diff against the emulator, and write the profile (below).
4. **Regenerate** `docs/chip-profile-evidence.measured.md`, `docs/coverage-matrix.md` and `docs/coverage-matrix.json` from the shared profiles.
5. **Report**: accepted, rejected with reasons, each generation with its n, badge, hold-out result and diff verdict. The last line is `RESULT: CLEAN`, `REJECTIONS` (files were quarantined) or `DEVIATIONS` (the diff shows a deviation that is not allowed: look at it before anything is committed).

It is a **dry run unless `--apply`**. `--link GENERATION=PROFILE-ID` attaches a generation to a curated profile (so the coverage matrix puts it on that document's row); it is remembered in the profile file on later runs.

`--branch NAME` (with `--apply`) makes a **local** branch with one local commit of `profiles/*.json` and the three generated docs. It never includes a per-capture record or `profiles/pending/`, and refuses to start if those files already have uncommitted changes. It does **not** push: it prints the push command for you to run yourself, and opening the pull request is left to you after you have pushed. Use the PR template's profile section.

## `profiles/pending/`: the holdback in practice

A generation with a **single capture** is written to **`profiles/pending/<id>.json`**, not to `profiles/`. Pending profiles:

- are **not part of the shared set**: the evidence doc and the coverage matrix are generated from `profiles/*.json` only, so a held-back capture does not show up as a row, and CI never sees them;
- are **not committed** (`profiles/pending/` is in `.gitignore`, and the intake's `--branch` leaves it out);
- are rebuilt from the restricted store on every run, so deleting the folder loses nothing;
- are `needsReview` and `needsMoreCaptures`: nothing can be held out with one capture, so no field is MEASURED.

A pending profile leaves pending in one of two ways:

- **A second capture of the same generation arrives.** The next intake run merges them, runs the hold-out rule, writes `profiles/<id>.json` and deletes the pending file. The rare-class concern is a single capture; two independent ones release it. (The steward may still ask for a rare class to stay out of the PR; that is their call.)
- **The steward releases it**, in writing with a reason, that the profile cannot single anyone out. Move the file from `profiles/pending/` to `profiles/` (the file name stays its profile id). The intake keeps a released single-capture profile in `profiles/` from then on, and the coverage matrix shows it as `Captured (n=1)`.

## n=1 rare-class holdback

A class with a single capture and a small population (diplomatic or official passports, tiny issuers, very new designs) can identify its holder, even when redacted. Such a capture:

- is not merged and not published in `profiles/`: the intake writes it to `profiles/pending/` (see above);
- stays in the restricted store, held for review;
- is released only when a second independent capture exists in the class, or the steward decides in writing, with a reason, that the merged profile cannot single anyone out (for example by merging into a broader generation).

Whether a class is rare is the steward's call; when in doubt, hold back. Holdback is the default.
