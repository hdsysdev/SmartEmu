# Capture consent and retention: DRAFT

**Status: DRAFT. DPO review: recommended, not yet performed.** Nothing here is signed off. The rules below are recommendations from the [capture-mode plan](capture-mode-plan.md), section 3, for the DPO to accept or change.

## Purpose

Capture mode records how a real eMRTD chip behaves, so that PassportEmu profiles match real documents. It is used for nothing else. Records are not used to identify, track or assess any person, and are not shared outside the team except as redacted merged profiles in this repository.

## Whose documents

- Your own document, or a colleague's who has agreed.
- Never a customer's, a stranger's, or one handed over for another purpose.
- Each capture records `SELF` or `CONSENTING`. It records no name.

## What is processed and what is stored

| Item | Standard capture | Authenticated read |
|---|---|---|
| Public files (EF.CardAccess, EF.ATR/INFO, EF.DIR), status words, timings | Stored | Stored |
| MRZ, CAN | Not used | In memory only, cleared after use, **never stored** |
| DG1, DG2, DG7, DG11, DG12 contents | Not read | Read in memory to check existence, **never stored** |
| Chip UID, per-chip AA/CA keys, SOD data-group hashes | Not stored | **Never stored** |
| Dates, sizes | Month only, sizes rounded | Same |

The phone shows a "what will be saved" preview before save or share. Confirming it is the holder's consent for that file.

## Retention

- **Per-capture records:** a restricted store, deleted after **12 months**. Access limited to the profile steward and named maintainers.
- **Merged profiles in `profiles/`:** redacted, with counts and random capture IDs only. Kept while they are useful.
- A contributor can ask for their capture to be deleted. The random capture ID (the file name) is the only handle, so contributors should keep the file name.
- Records leaving the 12-month window are deleted, not archived.

## Rules

1. Purpose limitation: capture and profile building only.
2. CAN and MRZ are never stored, logged or backed up.
3. Rare document classes (diplomatic, very small issuers) with one capture are held for review before sharing, because they can identify a holder.
4. Capture mode ships in internal builds only until this page has been reviewed.

## Open points for the DPO

- Is a 12-month limit right, and does it apply to the raw intake folder as well?
- Is the in-memory use of MRZ, CAN and DG data acceptable for the authenticated read?
- Is the confirmation step on the preview screen enough consent evidence, or is a separate record needed?
- Does the n=1 holdback cover the re-identification risk?

DPO review: recommended, not yet performed. Reviewer and date: _not recorded._
