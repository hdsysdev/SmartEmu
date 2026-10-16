# Capture record format (schemaVersion 2)

A capture is one JSON file, a `CaptureRecord`, written by kotlinx.serialization. The Kotlin type is `composeApp/src/commonMain/kotlin/com/hddev/smartemu/capture/CaptureRecord.kt`. The machine-readable contract is [capture-record.schema.json](capture-record.schema.json) (JSON Schema draft 2020-12, `additionalProperties: false` everywhere). The Kotlin and the schema must agree: `CaptureSchemaFileTest` validates every golden capture (both schema versions, and each synthetic one) against the schema file with a small validator of its own, and compares the schema's properties and `required` lists with the Kotlin serial descriptors type by type, so a field added on one side only fails the build. The schema is not defined only in Kotlin, and not only in this prose.

The format is an **allowlist**. Every field is approved to leave the phone, and there is no free-text field and no raw transcript.

## Fields

### Top level

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | `2` for a record written now. `1` records are still read and upgraded in memory (see the migration policy). |
| `captureId` | string | Random. Not derived from the device, chip or time. |
| `captureMonth` | `YYYY-MM` | Month only. |
| `source` | enum | `ANDROID_READER`, `PCSC` or `PROXMARK`. |
| `probePack` | `{id, version}` | The pack that ran. |
| `additionalProbePacks` | list of `{id, version}` | Further packs run in the same session after `probePack`, in order. Optional, defaults to none. Each probe result carries its own pack version. |
| `reader` | object | The capturing device. |
| `link` | object | What the link layer showed. |
| `files` | object | Unauthenticated files. Optional, defaults to none. |
| `application` | object or null | How the chip answered SELECT of the eMRTD application: `selectStatusWord` and `responseSize` (a 16-byte bucket). Absent if the run stopped before that. |
| `bacCommands` | object or null | The status words of the commands BAC starts with, before any authentication: `getChallenge` (GET CHALLENGE, Le 08) and `externalAuthenticate` (with 40 zero bytes and no GET CHALLENGE before it). The challenge itself is random and never stored. Absent if the run stopped before that. |
| `probes` | list | Probe results in the order run. Optional. |
| `authenticated` | object | What an authenticated read showed: see "Phase 2". Absent (never null) from a capture without one. |
| `aborted` | enum or null | `TAG_LOST`, `BLOCKED_STATUS_WORD`, `USER_CANCELLED`, `UNSUPPORTED_CHIP`. If set, missing probes are absent, not failed. |
| `fingerprint` | 16 UPPER-CASE hex digits | The **generation key** (schema version 2; see "Generation key and digest"): the first 8 bytes of a SHA-256 over what the chip itself fixes, written upper-case by `CaptureFingerprint.of`. Ingest requires non-empty and recomputes it: a lower-case or otherwise different value is rejected as not matching. |
| `observationDigest` | 16 UPPER-CASE hex digits | Schema version 2: the same kind of hash over every observation, for exact equality (`CaptureFingerprint.digestOf`). Absent from a version 1 record as written and from a draft; omitted from the JSON while empty. |
| `redactions` | list of `{item, reason}` | What was left out. Must equal the manifest for the record's version and content: see "Redaction allowlist and manifest". |

Writers always emit every field except the ones marked omitted-while-empty (`authenticated`, `observationDigest`, `link.historicalBytesLength`). Readers (the Kotlin decoder) tolerate omitted defaults; the schema requires only the fields with no Kotlin default plus `schemaVersion`.

### Generation key and digest

A **generation** is one kind of chip. The key (`fingerprint`) hashes only what the chip itself fixes: EF.CardAccess exactly as stored (with the status word of its SELECT), the answer to SELECT of the eMRTD application (status word and the 16-byte bucket of the response), and EF.ATR/INFO and EF.DIR where present. It leaves out everything a reader, a phone, a holder or a day can change: probe status words and sizes, timing, the steps a phone could not send, the probe packs run, the authenticated read, the link and the reader. So captures of one kind of chip share a key **whatever they answered to the probes**, which is what lets the hold-out rule (see [fidelity-diff.md](fidelity-diff.md)) fail: two captures of one generation can disagree.

