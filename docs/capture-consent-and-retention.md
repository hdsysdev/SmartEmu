# Capture mode: privacy notice

**Status: in force for developer builds of capture mode.** It applies to anyone who runs capture mode and to anyone
who offers a capture to the project.

Capture mode records how a real eMRTD chip (passport or ID card) behaves, so that PassportEmu profiles match real
documents. It is **not in published APKs**: it is compiled in only when the app is built with
`-Ppassportemu.captureMode=true`. The ordinary emulator does not read real documents.

## In short

- Nothing leaves your phone unless you choose **Share** and send the file yourself. The app has no network permission.
- The saved record holds no personal data: no MRZ, no CAN, no name, no photo, no chip identifier.
- You can discard a record, and you can ask for a record you offered to be deleted.

## Purpose

A capture is used to build and check chip profiles, and for nothing else. It is not used to identify, track or assess
any person, and it is not passed on. What is published is a merged, redacted profile in [`profiles/`](../profiles/README.md),
which carries counts and random capture IDs only.

## Whose documents

- Your own document, or one whose holder has agreed in writing that you may capture it.
- Never a document you found, were handed for another purpose, or have no right to read.
- Each capture records `SELF` or `CONSENTING`. It records no name.

You are responsible for having the holder's agreement. Reading a chip you may not read can be an offence in some
places.

## What is processed and what is kept

| Item | Standard capture | Authenticated read |
|---|---|---|
| Public files (EF.CardAccess, EF.ATR/INFO, EF.DIR), status words, timings | Kept | Kept |
| MRZ, CAN | Not used | In memory only, cleared after use, **never kept** |
| DG1, DG2, DG7, DG11, DG12 contents | Not read | Read in memory to check existence, **never kept** |
| Chip UID, per-chip AA/CA keys, SOD data-group hashes | Not kept | **Never kept** |
| Dates, sizes | Month only, sizes rounded | Same |

The phone shows a "what will be saved" preview before you save or share. Saving or sharing from that screen is your
agreement to that file being kept and used as described here. The entry and capture screens block screenshots, and
the saved captures and the flag that unlocks capture mode are excluded from cloud backup and device transfer.

## What leaves the phone

**Nothing, unless you share a file.** The app does not upload anything. **Share** hands one JSON file to the Android
share sheet, and where it goes is your choice. Do not post it publicly. Captures are not accepted publicly: see
[the capture guide](capture-guide.md#how-to-send-the-file) for how to offer one.

## Retention and deletion

- **On your phone:** a saved capture stays for 30 days and is deleted the next time capture mode opens after that.
  **Discard** deletes it at once. **Share** writes a temporary copy to the phone's cache for the share sheet and does
  not save the capture.
- **Per-capture records the steward received:** kept in a restricted store outside this repository (a folder on the
  steward's own computer that only that user can read), for **12 months**, then deleted, not archived. Per-capture
  records are never committed.
- **Merged profiles in `profiles/`:** redacted, with counts and random capture IDs only. Kept for as long as they are
  useful. They contain nothing that identifies a holder or a chip.
- **Deletion on request:** the random capture ID (the file name) is the only handle, so keep the file name. Open an
  issue giving the capture ID, and nothing else, and the steward will delete the record. A deleted record cannot be
  removed from a profile already merged, which is why merged profiles carry nothing identifying.

## Rules

1. Purpose limitation: capture and profile building only.
2. The CAN and the MRZ are never stored, logged or backed up.
3. Rare document classes (diplomatic, very small issuers) with a single capture are held back from publication,
   because they could identify a holder (see [the profile steward page](profile-steward.md#n1-rare-class-holdback)).
4. Capture mode is not shipped in published APKs.

## Contact

The profile steward is the maintainer, [hdsysdev](https://github.com/hdsysdev). Use a GitHub issue for questions and
deletion requests, and the private
[security reporting](https://github.com/hdsysdev/PassportEmu/security/advisories/new) for anything that involves real
document data. This page describes the project's practice; it is not legal advice, and the project does not give any
assurance that capturing a particular document is lawful where you are.
