<!--
GENERATED FILE. Do not edit.
Written by ChipProfileEvidenceDoc.render from the profiles in profiles/*.json (ChipProfileEvidenceDocTest regenerates it).
The hand-written evidence for the curated profiles is in chip-profile-evidence.md.
-->

# Measured chip profile evidence

> MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures.

MEASURED is awarded per field, only after the hold-out rule passed with at least two captures: the profile is built from captures 1..N-1 and the emulator, configured from it, must reproduce capture N. That shows the chip answered the same in an independent capture and that the replay reproduces it; it does not show how the chip answers a command no probe sent. Fields marked "matches emulator default" are ones the emulator's own generic rules already answer, so the replay adds nothing to them. See docs/fidelity-diff.md.

Not emulatable on Android HCE, captured and labelled but never claimed:

- NFC-B: HCE offers NFC-A only
- UID, ATS and historical bytes: HCE can't set them
- Behaviour before the eMRTD application is selected: Android answers only once the AID has been selected

No measured profile has been merged yet: `profiles/` holds none. Until real captures are merged, every curated profile keeps its provenance from specifications and reports.