Not in the key, on purpose, and not in any key of a generation: DG2, DG11 and DG12 size buckets, the signer certificate's `validityMonths` and the access outcomes of the authenticated read. They describe a holder, one issuance or one attempt. The authenticated structure that is the same for every chip of a model (algorithms, the data group set, curve ids) could be part of a second-level key; none is defined yet.

`observationDigest` is the exact-equality hash: pack, application, files, BAC answers, every probe step (status word, size, setup, `repeatsAgree`) and the structure of the authenticated part. Hex is hashed in upper case whatever case it was written in, so `31AB` and `31ab` are the same file.

Schema version 1 stored a fingerprint that hashed every observation. That recipe is kept (`CaptureFingerprint.legacyV1Of`) to check version 1 records as written, and its value for the golden record is pinned in `PublishedPacksTest`; the version 2 key and digest are pinned there too, with a note that the recipe changed on purpose.

### `source`

Says where the bytes came from. `ANDROID_READER` is the phone app. `PCSC` and `PROXMARK` are for lab captures from USB readers and transcript importers. Link-layer fields (`link`, `reader.controller*`) may be null where the transport cannot tell. Merge and diff treat sources as different reader-side confounders (see [probe-packs.md](probe-packs.md)).

### `reader`

`model`, `androidVersion`, optional `nfcChipset`, `controllerMaxTransceiveLength` (0 to 65535), `controllerExtendedLengthSupported`. These are the phone's NFC controller properties, **not the chip's**. They are recorded to explain differences, never merged into a profile as chip facts.

The three names come from the phone's firmware, so they are short plain ASCII: letters, digits, space and `. _ + - / ( )`, 1 to 64 characters (the builder cuts and cleans them; the lint refuses anything else). The lint also refuses a six-digit number (it could be a CAN) and a nine-character letters-and-digits token with six or more digits (it could be a document number). Real model strings pass: `Pixel 7 Pro`, `SM-S918B`, `M2007J3SG`, `SYNTHETIC reader A (JVM emulator not a phone)`. A name that fails is stored as `unknown`.

### `link`

`technology` (`NFC_A` or `NFC_B`), `historicalBytes` (hex; NFC-A historical bytes or NFC-B higher-layer response, since Android exposes no more of the ATS), `uidRandom` (boolean: 0x08-prefix heuristic confirmed by a second tap; the UID is never stored), `maxApduLengthObserved` (0 to 65535), `historicalBytesLength` (version 2, omitted while null).

Historical bytes are bounded (at most 64 bytes) and kept only if they are compact-TLV in a known category (`00`, `80` or `10`) with the issuer's data (tag 4) and the pre-issuing data (tag 5) cut out. Bytes in any other form (a proprietary category, a cut-off object) are not stored; `historicalBytesLength` then holds only how long they were, and it is also set when something was cut out. The lint refuses historical bytes that are not already in this reduced form. All of this is [not emulatable on Android HCE](probe-packs.md#not-emulatable) and is recorded and labelled, never claimed.

### `files`

`cardAccess` (EF.CardAccess, 011C), `atrInfo` (EF.ATR/INFO, 2F01), `dir` (EF.DIR, 2F00). Each is a `FileObservation` or null if the chip didn't offer it:

- `selectStatusWord`: integer SW (0 to 65535), for example `36864` is `0x9000`.
- `contentsHex`: the bytes in the chip's own order after the allowlist below, or null if unreadable or if nothing parseable is left. Either hex case is accepted; it is hashed in upper case.
- `size`: a 256-byte `SizeRange` bucket, never the exact length (of the stored bytes where they are kept, else of what was read).

The files are public, but a field called public is where a per-chip value would hide, so each has a structural allowlist (`PublicFileRules`, applied by the builder when it stores a file and again by the lint, in `RedactionLint` and `ProfileLint`):

