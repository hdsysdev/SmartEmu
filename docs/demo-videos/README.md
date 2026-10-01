# PassportEmu + Precheck device videos

- [75-second demo](passportemu-precheck-demo.mp4)
- [Three-minute tutorial](passportemu-precheck-tutorial.mp4)

For the next editing pass, read [the agent handoff](HANDOFF.md) for side-by-side layouts, improved instructions, supporting graphics and animation.

Both videos use Android `screenrecord` footage from the physical connected Pixel 4a (1080 × 2340). They retain the apps' portrait layout and production UI. There is no voice-over, music or audio stream. Brief instructions cover only the Android status bar; app controls remain visible. There are no fixture labels in the videos, as requested. The SRT files contain the same brief instructions already shown in the edits.

The previous animated previews and narrated tutorial have been replaced.

## Recording fixtures

These are app-code fixtures, not a verified two-phone NFC session. Both apps were recorded sequentially on the same physical Pixel. The production composables and view models render all visible screens; fixture hosts supply a fixed camera image, in-memory check/document data and deterministic reader events. The completion dialog demonstrates the production success presentation without claiming a physical NFC transfer or government authenticity verification.

The specimen uses John Smith, passport number `123456789`, DOB `1980-01-01`, expiry `2030-01-01`, nationality `GBR`, and CAN `123456`. It starts with the real **Use sample details** action, changes Doe to Smith through the real editor, and adds the app's emu portrait. Precheck's camera fixture is cropped and rotated from the actual PassportEmu specimen screenshot. Its check DOB and document fields match that specimen.

PassportEmu uses `SmartEmuApp` and `PassportSimulatorViewModel` with a debug `RecordingNfcRepository`. The normal progress reducer and read-history store handle the injected reader events. The debug host is in `composeApp/src/androidDebug/kotlin/com/hddev/smartemu/recording/RecordingActivity.kt`.

Precheck comes from `../../Projects/precheck-apps`. Its debug host uses the production `PrecheckPagePhotoDisplayView`, `DocumentDetailsView`, `DocumentBiometricsView`, `DocumentBiometricsViewModel`, and dialog factory. Fixture services override only document/check storage; camera and reader responses are deterministic. The host lives at `app/src/androidDebug/kotlin/com/t4connex/precheck/recording/RecordingActivity.kt`. A copy of its source, manifest and camera fixture is kept in `fixtures/precheck/`.

The recording package names are `com.hddev.smartemu.recording` and `com.t4connex.precheck.rightcheck.debug.recording`. Both use separate application IDs, and Precheck uses its own FileProvider authority. Existing installed app data was preserved. Neither host is included in release source sets.

## Build and reproduce

Both repositories add an optional debug `applicationIdSuffix = ".recording"` when Gradle property `recordingFixtures=true` is supplied. Build from their respective repository roots:

```sh
# SmartEmu
./gradlew :composeApp:assembleDebug -PrecordingFixtures=true --offline

# precheck-apps
./gradlew :app:assembleRightcheckDevDebug -PrecordingFixtures=true --offline
```

For Precheck's Google Services plugin, the local `app/src/rightcheckDev/debug/google-services.json` derives from the existing rightcheckDev configuration with an additional recording-package client. That configuration is intentionally not copied into these video assets. Local dependencies and development configuration must already be available.

Install the builds onto an explicitly selected physical device:

```sh
adb -s "$DEVICE_SERIAL" install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb -s "$DEVICE_SERIAL" install -r ../../Projects/precheck-apps/app/build/outputs/apk/rightcheckDev/debug/app-rightcheck-dev-debug.apk
```

Run these from SmartEmu. Complete each phase before starting the next, and leave the fixture activities alive between phases so read history is retained:

```sh
python3 docs/demo-videos/source/record_device.py --serial "$DEVICE_SERIAL" --phase smart
python3 docs/demo-videos/source/record_device.py --serial "$DEVICE_SERIAL" --phase extras
```

Before building Precheck, crop the newly recorded specimen into its camera asset:

```sh
ffmpeg -y -i docs/demo-videos/recordings/specimen-screen.png \
  -vf 'crop=1036:1472:22:502,transpose=2' -frames:v 1 \
  ../../Projects/precheck-apps/app/src/debug/assets/recording-specimen.png
```

Rebuild and reinstall Precheck, then record its flow and the additional preset selection:

```sh
python3 docs/demo-videos/source/record_device.py --serial "$DEVICE_SERIAL" --phase precheck
python3 docs/demo-videos/source/record_device.py --serial "$DEVICE_SERIAL" --phase preset
```

The driver targets the physical Pixel's 1080 × 2340 layout and 1.0 font scale. It uses accessibility text for taps and selected-device ADB for recording. The recording hosts skip authentication and introductory setup. Check and document storage for the recorded flow is in memory.

Edit the saved footage using Python with Pillow, ffmpeg and ffprobe:

```sh
/usr/bin/python3 docs/demo-videos/source/edit_device.py --video all
```

The editor uses only the recorded app pixels, trims pauses, accelerates parts of the short demo, and holds the last real frame when Android ends an idle recording early. The tutorial preserves the reading sequences and cuts between the two apps' progress. `source/demo-edit.json` and `source/tutorial-edit.json` document each source trim, playback rate, caption and timeline position. Raw MP4 takes and reference screenshots are in `recordings/`.

## Validation

Both fixture APKs built successfully and ran on the physical Pixel. The saved specimen, Precheck confirmation fields, progress screens, completion dialog, result details, preset selection, history and help screens were visually checked. Final videos are 75 and 180 seconds, H.264, 1080 × 2340 at 24 fps, with no audio streams. Full video decode and representative-frame review are recorded in `validation.json`.
