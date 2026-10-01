# Handoff: improve the PassportEmu + Precheck videos

## Objective and user requirements

Rework the existing 75-second demo and three-minute tutorial into clearer, more polished instructional videos. Show the apps side by side when that makes their relationship easier to understand. Improve the instruction text, graphics, animations and pacing while keeping the recorded apps as the main visual content.

Carry forward these explicit user preferences:

- Use the actual app UI recorded on the connected physical device. Earlier custom animated UI previews were rejected because they did not look like the apps.
- Keep both videos without voice-over. The current exports have no audio streams; preserve that default.
- Limit added text to what a coherent demo or tutorial requires. Improve the instructions rather than adding promotional copy or explanatory paragraphs.
- Fixture distinctions do **not** need to remain visible in the videos. Keep provenance in the accompanying documentation; add no persistent fixture badge.
- Precheck's actual repository is `/Users/hubertdudowicz/Projects/precheck-apps`, reached from SmartEmu as `../../Projects/precheck-apps`.

The SmartEmu repository's on-screen app name is **PassportEmu**. Use that name in video labels. Use **Precheck** for the other app.

## Start here

Workspace: `/Users/hubertdudowicz/OtherProjects/SmartEmu`.

1. Watch both current exports and inspect the relevant raw takes. Identify where paired screens clarify the next action and where a single enlarged screen is easier to read. This step is complete when each scene has a chosen layout, purpose and source take.
2. Write a scene plan with timings and final short instruction text. Use the layout and copy guidance below; the original timing grid is a starting point, not an obligation to preserve every cut. Keep the overall durations at 75 and 180 seconds.
3. Build a representative preview containing photo capture, paired reading progress and the outcome. Check it at ordinary playback size. Settle screen scale, labels, caption position and motion before rendering the full videos.
4. Implement a reproducible edit from the **raw recordings**, then render both videos. Each scene should have a traceable source trim, playback rate, layout and overlay definition.
5. Review the complete exports, including every transition and graphic. Fix readability, timing and flow problems, then complete the verification checklist below.
6. Replace the current deliverables after verification. Update the README, edit manifests, caption sidecars, contact sheets and validation report to describe the new edit. Return both videos to the user.

This is an editing task first. Re-record only when a missing action, inadequate take or incompatible fixture state prevents a coherent scene.

## Asset map

All paths in this table are relative to `/Users/hubertdudowicz/OtherProjects/SmartEmu/docs/demo-videos/`.

| Asset | Use |
|---|---|
| `passportemu-precheck-demo.mp4` | Current 75-second baseline |
| `passportemu-precheck-tutorial.mp4` | Current 180-second baseline |
| `recordings/` | Original physical-device MP4 takes and reference screenshots; use these for the new edit |
| `source/edit_device.py` | Existing deterministic editor; extend or replace it for compositing and motion |
| `source/demo-edit.json`, `source/tutorial-edit.json` | Current timeline, trims, playback rates and captions |
| `source/record_device.py` | Selected-device ADB recording and UI-action driver |
| `fixtures/precheck/` | Copy of the Precheck recording host, debug manifest and specimen image |
| `README.md` | Fixture implementation, build/install commands and capture sequence |
| `validation.json` | Baseline export metadata and completed checks; refresh for the new exports |
| `demo-contact-sheet.png`, `tutorial-contact-sheet.png` | Baseline overview, not a substitute for watching the videos |

Original plan: `/Users/hubertdudowicz/OtherProjects/SmartEmu/docs/demo-video-plan.md`. Its recorded-revision section describes the current approach. The user requirements at the top of this handoff take precedence over the original plan's voice-over and external-camera suggestions.

### Raw footage to pair

