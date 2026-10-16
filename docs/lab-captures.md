# Lab captures: PC/SC and Proxmark transcripts

A chip can be captured with a USB reader on a PC (PC/SC) or with a Proxmark instead of the phone. The lab operator sends the probe pack's commands, records the exchanges as a text **transcript**, and the importer replays that transcript to the same capture runner the phone uses. The result is a normal capture record with `source` set to `PCSC` or `PROXMARK`, built by the same typed extractors and checked by the same redaction lint, so it goes through intake like any other capture ([profile-steward.md](profile-steward.md)).

Nothing in a transcript is saved. The importer reads it, builds the redacted record, and writes only that. Keep the transcript out of git and out of shared folders: a response from a chip can hold personal data, and the importer's job is to leave it out of the record, not out of your disk.

The same safety rules as on the phone apply ([probe-packs.md](probe-packs.md)): own or consenting colleagues' documents only, Tier A freely, Tier B after a confirm, **Tier C on a sacrificial or test card only**. A Tier C transcript is refused by the importer unless you pass the sacrificial-card flag.

## 1. Get the command sheet

```
./gradlew :composeApp:captureExportPack \
  -Pexport.packs=tier-a/2 -Pexport.out=sheet.txt -Pexport.reader="ACS ACR1252U"
```

| Property | Meaning |
|---|---|
| `export.packs` | Packs and versions, comma separated, main pack first: `tier-a/2` or `tier-a/2,tier-b/1`. The published versions are in [probe-packs.md](probe-packs.md); an older version stays importable. |
| `export.out` | The file to write. |
| `export.reader` | Optional. A name for your reader, 1 to 64 letters, digits and `. _ + / ( ) -` (no personal data). Default: `PC/SC lab reader` or `Proxmark lab reader`. |
| `export.extended` | `false` if your reader can't send extended-length APDUs. The extended steps are then left out, as the phone leaves them out when its controller can't send them. |
| `export.source` | `PCSC` (default) or `PROXMARK`. |

The sheet is made by running the real capture runner, so it can't drift from the app. Each command is a line `> <hex>`, and a `# <step>` comment before it names the step. The sheet starts with directives (`#! source`, `#! packs`, ...) that the importer reads.

The sheet covers the whole run, in the order the runner sends it: first the fixed commands that read the public files (EF.CardAccess, EF.ATR/INFO, EF.DIR), select the application and probe BAC, then every probe's set-up, preamble, repeats and step. Send **exactly** these commands, in this order, and nothing else. Nothing in the sheet carries a key, a CAN or an MRZ.

**One thing depends on the chip.** The sheet assumes each public file fits one READ BINARY of 223 bytes (`00B00000DF`). If your EF.CardAccess is longer, the runner sends the next READ BINARY at the offset where the last ended, with the same Le (`00B000DFDF`, then `00B001BEDF`, and so on). Add those commands after the first one and record their responses. The importer's mismatch message tells you the command it expected where the transcript has something else.

## 2. Record the transcript

For each command, send it to the chip and write the response (data followed by the status word) on the next line, starting with `<`:

```
# a comment, ignored
#! source: PCSC
#! reader: ACS ACR1252U
#! extended-length: true
#! packs: tier-a/2

# ef-card-access
> 00A4000C023F00
< 9000
> 00A4020C02011C
< 9000
> 00B00000DF
< 31143012060A04007F0007020204020202010202010C9000
```

| Line | Meaning |
|---|---|
| `> <hex>` | A command APDU. Spaces in the hex are fine. At least 4 bytes. |
| `< <hex>` | The response APDU: data, then the 2-byte status word. At least 2 bytes. |
| `# ...` | A comment, ignored. |
| `#! name: value` | A directive. Known: `source` (`PCSC` or `PROXMARK`), `reader` (the name), `extended-length` (`true` or `false`), `packs` (`tier-a/2, tier-b/1`). An unknown directive is an error, so a typo is not ignored silently. |

Every `>` is followed by exactly one `<`. A tag lost half-way is not a transcript: capture again.

A script can do this for you: read the sheet, send each `>` line, and print the sheet back with a `<` line after each command. A transcript can also come straight from a PC/SC or Proxmark log if you convert it to this format.

## 3. Import it

```
./gradlew :composeApp:captureImportTranscript \
  -Pimport.transcript=transcript.txt -Pimport.out=./to-share
```

| Property | Meaning |
|---|---|
| `import.transcript` | The transcript file. |
| `import.out` | A folder. The capture is written to `<out>/<capture id>.json`. |
| `import.source` | `PCSC` or `PROXMARK`. Optional if the transcript has `#! source`. |
| `import.packs` | Optional if the transcript has `#! packs`. |
| `import.sacrificial` | `true` to declare that the card is sacrificial, which a Tier C pack needs. |

The runner then asks the chip (the transcript) for the same things it asks a phone. It checks that the n-th command it sends is the n-th command in the transcript, and answers with the recorded response. It fails, naming the exchange number, the transcript line and both commands, if:

- the transcript has a different command at that position ("sent X but the transcript has Y"): the operator sent another command or the same ones in another order;
- the transcript ends before the run does ("ends after N exchanges"): an exchange is missing;
- the transcript has more exchanges than the run used ("N more exchanges"): extra commands were sent.

The error never prints a response, only commands. A record is written only if it passes the redaction lint ([capture-schema.md](capture-schema.md)) and its fingerprint matches its contents; otherwise the importer says which rule failed, by field, and writes nothing.

The file is ready for the maintainer's intake. Share it like a phone capture.

## What a lab capture shows

- The reader is the lab reader: `reader.model` is the name you gave, `androidVersion` is `n/a`, the controller's maximum transceive length is unknown (null), and extended-length support is what the transcript's `extended-length` directive says (true by default). Link-layer fields are not known to the importer, so `link` is plain NFC-A without historical bytes. Merge and diff treat the source as a reader-side confounder ([capture-schema.md](capture-schema.md)).
- Timing is not recorded: a replay has none. Timing is informational and is never diffed in any case.
- The chip's answers are exactly what the transcript says. A lab capture is as good as the recording.

## Testing

`TranscriptTest` records a session from the emulator, imports it, and asserts the record equals the one captured directly, apart from the source, reader, capture id and timing. It also runs the exporter's sheet through a simulated operator and checks the mismatch errors above.

| What | Command |
|---|---|
| The tests | `./gradlew :composeApp:testDebugUnitTest --tests '*TranscriptTest*'` |
