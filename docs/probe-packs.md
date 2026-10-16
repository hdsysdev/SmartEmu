# Probe packs

A probe pack is a fixed, versioned set of probes run against a real chip. Results are keyed by probe id and pack version, so a change to a probe is a new pack version, never an edit.

## Tiers

| Tier | Rule | Pack |
|---|---|---|
| **A** | Always safe. Unauthenticated, no counters touched. SELECT variants, out-of-sequence commands, wrong lengths, largest READ BINARY, odd-INS READ BINARY (B1). | `tier-a` |
| **B** | Unauthenticated but unusual. Confirm first. Could trip a chip's own delay or lockout heuristic; sends no key, no cryptogram, no password. | `tier-b` |
| **C** | Sacrificial or test cards only. May touch a counter: bounded wrong-key BAC attempts. | `tier-c` |

Never, in any tier: PIN or PUK commands (INS 20, 24, 2C) or repeated CAN failures. The CAN repeated-failure probe is excluded entirely, including from Tier C, and PACE with a wrong password is excluded too. Each pack lists what it leaves out, and why, in its `excluded` field; the lists are the reviewer's checklist for a new probe.

Rules for every run:

- Abort on a status word that means the chip may have counted a failure, and record `aborted: BLOCKED_STATUS_WORD`. Nothing more is sent after one, and a draft that ended this way is not resumed. In Tier A and B (and every probe outside Tier C) these are `6983`, `6984`, **any `63Cx`** (a retry-counter hint) and `6300` as the answer to an EXTERNAL AUTHENTICATE. Tier C, whose design is a bounded run of wrong-key attempts, is the one exception: it carries on through the `6300` and `63C2` and up that wrong keys are expected to get, and stops at `63C1`, `63C0`, `6983` and `6984`. An authenticated read has its own attempt policy (see [capture-guide.md](capture-guide.md)).
- A run that sent an EXTERNAL AUTHENTICATE records that in its draft, even if the tag was lost before the answer. A resume never sends one again: the probe that holds it is left out of the record (it is missing, not failed), and so is anything else that sends one.
- A resume reads the application SELECT and the public files again and compares them with the draft's (no UID is kept). If they differ the draft is thrown away and the run starts again under a new capture id, and the phone says "This looks like a different document, starting again".
- A step whose setup could not reach the state it starts from is not run: if SELECT of the master file is refused (anything but `9000`), the steps that start from the master file are recorded with no status word and the setup's status word, which says why; so are the steps that start from EF.CardAccess unless that SELECT worked. Steps that start from the application still run.
- Every step re-SELECTs first (its `setup`), so steps do not depend on each other, and is resumable. Tag lost is the normal failure mode.
- Every step stores a status word, a rounded response size and a timing summary (min, median, max over repeats; informational, never diffed exactly).
- Tier A and Tier B are safe to run in one session. `CaptureRunner(pack = ProbePack.tierA, additionalPacks = listOf(ProbePack.tierB))` records tier-a as `probePack` and tier-b in `additionalProbePacks`; every probe result carries its own pack's `packVersion` and `tier`. A draft only resumes a run of the same packs in the same order.
- Tier C is only offered when the user declares a sacrificial or test card. `ProbePack.requiresSacrificialCardAcknowledgement` is true for it, and `CaptureRunner.run` throws `SacrificialCardNotAcknowledgedException` before sending anything unless the runner was built with `acknowledgeSacrificialCard = true`. The record does not carry that declaration, so Tier C results are accepted at ingest only from the profile steward.

Steps with a `preamble` (for example MSE:Set AT, then GENERAL AUTHENTICATE step 1, then the command being probed) redo their setup and preamble before every repeat. The answers to the preamble commands are not stored with the step: each preamble command is also a step of its own in the same pack, which records it.

## Registry

`ProbePacks.all` lists every published pack. `ProbePacks.byId(id, version)` and `byRef` resolve a pack, and `probeOf`, `packOf`, `stepOf` and `packsOf(record)` resolve a probe result or a whole capture to the exact definition that produced it. A probe id is unique among the packs of one version, so a probe id and a pack version name one probe; a new version of a pack keeps the id of a probe it did not change (tier-a v2 has the ids of v1), and the version tells them apart. `ProbePack.tierA` is the newest tier-a (a new capture runs it), and `ProbePack.tierAV1` is v1, kept for the captures made under it. `ProbePack.definitionDigest` is a SHA-256 of the pack's step list, which tests pin so a published pack cannot change.

## tier-a

### Version history

