# SmartEmu capture mode: plan v2 (measured chip profiles)

Supersedes `smartemu-capture-mode-handoff.md`. Repo: `/Users/hubertdudowicz/OtherProjects/SmartEmu`, branch `master`.
Do not revert the uncommitted working tree. Do not commit unless asked. Never run `git push`.

## 1. Goal and what "accurate" means

The phone reads a real passport or ID card as a reader would, runs a fixed set of safe probes, and saves a **redacted**
record of how the chip behaved. Records are merged into shared profiles. The emulator replays them, and a transcript diff
shows where the emulation matches the real chip.

**Fidelity contract (use this wording everywhere):**
"MEASURED = indistinguishable from the real chip at APDU level on probe pack vN, for the fields marked compared, from n
captures." Link-layer properties (NFC-B, UID, ATS, pre-SELECT behaviour) are listed as **not emulatable on Android HCE**
and are never claimed.

## 2. Audiences and deliverables

| Audience | Gets |
|---|---|
| Anyone on the team (non-technical) | Guided capture screen, one-page capture report, how-to-capture guide, glossary |
| Testers / support | Report answering "what does this document offer, and which profile does it match?"; field-failure triage by asking for a capture |
| Maintainers | Coverage matrix (country × document × status × n) to prioritise which documents to capture next |
| Engineers / QA | JSON schema, probe packs, fidelity diff in CI, reader compatibility matrix (open-source readers) |
| Legal / DPO | One-page consent and retention rule, redaction manifest, sign-off before Phase 2 |

## 3. Decisions to settle first (Phase 0, ask the user; recommendations given)

1. **Where profiles live.** Recommend `profiles/` in SmartEmu for merged profiles (CODEOWNERS, PR template with redaction
   checklist). Per-capture records go in a separate restricted store with a retention limit (suggest 12 months). Merged
   profiles carry only counts and random capture IDs.
2. **CAN (ID cards) in Phase 2.** Recommend yes: single attempt with a confirm step. Counter behaviour is unknown per
   issuer, so treat it as unsafe to repeat.
