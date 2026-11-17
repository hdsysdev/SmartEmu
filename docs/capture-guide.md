# How to capture a passport or ID card

Capture mode lets your phone read a real passport or ID card the way a reader would, and save a short record of how the chip behaves. The record holds no personal data. It is used to make the PassportEmu profiles match real documents.

Screen and button names below are indicative and may differ slightly in your build.

**Capture mode is in developer builds only.** It is compiled in only when the app is built with `./gradlew :composeApp:assembleDebug -Ppassportemu.captureMode=true`, and it is never in published APKs.

Use your own document, or one whose holder has agreed in writing. Never use a document that someone handed to you for another purpose, or that you found. Read the [privacy notice for capture mode](capture-consent-and-retention.md) first.

## What you need

- An Android phone with NFC, with NFC switched on.
- The document, out of its cover or wallet.
- About two minutes, and a steady hand. A tag lost halfway is the most common failure.
- For the authenticated read only (see the last section): the document's MRZ or, for an ID card, its CAN.

You do not need the document number, the MRZ or the CAN for the standard capture.

## Steps

1. Open your developer build of PassportEmu and turn on **Advanced mode** in Settings, then tap the NFC icon in the top bar (or **Open capture mode** in Settings). This is a separate screen from the emulator. The phone cannot read and emulate at the same time.
2. Tick whether the document is **yours** or belongs to a **consenting holder**. No names are recorded, only that choice.
3. Tap **Start**. Put the document flat against the back of the phone, over the NFC antenna. The antenna is usually near the camera or the middle of the back. Try both if nothing happens.
4. Hold still until the screen says the read is finished. Do not lift the document during the run. Plain-language messages tell you what is happening.
5. Review the **What will be saved** screen. It lists what the record contains and what was left out. Saving or sharing from this screen is your consent. If anything looks like personal data, stop and do not share the file. Tell the profile steward by opening an issue that does not include the file.
6. Choose **Share** and send the file (see below). Or choose **Discard**.

The screen also shows a one-page report: the country and document type, the protocols the chip offers, and which existing profile it matches. If it says **NEW variant**, the chip behaves differently from every profile so far, and your capture is especially useful.

## What the phone does and does not save

Saved:

- The phone model, Android version and NFC chipset.
- Whether the chip answered as NFC-A or NFC-B, and its historical bytes.
- Whether the chip's identifier looks random (yes or no only).
- Public files the chip gives to anyone: EF.CardAccess, EF.ATR/INFO and EF.DIR.
- The status words the chip returned to the probe commands, with rough timings.
- The capture month (not the day) and a random capture ID.

Never saved: the MRZ, the CAN, the chip's identifier, any photo or personal data group, per-chip keys, the document-signing hash values, exact file sizes (they are rounded), the capture date and time, and the raw command log. The command log only exists in memory while the run is going.

The phone also blocks screenshots on the entry and capture screens, and keeps nothing in backups (the saved captures and the flag that unlocks capture mode are excluded from both cloud backup and device transfer). A capture you save stays on the phone for 30 days and is then deleted the next time capture mode opens. **Share** writes a temporary copy to the phone's cache for the share sheet and does not save the capture.

## If it fails

| What you see | What to do |
|---|---|
| Nothing happens when you touch the document | Check NFC is on. Move the document slowly across the back of the phone. Remove thick cases. |
| "Tag lost" | Hold it still and start again. The run resumes where it stopped, and the commands that count are not repeated. If the phone says "This looks like a different document, starting again", you tapped another document than the first one: the first capture was dropped and a new one started. |
| "Chip not supported" | Not every card is an eMRTD. Note the document type and, if you like, mention it in an issue. |
| "Stopped: the chip reported it is blocked" | The run stopped on purpose to protect the document. Do not retry. Open an issue (no files, no document details) if you want to report it. The same stop happens when the chip answers with a retry-counter hint (`63Cx`) or refuses an out-of-sequence EXTERNAL AUTHENTICATE with `6300`: those mean it may have counted a failure. |
| The phone says the capture is partial | You can still share it. Missing steps are marked absent, not failed. |