| Version | Published | What changed |
|---|---|---|
| v1 | first | 7 probes, 45 steps. The out-of-sequence EXTERNAL AUTHENTICATE probe is the last probe, after the seven GET CHALLENGE steps of `get-challenge-lengths`, and the `bac-commands` extractor sent a third EXTERNAL AUTHENTICATE (and a GET CHALLENGE) before the pack. A chip that keeps a challenge pending across SELECT would see the pack's EXTERNAL AUTHENTICATE as a real, wrong BAC attempt. |
| v2 | review fix M1 | The same 45 steps with the same probe and step ids, but `external-authenticate-out-of-sequence` is the **first** probe. Each of its two steps runs from a fresh SELECT of the application, with no GET CHALLENGE before it in the run. The `bac-commands` extractor does nothing for tier-a v2 (it would put a GET CHALLENGE before the probe, or a third EXTERNAL AUTHENTICATE in the run): the record's `bacCommands` is taken from the pack's own `external-authenticate-out-of-sequence/lc-40-le-28` and `get-challenge-lengths/le-08` steps, the same two commands. A whole run therefore sends **at most two** EXTERNAL AUTHENTICATE commands, never with a challenge pending, and a test counts them over a full run, extractors included. An authenticated capture leaves the EXTERNAL AUTHENTICATE probe (and so `bacCommands.externalAuthenticate`) out altogether. |

v1 is immutable and stays in `ProbePacks.all` (a test pins its digest and its step list). `ProbePack.tierA` is v2: new captures run it, and `CaptureRunner`'s default is v2.

### tier-a v1 and v2

7 probes, 45 steps in each. Published: never edit. A test pins the probe and step ids and the digest of the step list for each version. Probes in v2 order (v1 has the same, with `external-authenticate-out-of-sequence` last):