3. **Consent and retention policy.** Own documents or consenting colleagues only, never customers'. Record
   `SELF | CONSENTING` in the capture, with no names. DPO sign-off before any authenticated capture (MRZ and DG data is
   processed in memory even though it isn't stored).
4. **Build gating.** Capture mode ships only in internal builds or behind a hidden toggle until (3) is signed off.
5. **Profile steward.** Name an owner for review and merges.

Use `mattpocock-skills:grilling` for 1–5, and `mattpocock-skills:domain-modeling` to write a GLOSSARY and the term
"generation" (see Phase 1).

## 4. Cross-cutting rules

### 4.1 Redaction: allowlist, on the phone, before saving
- Captures are built only by **typed extractors** that can emit approved fields. No raw transcript is ever serialised.
- The APDU logger is in-memory only. No logcat output of APDUs. No MRZ or CAN in saved instance state, and both are
  cleared after use.
- `FLAG_SECURE` on entry and capture screens. No keyboard personalised learning on MRZ/CAN fields. Capture storage
  excluded from backup.
- Never store: DG1, DG2, DG7, DG11, DG12 contents, the MRZ, the CAN, the UID, per-chip AA/CA public keys, EF.SOD data-group
  hash values, per-chip parts of EF.CardSecurity.
- **Quasi-identifiers:** DG sizes rounded or stored as ranges. Capture date to the month only. Random capture IDs not
  linkable to a device. n=1 captures of rare document classes (diplomatic, tiny issuers) are held for review before they
  are shared.
- The capture lists what was removed, and the phone shows a **"what will be saved" preview** before save or share. That
  screen is the consent moment.
- **Second line of defence at ingest:** a redaction lint (MRZ patterns, unknown fields, high-entropy blobs) rejects bad
  submissions.
- **Canary test:** run a synthetic passport with known MRZ and name bytes through capture and assert none appear in the
  serialised output.

### 4.2 Probe safety tiers
- **Tier A (always safe):** unauthenticated reads, SELECT variants, out-of-sequence commands, wrong lengths, largest READ
  BINARY, odd-INS READ BINARY (B1).
- **Tier B (confirm first):** unauthenticated but unusual: aborted PACE starts, command chaining, class bytes, odd SELECT and READ BINARY. Nothing that carries a key or password; could still trip a chip's anomaly handling.
- **Tier C (sacrificial or test cards only, bounded):** wrong-key BAC attempts, at most five, each from a fresh SELECT and challenge. The runner refuses a Tier C pack unless `acknowledgeSacrificialCard = true`. Behaviour after an aborted PACE start (no password tested) is Tier B. PACE with a wrong password and repeated CAN failures are excluded in every tier. See `docs/probe-packs.md`.
- Never: PIN or PUK attempts, or repeated CAN failures.
- Abort the run on `6983`, `6984` or any "blocked" status word.
- Every step re-SELECTs and is resumable, because tag-lost is the normal failure mode.

### 4.3 Reader-side confounders
`IsoDep.getMaxTransceiveLength()` and `isExtendedLengthApduSupported()` mostly describe the **phone's NFC controller**,
and Android exposes only the historical bytes, not the full ATS (no FSCI/FWI).
- Record the capturing device (model, Android version, NFC chipset) in every capture.
- Infer the chip's extended-length support from EF.ATR/INFO and probes.
- Merge across devices only when fields agree, and flag disagreements.
- Timing is recorded as min/median/max over repeats and is informational. It is never diffed exactly.
- "UID random" = the 0x08 prefix heuristic, confirmed by a second tap. Store a boolean only.

### 4.4 Format
- kotlinx.serialization JSON with `schemaVersion`, a published JSON Schema, and a migration policy. Add golden-file tests
  so the schema isn't defined only in Kotlin.
- `source: ANDROID_READER | PCSC | PROXMARK`, so lab captures from USB readers can be ingested.
- Provenance is **per field**: SPECIFICATION < REPORTED < ASSUMED stay as is, and `MEASURED` is added with capture IDs
  and sample count.
- Probes have IDs and versions. Results are keyed by probe ID and pack version.

## 5. Phases

Each phase ends with `mattpocock-skills:code-review`. Use `mattpocock-skills:tdd` throughout, with probes and redaction
tested against `HceCardService`. Baseline: 468 tests with 10–11 known unrelated failures (see the handoff).

### Phase 0: Decisions, schema skeleton, round-trip harness
- Settle section 3.
- Define the capture schema, extractor interfaces and redaction manifest (no UI yet).
- **Round-trip harness (JVM):** profile → emulator (`HceCardService`) → capture → profile′, asserting profile ≈ profile′.
  This tests the capture engine with no real document. Fields that capture can't observe stay ASSUMED, and the harness
  produces that list.
- Add the canary redaction test.
- **Done when:** the round trip passes for all 29 profiles on observable fields, the unobservable-field list is written
  down, and the canary test is green.

### Phase 1: Reader mode, logger, unauthenticated capture (zero-PII release)
- `NfcAdapter.enableReaderMode` (NFC-A and B, skip NDEF, high `IsoDep` timeout) with `IsoDep` → SCUBA `CardService`,
  wrapped by an in-memory command/response logger with timing. Separate screen (the phone can't emulate and read at once).
- Capture: tech A/B, historical bytes / hi-layer response, controller limits (labelled as reader-side), UID-random
  boolean, raw EF.CardAccess in order, EF.ATR/INFO (2F01), EF.DIR (2F00), status words for BAC commands and GET
  CHALLENGE before authentication.
- **Fingerprint and "generation":** a hash over the chip-fixed observations (CardAccess, application SELECT, ATR/INFO, DIR; probe answers are left out so that captures of one generation can disagree and be checked). After a scan the app says "matches profile
  X, n=3, no disagreements" or "NEW variant".
- Guided UX, plain-language errors, auto-resume, "what will be saved" preview, export through the share sheet.
- **Capture report** (shareable text or PDF) with country and document type, protocols offered,
  and matched profile.
- Docs: how-to-capture guide, consent one-pager, glossary.
- **Done when:** the team can run it on their own documents with no MRZ entry, and the output contains no personal data.
- This is the broad-collection release. Collect for a few weeks before building Phase 2.

### Phase 2: Authenticated reads (needs DPO sign-off)
- Validate MRZ check digits before attempting. Try every offered PACE suite, plus BAC. CAN for ID cards, single attempt
  with a confirm step.
- Record: EF.COM and the DGs present with rounded sizes. From EF.SOD: digest and signature algorithms, LDS version, DS
  certificate. DG14/DG15 reduced to algorithms, curves and key sizes. EF.CardSecurity structure, EF.CVCA, the DG3/DG4
  refusal status word, and whether AA and CA succeed.
- Run `security-review` on the full save and share path before anything leaves the phone.
- **Done when:** the canary test passes on the authenticated path, the redaction lint is in CI, and the manifest lists
  every removed item.

### Phase 3: Behaviour probe packs
- Packs by tier (4.2), fixed and versioned, with a status word and timing summary per step.
- **Done when:** the pack runs end to end against the emulator in the JVM and the round trip includes probe results.

### Phase 4: Profiles as data, replay and diff
- Migrate the 29 Kotlin profiles to JSON incrementally (the loader supports both during the move).
- Merge captures per generation and flag disagreeing fields. Emulator changes: replay captured EF.CardAccess bytes,
  replace `ErrorResponses` with a per-command status-word map, add max read length and file sizes.
- **Fidelity diff:** every field has a status of *compared*, *structural* (randomised or cryptographic) or *not
  comparable* (with a reason).
  - **Hold-out rule:** build the profile from captures 1..N-1 and diff against capture N. A replay that only matches the
    capture it was built from earns nothing.
  - The JVM diff is necessary but not sufficient, because it bypasses Android's HCE stack. Add a second-phone run as a
    release gate.
  - Deviations go in a reviewed allowed-deviation file.
  - The CI output is a human-readable table.
- MEASURED is awarded **per field**. The UI shows a per-profile fidelity badge (for example "Measured, n=3") and an
  "emulation limits" panel.
- Generate `docs/chip-profile-evidence.md` from the JSON.
- **Done when:** the diff runs in CI against golden captures, and at least one profile has MEASURED fields that pass the
  hold-out rule.

### Phase 5: Team-wide outputs
- **Intake:** share to a Drive or Slack folder, then a maintainer script validates schema and redaction lint and opens
  the PR. No git needed for contributors.
- **Coverage matrix** generated by CI and published as a page.
- **Reader compatibility matrix:** each profile against open-source readers already used here (JMRTD with SCUBA and
  Bouncy Castle) in the JVM, on a schedule. Profiles a reader can't complete are labelled "reader unsupported" with the
  reason.
- Optional PCSC/Proxmark transcript importer.

## 6. Risks

| Risk | Mitigation |
|---|---|
| PII leaks through capture or logs | Allowlist extractors, in-memory logger, canary test, ingest lint, security review |
| A probe locks a real document | Tiers, abort on blocked status words, sacrificial cards for Tier C |
| Overclaiming fidelity | Fidelity contract wording, per-field MEASURED, hold-out rule |
| Phone differences mistaken for chip properties | Device metadata, merge only on agreement |
| Plan too large for one pass | Phase 1 is a standalone release. Later phases are separately shippable |
| Re-identification by rare documents | Rounded sizes, month-only dates, review hold for n=1 rare classes |

## 7. Open items for the user
- Answers to section 3 (items 1–5).
- Whether this plan should live in the repo (for example `docs/capture-mode-plan.md`) or be published as a page for the
  team.