- **EF.CardAccess** is a SET of SecurityInfo. Kept: `PACEInfo` (`0.4.0.127.0.7.2.2.4.*`: version and optional parameter id), `PACEDomainParameterInfo` only where the parameters are a reference to a standard domain, `ChipAuthenticationInfo` (`...2.2.3.*`), `TerminalAuthenticationInfo` (`...2.2.2`), `EFDIRInfo` (`...2.2.13`, its content passing the EF.DIR rule) and `ActiveAuthenticationInfo`. Refused or dropped: `ChipAuthenticationPublicKeyInfo` (`...2.2.1.*`) and anything else carrying a key's bytes, explicit domain parameters, and entries of an unknown protocol with more than 32 bytes of payload. A PACE-CAM chip's per-chip key is in EF.CardSecurity, not here, so a real EF.CardAccess is PACEInfos only and loses nothing.
- **EF.ATR/INFO** is BER-TLV data objects. Kept: `7F66` (extended length information), `43` and `47` (card service data and capabilities) and `5F52` (historical bytes, reduced as above). Dropped: `45` and `46` (card issuer's data and pre-issuing data, which name an issuance) and every other tag.
- **EF.DIR** is application templates. Kept: `61` holding only `4F`, the application identifier (at most 16 bytes). Labels and paths are dropped.
- Fill (`00` or `FF`) after the last object is dropped. Anything that does not parse as data objects is not stored; only its length bucket is.
- Over all of it, no 32-byte window may have the byte statistics of random data (Shannon entropy over 4.5 bits per byte), as a key or a hash does.

When the builder drops something it does not fail the capture; the manifest's last entry (below) says it may. The lint refuses a record that still holds any of it.

### `probes`

Each `ProbeResult` has `probeId`, `packVersion`, `tier` (`A`, `B`, `C`) and `steps`. Each `StepResult` has `stepId`, `statusWord` (null if the tag was lost before an answer), `responseSize` (a 16-byte bucket), `timing` (`minMs`, `medianMs`, `maxMs`, `samples`), `setupStatusWord` (the status word of the re-SELECT that put the chip in the step's starting state; null or absent if the step needs none) and `repeatsAgree` (false if repeating the command gave different status words, in which case `statusWord` is the first; default true). Timing is informational and never diffed exactly: it is 0 to 600000 ms with `minMs <= medianMs <= maxMs` and 1 to 50 samples.

### `SizeRange`

`{minBytes, maxBytes}`. Two bucket sizes, by what the size describes:

- **256 bytes** (`Redaction.SIZE_BUCKET`) for the three files and for everything in `authenticated`: `minBytes` is a multiple of 256 and `maxBytes = minBytes + 255`. An exact length is a quasi-identifier.
- **16 bytes** (`Redaction.PROBE_BUCKET`) for the response of a probe step and of the application SELECT: `minBytes` a multiple of 16 and `maxBytes = minBytes + 15`. Probes read only the public EF.CardAccess, so their lengths are no quasi-identifier, and 256 would be too coarse to tell a chip that answers 223 bytes from one that answers 255.

JSON Schema can state the multiple (the schema has `SizeRange` and `ProbeSizeRange`) but cannot relate the two fields, so the ingest lint checks `maxBytes`.

## Provenance in merged profiles

Provenance is per field. `SPECIFICATION`, `REPORTED` and `ASSUMED` keep their meaning in `ChipProfile.kt` and are unchanged. `MEASURED` is added above `SPECIFICATION`, and every MEASURED field carries:

- the capture IDs it was built from,
- the sample count n,
- the probe pack and version it was compared on.

MEASURED means: "MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, on fields marked compared, from n captures." It is awarded per field and only after the hold-out rule passes. It never covers the not-emulatable properties. A field that disagrees across captures is flagged, not averaged, and stays at its previous provenance.

## schemaVersion migration policy

- `schemaVersion` is bumped on any change to the JSON: a field added, removed, renamed, or its meaning or allowed values changed. `CAPTURE_SCHEMA_VERSION` in Kotlin and the schema's `const` change together.
- One schema file, `capture-record.schema.json`, describes every version the reader accepts: `schemaVersion` is `1` or `2`, and version 2 is a superset (`observationDigest` and `link.historicalBytesLength` are optional additions; what changed in meaning is `fingerprint`). A change that made versions incompatible in the schema would add a second file and keep the old one.
- The reader accepts the current version and the previous one. `CaptureJson.decode` upgrades older records in memory (`CaptureMigration.toCurrent`); `CaptureJson.decodeAsWritten` gives the record as the file said it, which the lint uses to check a version 1 record under version 1's own rules. Stored records are never rewritten in place.
- A removal or tightening requires a migration and golden files for both versions. A pure addition of an optional field is still a bump, because `additionalProperties: false` would reject it in old validators.
- Ingest rejects a `schemaVersion` it has no migration for, and says so.
- **Version 1 to 2 (the one migration so far).** The generation key replaced the all-observations fingerprint in `fingerprint`, `observationDigest` was added, and the manifest gained an entry. Upgrading recomputes `fingerprint` and `observationDigest` from the record's own contents and sets `redactions` to the current manifest; nothing a version 1 record held is lost. A version 1 record's own fingerprint is checked first, as written (under the legacy recipe), by the lint; the upgrade replaces it. `golden-capture-v1.json` is a version 1 record that must keep decoding and upgrading to the golden record, and `golden-capture-v2.json` is the same chip as version 2.
- Anything newly stored needs a redaction review first: update the manifest and the canary test in the same change.

## Redaction allowlist and manifest

Only typed extractors build a record, and they can emit only the fields above. The following have no field and are never stored:

DG1 and the MRZ; DG2; DG7, DG11 and DG12 contents; the CAN and any access key; the chip UID; per-chip AA and CA public keys; EF.SOD data-group hash values; per-chip parts of EF.CardSecurity; exact file sizes; capture day and time; the raw APDU transcript.

`redactions` in every record lists these with a reason, and the phone's "what will be saved" preview shows the same list. The manifest is `RedactionManifest.entries` (version 2: the eleven items above and a twelfth, "Per-chip and per-issuance data objects inside the public files and the historical bytes", which says that public key infos, issuer and pre-issuing data and labels are dropped before saving) and `entriesV1` for a version 1 record (the eleven). The lint requires the list to equal `RedactionManifest.expected(version, hasAuthenticated)`. The APDU logger is in memory only, with no logcat output. A canary test runs a synthetic passport with a known MRZ and name through capture and asserts none of those bytes appear in the output.

## What ingest lint rejects

Ingest is the second line of defence. `RedactionLint.check` reads the JSON as text and rejects a submission if any of these hold; each is a rule in code and has a test (`RedactionLintTest`, `PublicFileRulesTest`, `AuthenticatedLintTest`):

- It fails the strict decode: unknown fields, wrong types, bad enum value, a missing required field (`SCHEMA_MISMATCH`). (The JSON Schema file states the same contract for other tools; the lint is the Kotlin decoder, and `CaptureSchemaFileTest` keeps the two in step.)
- `schemaVersion` is not one this build reads (`SCHEMA_VERSION`).
- `fingerprint` is empty, is not 16 upper-case hex digits, or does not match the one recomputed from the record; for version 2, the same for `observationDigest` (`FINGERPRINT_MISMATCH`). `CaptureFingerprint` is pure Kotlin in `commonMain` (including its SHA-256), so this runs wherever the lint does.
- `redactions` is not exactly the manifest for the record's version, plus `authenticatedEntries` if and only if there is an `authenticated` part (`REDACTIONS_MISMATCH`).
- A `SizeRange` is not a bucket of the right size: 256 bytes for files and the authenticated part, 16 for probe and application responses (`BAD_FIELD`).
- A number is out of range: a status word outside 0 to 65535, a timing outside 0 to 600000 ms or not ordered, samples outside 1 to 50, `controllerMaxTransceiveLength` or `maxApduLengthObserved` outside 0 to 65535 (`BAD_FIELD`).
- `reader.model`, `androidVersion` or `nfcChipset` is not a short plain device name, or is a six-digit number or a document-number-like token (`BAD_FIELD`).
- Any string value matches an MRZ pattern (TD1, TD2 or TD3 lines, `<` filler runs), or is a long hex or base64 blob outside the file fields (`MRZ_PATTERN`, `HIGH_ENTROPY_BLOB`).
- A file's `contentsHex` is not hex, is longer than its limit (2048 bytes for EF.CardAccess, 512 for the others), carries MRZ-like text, or fails the structural allowlist above, including the entropy rule (`BAD_FIELD`). `link.historicalBytes` the same.
- `captureId` is not a version 4 UUID, `captureMonth` is not `YYYY-MM`, or a pack, probe or step id is not a short lower-case identifier (`BAD_FIELD`).
- The `authenticated` part fails `AuthenticatedLint` (see "Lint rules added").

Where the lint stops: it checks the **shape** of probe pack, probe and step ids, not that they exist. Resolving them against the published packs (and a tier against the pack definition) is capture intake's job, which has the registry (`UNKNOWN_PROBE_PACK`). Rejections give the reason and never quote the offending text. The contributor's file is not edited to make it pass.

## Phase 2: the `authenticated` section

An authenticated capture (MRZ or CAN) adds one optional top-level field, `authenticated`, an `AuthenticatedObservation` (Kotlin: `AuthenticatedObservation.kt`; schema: `$defs/AuthenticatedObservation` in [capture-record.schema.json](capture-record.schema.json)). It is **absent**, not null, from a capture without authentication, so a Phase 1 record is the same text it was. It was added without a version bump, as an optional field absent from records without one; the later bump to version 2 (see the migration policy) covers it.

Everything in it is structure: algorithms, key types and sizes, presence, status words, and sizes in 256-byte buckets (`Redaction.sizeRange`, never an exact length). There is no field for the MRZ, the CAN, any data group's contents, a data group hash, a key's bytes, or a certificate's identity.

### `authenticated`

| Field | Type | Meaning |
|---|---|---|
| `credential` | `MRZ` or `CAN` | The kind of credentials used. Never their values. |
| `access` | list of `AccessAttempt` | Every attempt to open the chip, in the order made: each PACE suite in EF.CardAccess's order, then BAC (MRZ only). |
| `accessStopped` | enum or null | Why attempts ended early: `KEY_REJECTED` (6300 or 63Cx), `BLOCKED` (6983, 6984), `CONDITIONS_NOT_SATISFIED` (6985 after a failure), `CAN_SINGLE_ATTEMPT_FAILED`, `DECLINED_BY_USER`, `CAN_NEEDS_PACE`. |
| `readUnder` | int or null | Index in `access` of the attempt whose session the files were read in (the first that opened the chip). |
| `com` | object or null | EF.COM: `ldsVersion`, `unicodeVersion`, `dataGroups` (numbers it lists, ascending), `size`. |
| `dataGroups` | list | One `DataGroupObservation` per DG1 to DG16 (see below). |
| `sod` | object or null | EF.SOD, without hash values (see below). |
| `dg14` | object or null | DG14's SecurityInfos in the chip's order (see below). |
| `dg15` | object or null | The Active Authentication key as `keyType`, `keyBits`, `curve`, `size`. Not the key. |
| `cardSecurity` | object or null | EF.CardSecurity's SecurityInfos in order, with `paceCamPresent`. Read only where the chip offers a PACE-CAM suite. |
| `cvca` | object or null | EF.CVCA: `size` and `carCountry` (the first two characters of the certification authority reference), `hasAlternateReference`. |
| `activeAuthentication` | object or null | Null if the chip has no AA key. `succeeded` (the answer to INTERNAL AUTHENTICATE with a random 8-byte challenge verified against the DG15 key), `algorithm` (for example `ECDSA-SHA256`, `RSA-ISO9796-2-SHA1`), `statusWord` on failure. |
| `chipAuthentication` | object or null | Null if DG14 has no Chip Authentication. `succeeded` (the protocol ran and a protected read works under the new keys), `algorithm` (for example `ECDH-AES-128`). |

`AccessAttempt`: `method` (`PACE` or `BAC`), `oid` (the PACE protocol OID, public), `mapping` (`GM`, `IM`, `CAM`), `cipher` (`AES-128`, `AES-192`, `AES-256`, `3DES`), `parameterId`, `succeeded`, `statusWord` (the failing step's, else null), `statusClass` (`OK`, `KEY_REJECTED`, `BLOCKED`, `CONDITIONS_NOT_SATISFIED`, `SECURITY_STATUS`, `NOT_FOUND`, `NOT_SUPPORTED`, `WRONG_LENGTH`, `NO_ANSWER`, `OTHER`), `camVerified` (for a CAM suite that succeeded: the chip's key from EF.CardSecurity fits the mapping, ICAO 9303-11 4.4.3.5).

`DataGroupObservation`: `number`, `inCom`, `inSod`, `selectStatusWord`, `readStatusWord`, `size`. Only the first 4 bytes of a data group are read (its BER header), enough for its length, and are erased at once. For DG3 and DG4 without Terminal Authentication `readStatusWord` is the refusal (`27010` = `0x6982`).

`sod`: `size`, `digestAlgorithm`, `signatureAlgorithm`, `ldsVersion`, `unicodeVersion`, `hashedDataGroups` (the numbers it hashes, never the hashes), `certificateCount`, and `signer`: `signatureAlgorithm`, `keyType`, `keyBits`, `curve`, `issuerCountry` (two letters), `validityMonths` (the length of the validity period, not its dates). Not the serial number, key identifier, names or bytes.

`dg14` and `cardSecurity`: a list `infos` of `{kind, oid, version, parameterId, keyType, keyBits, curve}`. `kind` is `PACE`, `PACE_DOMAIN_PARAMETERS`, `CHIP_AUTHENTICATION`, `CHIP_AUTHENTICATION_PUBLIC_KEY`, `TERMINAL_AUTHENTICATION`, `ACTIVE_AUTHENTICATION`, `EF_DIR` or `OTHER`. `parameterId` is the PACE domain parameter id or the Chip Authentication key id. A key is its type and size.

### Safety rules the section records

- Credentials are validated first (MRZ check digits with weights 7-3-1, plausible dates, a CAN of 6 digits); an invalid set sends no command.
- Each PACE suite is tried at most once, then BAC once (MRZ only). A refused key (6300, 63Cx), a blocked status word (6983, 6984) or 6985 after a failure ends the attempts.
- A CAN is tried once, after the person confirms, and a failure of any kind ends it. A chip with no PACE gets no CAN attempt.
- BAC's own repeat of a refused EXTERNAL AUTHENTICATE (JMRTD resends it with Le 0) is not sent, so one attempt is one wrong cryptogram.

### Redaction

A record with `authenticated` lists three further items in `redactions`, after the manifest's own (and a record without one must not): the signer certificate's serial number, key identifier, names and bytes; EF.CVCA beyond its country; and data group contents beyond the BER header (`RedactionManifest.authenticatedEntries`). A record without `authenticated` lists the manifest only.

### Lint rules added

Ingest lint (`AuthenticatedLint`, run by `RedactionLint.check` after the strict decode) rejects, in the `authenticated` section: an algorithm, version, OID, curve, key type, country or cipher that is not the shape its field has; a hash-like string (40 or more hex characters, or a long base64 mix) in any string; MRZ-like text; a size that is not a 256-byte bucket; a status word beyond 16 bits; a data group number outside 1 to 16, or repeated; a key size outside 64 to 16384 bits; a validity over 1200 months; a BAC attempt that names a PACE suite; `camVerified` on a suite that is not CAM; a success whose `statusClass` is not `OK`; a `readUnder` that names no attempt.

### Schema version: the decision

Phase 2 added `authenticated` (and Phase 3 `additionalProbePacks` and the step fields) as optional additions without a bump, which an older strict reader (`ignoreUnknownKeys = false`) would reject on a record that has them. The repository's policy bumps on any change; the fix is **version 2**, taken because version 1 records can be kept readable cleanly: `CaptureJson.decode` accepts both and upgrades version 1 in memory, and the version 2 change that did alter meaning (`fingerprint` becoming the generation key) is exactly what the upgrade recomputes. Older strict readers now refuse a version 2 record by its `schemaVersion`, with a clear message, instead of by an unknown-field error. `MeasuredProfile` (`MEASURED_PROFILE_SCHEMA_VERSION`) stays at 1: profiles are derived files that capture intake regenerates from the captures, none is stored from before the change, and their new fields (`matchesDefault`, `heldOutFromDifferentReader`) default to false.
