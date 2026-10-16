# Capture intake

`intake.sh` is the maintainer's tool for turning capture files shared by contributors into merged profiles. The flow and the rules are in [docs/profile-steward.md](../../docs/profile-steward.md#intake-flow); this page is how to run it.

```
tools/capture-intake/intake.sh --input DIR [--store DIR] [--repo DIR] [--apply] [--link GENERATION=PROFILE-ID]... [--branch NAME]
```

- Dry run unless `--apply`. A dry run reads, validates, merges in memory and reports; it writes and moves nothing.
- `--store` (default `$CAPTURE_STORE`, else `~/captures-store`) is the restricted per-capture store. It must be outside the repository.
- `--branch NAME` makes a local branch and one local commit of `profiles/*.json` and the generated docs. It never pushes, and prints the push command for you to run.
- Exit status: 0 when the report's last line is `RESULT: CLEAN`; 3 for `REJECTIONS` or `DEVIATIONS`; 1 if the tool could not run.

It calls the Gradle task `:composeApp:captureIntake`, which runs `com.hddev.smartemu.capture.intake.IntakeMain` on the unit-test classpath, so the lint and the merge are the app's own code. You can call the task yourself:

```
./gradlew -q :composeApp:captureIntake -Pintake.input=DIR -Pintake.store=DIR -Pintake.repo=. [-Pintake.apply=true] [-Pintake.links=GEN=ID,GEN=ID]
```

The tests are `CaptureIntakeTest` (`./gradlew :composeApp:testDebugUnitTest --tests '*CaptureIntakeTest*'`).

The lab-capture tasks (`captureExportPack`, `captureImportTranscript`) are described in [docs/lab-captures.md](../../docs/lab-captures.md).