| Probe | What it sends |
|---|---|
| `external-authenticate-out-of-sequence` | EXTERNAL AUTHENTICATE with zeros and no GET CHALLENGE before it, once for each of two command shapes, each from a fresh SELECT of the application. Real chips differ here (`6985`, `6982`, `6700`); one of the best fingerprints. First in v2, last in v1. |
| `select-aid` | SELECT of the eMRTD application with P2 `0C`, `00`, `04`, with and without Le, a partial AID, an unknown AID, and an Lc longer than the data. |
| `select-fid` | SELECT of EF.CardAccess, EF.CardSecurity, EF.COM, EF.SOD, DG1, an unknown file and the master file by file id, from the master file and from the application. |
| `read-binary-order` | READ BINARY before any SELECT, by short EF id, and with the odd INS `B1`. The short-id reads are from the master file (`9C`, EF.CardAccess) and, inside the application, `9E` (EF.COM) only: no data group is addressed by short id. A test refuses any other. |
| `read-binary-wrong-length` | READ BINARY of EF.CardAccess with Le 1 and 4, Le 0 at offset 1, an offset beyond the file, and Lc instead of Le. |
| `read-binary-max-length` | READ BINARY of EF.CardAccess with Le `DF`, `E0`, `FF`, `00` and the extended-length forms (skipped on a phone that can't send them). |
| `get-challenge-lengths` | GET CHALLENGE with Le 8, 0, 4, 7, 9, 16 and no Le, before authentication. |

## tier-b v1

11 probes, unauthenticated but unusual. Every step uses a fresh SELECT. Nothing carries a key, a cryptogram or a password, and no step sends EXTERNAL AUTHENTICATE.

| Probe | What it sends |
|---|---|
| `mse-set-at` | MSE:Set AT (`00 22 C1 A4`) with an unsupported OID, with the PACE ECDH-GM AES-128 OID and a CAN reference, with an MRZ reference, and with no OID. |
| `aborted-pace-start` | An MSE:Set AT, never followed by GENERAL AUTHENTICATE, then SELECT of the application, SELECT of EF.CardAccess, GET CHALLENGE, READ BINARY, or another MSE:Set AT. Shows whether a half-started PACE leaves state behind and whether a SELECT clears it. |
| `general-authenticate-chaining` | GENERAL AUTHENTICATE step 1 (`7C 00`, the nonce request) alone, after MSE:Set AT, twice in a row, and followed by a different command; and the same with CLA `10` (command chaining) then SELECT or a last command. Step 1 carries no password-derived data. |
| `class-select-aid`, `class-select-fid`, `class-read-binary`, `class-get-challenge` | The same SELECT, READ BINARY and GET CHALLENGE with CLA `00`, `0C`, `10` and `80`. CLA `0C` carries no secure-messaging data objects and no session exists, so no MAC is wrong. |
| `unknown-instructions` | GET DATA with unknown tags, GET RESPONSE, EMV GET PROCESSING OPTIONS, READ RECORD, SELECT PPSE, and the unassigned INS `02`, `30`, `5E`. |
| `select-p1-p2` | SELECT of the AID with P1 `00` to `09`, P2 `01` to `FF`, no data, a short AID, and file selection by path and as a child DF. |
| `read-binary-past-end` | READ BINARY of EF.CardAccess at offsets `0100` to `7FFF`, Le beyond the remaining bytes, SFI 0 (the current file), and a READ BINARY after a failed SELECT of an unknown file id. |
| `read-binary-sfi-from-master-file` | READ BINARY by short EF id (P1 bit 8 set): EF.CardAccess (`1C`), EF.DIR (`1E`), EF.ATR/INFO (`1F`), EF.CardSecurity (`1D`), and a reserved P1. **Only with the master file selected.** Inside the application the same P1 values name the data groups (`9E` is EF.COM, `81` is DG1) and could return personal data. A test refuses an SFI read with any other setup. |

## tier-c v1

Sacrificial and test cards only. One probe, `bac-wrong-key`, 8 steps:

1. GET CHALLENGE (the baseline).
2. Five attempts, each from a fresh SELECT and a fresh GET CHALLENGE: EXTERNAL AUTHENTICATE with one constant 40-byte cryptogram (the bytes `00` to `27`) that no BAC key derives. Each attempt is a separate step with one command, so the status word of each is kept and a delay, a change of answer or a lock shows as it happens.
3. GET CHALLENGE and READ BINARY of EF.CardAccess afterwards, to see whether the chip still answers.

The pack has no key, so no attempt can succeed. It carries on through `6300` and `63C2` and up, and stops at `63C1`, `63C0`, `6983` and `6984` (a retry counter at one or none left). A resume after a lost tag never repeats an attempt: the probe is left out. The number of wrong attempts is bounded at five; the pack never looks for the lock-out point.

Excluded from every pack, with no exception for sacrificial cards: PIN and PUK commands; repeated CAN failures (PACE with a wrong CAN); PACE with any wrong password, and GENERAL AUTHENTICATE from step 2; more than five wrong attempts; any attempt with a key that could be right; secure messaging with a wrong MAC; Terminal Authentication, Chip Authentication or Active Authentication with wrong data; anything that writes.

## Reader-side confounders

Recorded with every capture. See plan section 4.3.

| Observation | What it really describes | Handling |
|---|---|---|
| `IsoDep.getMaxTransceiveLength()` | The phone's NFC controller | Record as `controllerMaxTransceiveLength`. Infer the chip's limit from EF.ATR/INFO and probes. |
| `isExtendedLengthApduSupported()` | The phone's NFC controller | Record. Infer the chip's extended-length support from probes. |
| ATS | Android exposes only the historical bytes, no FSCI/FWI | Store only `historicalBytes`. |
| Device model, Android version, chipset | The reader stack | Always recorded. |
| Timing | Phone, field strength and chip | min/median/max over repeats. Informational. Never diffed exactly. |
| UID random | 0x08 prefix heuristic | Not recorded: `uidRandom` is null (unknown) unless a second tap with a different UID confirmed it, and never `false`. On NFC-B the identifier is a PUPI (changes per activation), so it says nothing there. No UID is read or kept. |

Merge across devices only when the fields agree. A disagreement is flagged and not averaged.

## <a id="not-emulatable"></a>Not emulatable on Android HCE

Captured and labelled, never claimed as MEASURED:

- NFC-B: HCE offers NFC-A only.
- UID, ATS and historical bytes: HCE can't set them.
- Behaviour before the eMRTD application is selected: Android answers only once the AID has been selected.

## Adding a pack version

Version bump policy: **a published pack version never changes**, not a command, not a step id, not a repeat count, not the order. Any change to a step, an added step, a removed step or a changed setup is a new version of the pack (`tier-b` v2), and the old version stays in `ProbePacks.all` for good. A new probe family that does not belong in an existing pack is a new pack id. Typos in `excluded` notes and comments are the only edits allowed to a published pack, and they do not change the step list. Use a new probe id for a new probe, and reuse an id only for a step that is unchanged.

1. Copy the previous pack definition into a new version. Change, add or remove steps there only.
2. Give every new probe and step a stable id: lower case, digits and `-`, at most 48 characters. A probe id is unique among the packs of one version.
3. Assign each probe a tier using the table above. Anything that could touch a counter is Tier C and needs the sacrificial-card acknowledgement. PIN, PUK and repeated CAN failures are not allowed in any tier.
4. Add the pack to `ProbePacks.all`, pin its `definitionDigest` in `PublishedPacksTest`, and keep the old digest test unchanged.
5. Run it against the emulator in the JVM (`EmulatorPacksTest`: every step gets a status word, the emulator never throws, no banned INS) and check the round trip still passes.
6. Update this doc, the schema if a new field is needed (see [capture-schema.md](capture-schema.md)). The ingest lint accepts any lower-case identifier for a pack or probe id, so it needs no list, but resolve ids with `ProbePacks` wherever the exact definition matters.
7. Old captures stay valid under the version they ran. Merging and diffing compare only results from the same pack version.
8. `mattpocock-skills:code-review` before release; a security review if the probe sends any new command class.