| Stage | PassportEmu footage | Precheck footage | Editing opportunity |
|---|---|---|---|
| Setup | `smart-home.mp4` | Opening of `precheck-capture.mp4` | Briefly establish which app creates the specimen and which app captures/reads it |
| Create and edit | `smart-create.mp4` | — | Enlarge PassportEmu; show the real sample action and Doe → Smith edit |
| Photo page | `smart-show.mp4`, `smart-page.mp4`, `specimen-screen.png` | `precheck-capture.mp4`, `precheck-captured.png` | Pair the displayed specimen with the same image appearing in Precheck |
| Confirm details | Specimen page or real result screenshot | `precheck-details.mp4`, `precheck-details.png` | Show matching fields with restrained callouts |
| Start and align | `smart-hold.mp4` | `precheck-ready.mp4` | Make the order clear: prepare PassportEmu, tap Start Reading in Precheck, align phones |
| Read progress | `smart-read.mp4` | `precheck-read.mp4` | Pair the real progress displays; anchor timing to connection and completion |
| Outcome | End of `smart-read.mp4`, `smart-result.mp4` | `precheck-finished.mp4`, `precheck-acknowledge.mp4` | Show Capture Finished and PassportEmu's actual result together |
| More tests | `smart-presets.mp4`, `smart-preset-select.mp4`, `smart-history.mp4` | — | Show selection of the accented-name preset and the saved read |
| Troubleshooting | `smart-help.mp4` | — | Enlarge the expanded NFC/alignment advice; finish with a useful next action |

The raw clips include lead-in pauses and transitions. Their encoded duration can be shorter than the requested recording time: Android stops producing frames when a screen is idle. For example, `precheck-finished.mp4` is about 0.47 seconds of a static dialog. Hold its last recorded frame for a readable outcome shot. Probe every source rather than assuming its nominal recording duration.

## Layout, graphics and motion

Use a landscape master, initially 1920 × 1080, to make paired portrait screens practical. Choose the final resolution and scale after the representative preview is readable. The baseline exports are 1080 × 2340 portrait; their dimensions are an implementation detail that the new composition may change.

For paired scenes, keep **PassportEmu left / Precheck right** consistently. Add small app-name labels when needed to orient the viewer. Preserve recorded screen proportions and enough UI context to identify the page and action. Give the active app visual emphasis with a subtle highlight or scale change; keep the other app visible when its state explains what is happening.

For editing, document fields, presets and Help, use a larger single screen or an intentional close-up. A full portrait screen reduced to a tiny panel will undermine the tutorial. Review text at the size the user will actually watch, not only in a full-resolution still.

Supporting graphics should explain a relationship or physical action:

- A short connector from the displayed specimen to Precheck's captured-image area can explain photo capture.
- Field outlines can connect the matching passport number and expiry date. Show DOB as a separate check-setup requirement because Precheck's recorded confirmation form does not display it.
- A brief diagram of the phones' backs can explain back-to-back NFC placement. Treat it as an instruction graphic, then return attention to the real app screens.
- A small tap ring can identify a real recorded button press. Time it to that press and keep the label legible.
- Use short fades, slides or zooms to guide attention between layouts. Settle the motion before the viewer needs to read a field or tap target.

Use actual recorded pixels for app content. Graphics and animation support those pixels; rebuilding app screens, inventing controls or replacing app progress with a custom animation would repeat the rejected approach. Keep original app buttons, status text and completion messages readable, and keep overlays away from them.

### Pairing the read sequence

The two takes were recorded sequentially on one physical Pixel using coordinated fixtures. Inspect the recordings and align the displayed connection, reading and completion states into an understandable demonstration. Keep progress continuous within each app's track. Let a completed screen remain visible while the other finishes.

The apps express progress differently: PassportEmu uses milestones and Precheck uses data-group progress. Their percentages and milestones need not advance in lockstep. Synchronize meaningful state transitions rather than forcing numerical equivalence. Preserve the complete visible reading sequence in the tutorial; shorten waiting time in the demo without skipping the action that starts the read or its outcome.

## Instruction copy

Use one short action or relationship per beat. App labels establish identity; captions tell the viewer what to do. Time each instruction just before the relevant action and leave enough time to read it. Reuse wording across the demo and tutorial where the action is the same.

These are draft lines to refine after reviewing the footage, not mandatory text to put on every scene:

| Beat | Suggested instruction |
|---|---|
| Setup | “Enable NFC on both phones.” |
| Create | “Load sample details, then edit the holder.” |
| Photo capture | “In Precheck, capture the whole displayed page.” |
| Confirmation | “Confirm the passport number and expiry.” |
| Check setup | “Use the same birth date in the Precheck check.” |
| Start | “Tap Start Reading in Precheck.” |
| Alignment | “Hold the phones back to back and keep still.” |
| Outcome | “Compare the captured details.” |
| New preset | “Capture the new specimen again before reading.” |
| Help | “Check NFC, alignment and matching details.” |