Never keep trying to force a read. The standard capture is safe to repeat, but if you are unsure, stop.

## How to send the file

**Captures are not accepted publicly yet.** There is no upload page and no shared folder, and you should not attach a capture file to an issue or a pull request. If you have a capture and would like to offer it, open an issue that says so (country, document type and year only, no file, no personal details). The profile steward ([hdsysdev](https://github.com/hdsysdev)) will arrange a way to hand it over privately. Share with **Share** only when you have agreed that with the steward. The file is JSON, named after its capture ID. Send only that file, and do not add notes that name the holder, the document number or the issue date.

The steward checks the file against the schema, runs the redaction lint, and turns the merged, redacted profile into a pull request that uses the [profile template](../.github/PULL_REQUEST_TEMPLATE/profile.md). You do not need git. You can ask for your capture to be deleted at any time: see [retention](capture-consent-and-retention.md#retention).

## Authenticated read (MRZ or CAN)

This section applies only to developer builds that include the authenticated read. Use it only on your own document or one whose holder has agreed in writing, and read the [privacy notice](capture-consent-and-retention.md) first.

Why: some behaviour is only visible after the chip has been unlocked, such as which data groups it has, the algorithms it uses, and whether Active and Chip Authentication succeed.

1. On the capture screen, choose **Authenticated read**.
2. Enter the **MRZ** (passport: document number, date of birth and expiry date as printed in the machine-readable zone) or, for an ID card, the **CAN** (the six-digit number printed on the card front). **Type each MRZ field with its check digit on the end**, as the MRZ line prints it (for example `L898902C36`, `7408122`, `9406236`). The check digits are required: the app checks them before it talks to the chip, and will not start without them, because a mistyped key is a wrong key and a wrong key can count against the document. Fix any typo it flags.
3. **The key is tried once, and the read stops at the first sign of trouble.**
   - **MRZ:** the app tries each PACE suite the chip lists, once, then BAC once. The only failure it goes past is a plain "not supported" at the very start of a suite, before any key was sent. Any other failure after that (a refused key, `6982`, `6988`, a bad answer, no answer) ends the whole read: the app does not try another suite or BAC.
   - **CAN: one attempt only.** Counter behaviour for repeated wrong CANs is unknown for each issuer, so the app asks you to confirm before it tries, and then tries once. If it fails, do not re-enter it. Check the number on the card and stop and check the number on the card before another go, and ask in an issue if you are unsure. If a CAN failed earlier in the same session, the app asks again with an extra confirmation (it does not know whether this is the same document) before any other CAN is tried.
   - **A lost tag in the middle of a CAN attempt:** the app does **not** retry by itself. It says the attempt may already have counted against the card and asks again; you must tick that you understand before it tries the CAN a second time. If in doubt, stop.
   - If you tap a different document after a lost tag, nothing is tried with the key you typed (it was for the first document): the app starts a new capture of the new document, without the authenticated part.
4. Hold the document against the phone until the run finishes, then review **What will be saved** as above.

**What the chip is sent before the key.** The authenticated read sends no EXTERNAL AUTHENTICATE of its own before the real attempt: the out-of-sequence EXTERNAL AUTHENTICATE probe of tier-a, and the BAC-commands observation that goes with it, are left out of an authenticated capture, so that your key's attempt is the first one the chip's BAC counter sees. The other tier-a probes (SELECT variants, READ BINARY lengths, GET CHALLENGE lengths) do run first, in the unauthenticated state they are meant for. If you also want the out-of-sequence probe for the same document, make a separate standard capture of it.

What is kept: which data groups exist (sizes rounded), the algorithms and curves in use, the LDS version, the document-signer certificate, and whether authentication succeeded.

What is not saved: the MRZ and the CAN. They are held in memory only, cleared when the run ends, and kept out of saved instance state, logs and the keyboard's learned words. The data groups with personal content (DG1, DG2, DG7, DG11, DG12) are read in memory to check they exist and are never written down. Per-chip keys and the document-signer hash values are dropped.

Never use the authenticated read to try PINs or PUKs. The app has no such step.
