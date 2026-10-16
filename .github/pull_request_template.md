## What changed

<!-- One or two sentences. -->

## Checks

- [ ] Tests run and results reported

## Profile PRs only (changes under `profiles/`)

Country / document / generation:
Captures merged (random IDs only) and n:
Probe pack and version:

Redaction checklist. Every box must be ticked, or say why not.

- [ ] No MRZ, CAN, document number, name or date of birth anywhere in the diff
- [ ] No DG1, DG2, DG7, DG11 or DG12 contents
- [ ] No chip UID, and no per-chip AA or CA public keys
- [ ] No EF.SOD data-group hash values, and no per-chip parts of EF.CardSecurity
- [ ] File sizes are in buckets; capture dates are month only
- [ ] Capture IDs are the random ones, and no per-capture record is committed
- [ ] Ingest lint (schema and redaction) passed in CI
- [ ] If n=1 of a rare class (diplomatic, small issuer): held for steward review, not merged
- [ ] Fields that disagree across captures are flagged below
- [ ] Not-emulatable properties are not claimed as MEASURED

Disagreeing fields:

Provenance changes (field, old, new, capture IDs, n):