Preserve the real button names when referring to taps: **Use sample details**, **Edit details**, **Done**, **Use this passport**, **Show photo page**, **Next: hold phones together**, **TAKE PHOTO OF DOCUMENT**, **NEXT**, **START READING**, **Try another**, **Past reads**, **Help**. The completion dialog's button is **Ok** in the current build.

The recorded result explains that “Not verified” is expected for a specimen signed by a sample authority. Show that existing result when useful; a successful capture must not be described as proof of government issuance. The video needs no additional disclaimer badge or long explanation.

## Fixture and device facts

Both apps' production composables run in separate debug recording hosts. Camera results, in-memory check/document records and NFC callbacks are controlled fixtures. The footage demonstrates the app experience; it is not evidence of an observed physical two-phone NFC transfer. Keep this fact in the project documentation and handoff, without a persistent label in the videos.

The main specimen is **John Smith**, passport **123456789**, DOB **1980-01-01**, expiry **2030-01-01**, nationality **GBR**, CAN **123456**, with the app's emu portrait. Precheck's camera image is cropped and rotated from the actual PassportEmu screen capture, and its check DOB and document fields match. The later accented-name preset intentionally changes the specimen; present it after the first read completes.

Recording device at capture time: physical **Pixel 4a**, ADB serial **11281JEC201534**, screen **1080 × 2340**, font scale **1.0**. An emulator was also connected; always select the physical device explicitly if re-recording. Recheck connected-device state before using the saved serial.

Recording packages:

- PassportEmu: `com.hddev.smartemu.recording`
- Precheck: `com.t4connex.precheck.rightcheck.debug.recording`

Original installed app data was preserved by separate application IDs and a separate Precheck FileProvider authority. Keep that isolation if changing fixtures. Recording hosts belong to debug source sets; preserve release behavior.

If re-recording is needed, read `README.md` for the exact build/install/capture sequence and `/Users/hubertdudowicz/Projects/precheck-apps/.agents/skills/run-precheck-apps/SKILL.md` for Precheck's local workflow. Precheck's recording Google Services config is local to that repository and is intentionally absent from these media assets. Check repository status before edits and preserve unrelated work; the fixture changes and media are currently uncommitted.

## Implementation notes and completion checks

The current editor uses Pillow to create caption images and ffmpeg to trim, retime, hold and concatenate takes. On this machine `/usr/bin/python3` has Pillow. The installed ffmpeg lacks `drawtext` and `subtitles` filters; the existing PNG-overlay method works. Vector or programmatically rendered graphics and timestamped image overlays are suitable for the new edit.

`source/edit_device.py` currently asserts portrait dimensions and assumes one source per scene. Update its layout model, dimension checks and manifests when adding paired tracks. The output SRT files currently mirror burned-in captions; keep sidecars consistent or document a deliberate change. `source/.edit-cache/` is ignored scratch space. Preserve the original raw takes while iterating.

Before handing back the improved videos, confirm:

- Both exports run for 75 and 180 seconds respectively, with no audio streams.
- Photo capture, NFC reading and outcomes use side-by-side views where they clarify the apps' relationship; detailed interactions remain readable.
- Added instructions are concise, correctly ordered and attached to the right app/action. The tutorial makes the matching check DOB requirement clear.
- Tap cues correspond to recorded actions; animations settle before reading; labels and graphics cover no essential controls or result text.
- Specimen identity remains consistent through capture, confirmation, reading and result comparison. The new preset appears after that sequence.
- Both apps' progress proceeds coherently to their actual recorded completion UI, and fixture provenance remains accurate in the README.
- Both MP4s decode fully without errors. Confirm codec, dimensions, frame rate, duration and absence of audio with ffprobe; run ffmpeg with `-xerror` for a full decode.
- Watch both complete final edits at normal playback size. Inspect representative frames for paired capture, paired progress, outcomes, preset selection and Help.
- The renderer, source references, timing/caption manifests, README, contact sheets and validation report reproduce and describe the delivered files.
