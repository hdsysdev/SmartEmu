# PassportEmu and Precheck video plans

Make two videos: a 75-second demo showing the experience, and a three-minute tutorial showing how to repeat it. Both use PassportEmu on one Android phone and the Precheck app from `../../Projects/precheck-apps` on the reader phone.

## Video 1 Short demo

Purpose: show what PassportEmu does and how it works with Precheck. Use actual app recordings, brief essential captions and no narration.

| Time | Scene |
|---|---|
| 0:00–0:10 | Show both phones. Introduce “PassportEmu creates the test document; Precheck reads it.” |
| 0:10–0:25 | In PassportEmu, load sample details, briefly edit a field, and show the specimen photo page. |
| 0:25–0:40 | In Precheck, show **Passport Details Page**, capture the displayed specimen, and briefly confirm the document details. |
| 0:40–1:00 | Tap Precheck's **Start Reading** on **Biometrics Capture**. Show the phones back to back, with Precheck's circular reading progress beside PassportEmu's step indicators. |
| 1:00–1:10 | Show Precheck's actual completion or error message and PassportEmu's result. Use **Capture Finished** when the rehearsed read succeeds. |
| 1:10–1:15 | Briefly show PassportEmu presets and finish with “Create. Scan. Read. Test.” |

## Video 2 How to use it with Precheck

Purpose: help a user complete their first read. Use actual app recordings and brief essential captions. No voice-over or extra promotional copy.

| Time | Action and explanation |
|---|---|
| 0:00–0:20 | Explain the two-phone setup. Enable NFC and open a prepared Precheck test check at the passport-capture step. |
| 0:20–0:50 | In PassportEmu, tap **Use sample details → Edit details**. Explain fictional holder details and the optional photo, then tap **Done**. |
| 0:50–1:25 | Tap **Use this passport → Show photo page**. In Precheck's **Passport Details Page**, photograph the whole displayed page. Review the captured document details and continue. |
| 1:25–2:05 | Return from PassportEmu's full-screen page and tap **Next: hold phones together**. In Precheck's **Biometrics Capture**, tap **Start Reading**. Align the phones back to back and hold still. Show both progress displays. |
| 2:05–2:25 | Show the real Precheck outcome and acknowledge its completion dialog when successful. Compare the specimen with the captured details. Explain that successful capture alone does not prove government issuance. |
| 2:25–2:45 | Show **Try another**, an unusual-details preset, and **Past reads**. Explain that each new specimen needs a fresh capture in Precheck. |
| 2:45–3:00 | Show **Help**. Recap: check NFC and alignment if nothing happens; check matching details if the chip cannot unlock. |

## Preparation and recording

- Use a Precheck test environment and test check. Match its date of birth to the PassportEmu holder; Precheck uses the check's birth date, document number, and expiry date to unlock NFC.
- Rehearse photo capture, details confirmation, NFC reading, and completion with the exact builds and phones. PassportEmu uses test certificates, so capture Precheck's observed response rather than promising verification or fault rejection.
- Record both screens and one external camera angle for scanning and NFC placement. Keep labels **PassportEmu phone** and **Precheck phone** visible in paired shots.
- Keep the same specimen throughout each read. Use guided mode and adapted cryptography for the initial Precheck demonstration. Use fictional data and a demonstration portrait.
- Record one complete successful take, then the edit, presets, history, and help inserts. Reuse the footage for both edits; preserve the full reading sequence in the tutorial.

These plans follow the current source flow. Actual device compatibility and Precheck outcomes still need a rehearsal. Developer controls can be covered in a separate follow-up.

## Recorded revision

The delivered videos are [the 75-second demo](demo-videos/passportemu-precheck-demo.mp4) and [the three-minute tutorial](demo-videos/passportemu-precheck-tutorial.mp4). They use full-size portrait screen recordings of both apps running on the connected physical Pixel 4a, with no audio and only essential step instructions. App-code fixtures supply the camera image, matching check data and NFC callbacks. There is no persistent fixture label in either video. The recordings demonstrate both production UIs; they do not document a physical two-phone NFC transfer.

The actual specimen is John Smith, number 123456789, DOB 1 January 1980, expiry 1 January 2030, GBR, with a demonstration emu portrait. The edit alternates between the apps' full-screen progress rather than reconstructing their UI or drawing phone frames. Build details, raw recordings, fixture source and reproducible editing instructions are in [the recording README](demo-videos/README.md).
