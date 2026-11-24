# CAPCAKE-LAB

Isolated stability and architecture workspace for the CAPCAKE Android video editor.

## Project status

This repository starts from the CAPCAKE `main` baseline. Work here focuses on repository health, deterministic timeline tests, persistence safety, and incremental architecture improvements.

**Out of scope:** implementing Cross Dissolve or RIFE. Their feature branches, model weights, native dependencies, and CI download steps are not included in this workspace.

## Build requirements

- JDK 17
- Gradle 8.9
- Android SDK 35

Run locally:

```bash
gradle testDebugUnitTest
gradle lintDebug
gradle assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions runs the same verification steps and uploads the debug APK as a workflow artifact.

## Project conventions

- Application ID and namespace remain `com.kanzz.editor`.
- Timeline positions and durations use milliseconds.
- Preserve existing dependencies and features; add dependencies only when justified.
- Do not commit signing keys, secrets, generated APKs, or model weights.
- See [ARCHITECTURE.md](ARCHITECTURE.md) for boundaries and release gates.
