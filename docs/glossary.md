# Glossary

Terms used by capture mode and its docs. Plan: [capture-mode-plan.md](capture-mode-plan.md).

## Capture mode

**Capture.** One run of the phone as a reader against one real chip, producing one redacted `CaptureRecord`. See [capture-schema.md](capture-schema.md).

**Probe.** A single scripted test with a fixed id and version, made of one or more steps (APDUs), each recorded as a status word, a rounded response size and a timing summary.

**Probe pack.** A fixed, versioned set of probes. Results are keyed by probe id and pack version. See [probe-packs.md](probe-packs.md).

**Tier A / B / C.** The safety class of a probe. A is always safe. B is unauthenticated but unusual: confirm first. C is for sacrificial or test cards only, one attempt. PINs, PUKs and repeated CAN failures are in no tier.

**Fingerprint (generation key).** A hash over what the chip itself fixes: EF.CardAccess, the answer to SELECT of the eMRTD application, and EF.ATR/INFO and EF.DIR where present. Probe answers, timing, device, probe packs and capture identity are excluded (the separate `observationDigest` covers everything, for exact equality). Equal fingerprints mean the same kind of chip, not necessarily the same behaviour: captures of one generation can disagree, and that is what the hold-out looks for.

**Generation.** An equivalence class of captures with the same fingerprint (generation key): same CardAccess bytes, same application SELECT answer, same ATR/INFO and DIR. A generation may map to one profile or to several (for example when two profiles differ only in sample data or emulator settings the fingerprint does not see).

**Profile.** A configuration the emulator replays, for one country and document at one design (`de-passport-2017`). A profile is built from a generation's captures or, until measured, from specifications and reports.

**MEASURED.** A provenance level. It means: "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures." It is awarded per field, only after the hold-out rule is met. It carries capture IDs and a sample count. It says nothing about the properties in the not-emulatable list ([probe-packs.md](probe-packs.md)).

**Compared / structural / not comparable.** The fidelity diff's status for each field. *Compared*: matched exactly. *Structural*: random or cryptographic, so matched by shape (length, tags, algorithm), not value. *Not comparable*: cannot be checked, with a reason written next to it.

**Hold-out.** Building a profile from captures 1..N-1 and diffing against capture N. A replay that matches only the capture it was built from earns nothing.

**Redaction manifest.** The fixed list of what a capture never stores and why, written into every record (`redactions`) and shown on the "what will be saved" preview.

**Reader-side confounder.** A property of the capturing phone, not the chip: for example `IsoDep.getMaxTransceiveLength()`, extended-length support, NFC chipset, and the missing ATS timing fields. Recorded with the device and merged only when devices agree.

**Quasi-identifier.** A value that is not an identifier alone but can single out a holder or chip when combined (exact file sizes, capture day, rare document class). Handled by rounding, month-only dates and the n=1 holdback.

**Allowlist.** Captures contain only fields that a typed extractor is approved to emit. Unlisted data has no field to go in.

## eMRTD terms

**eMRTD.** Electronic machine-readable travel document: a passport or ID card with an NFC chip (ICAO Doc 9303).

**DG.** Data group (DG1 to DG16): the chip's data files. DG1 is the MRZ, DG2 the portrait, DG14 security options for Chip Authentication, DG15 the Active Authentication public key.

**EF.CardAccess.** An unauthenticated file (011C) listing the PACE parameters the chip offers, and related SecurityInfos. Order is kept as written.

**PACE.** Password Authenticated Connection Establishment: the modern access protocol, keyed by MRZ or CAN, with a mapping (generic, integrated, or chip-authentication) and a cipher/curve suite.

**BAC.** Basic Access Control: the older access protocol, keyed by MRZ, using 3DES.

**CAM.** Chip Authentication Mapping: a PACE variant that also authenticates the chip.

**AA.** Active Authentication: the chip signs a reader challenge with a per-chip key (DG15), proving it is not cloned.

**CA.** Chip Authentication: key agreement with the chip's static key (DG14), after which the channel is re-keyed and the chip is authenticated.

**TA.** Terminal Authentication: the reader proves its authorisation with a certificate chain, to unlock sensitive groups (DG3, DG4). Not supported by the emulator: it refuses TA.

**SOD.** Document Security Object (EF.SOD): the issuer-signed list of data-group hashes, with the digest and signature algorithms, LDS version and document-signer certificate.

**CAN.** Card Access Number: the printed number on ID cards used as the PACE key.

**MRZ.** Machine-readable zone: the printed lines on the data page, and the PACE/BAC key for passports.
