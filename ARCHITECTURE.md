# CAPCAKE Architecture and Stability Notes

## Scope of this lab repository

This repository is an isolated engineering workspace based on the CAPCAKE main branch. Stability work must preserve existing editor behavior and application identity.

Cross Dissolve implementation and RIFE implementation are explicitly out of scope. Do not import their feature branches, model weights, native libraries, JNI/CMake work, or CI downloads into this branch. The existing transition metadata and existing AI Motion Blur code are preserved as-is unless a separate, explicitly approved bug fix is required.

## Layer boundaries

- Timeline/domain logic: deterministic Kotlin data and command logic; no Android View or Media3 dependencies.
- Media models: clip/source ranges, track placement, and per-clip settings.
- Persistence: versioned project data; legacy fields must have safe defaults and corrupt input must not destroy an existing in-memory project.
- Composition/export: isolate composition creation and export lifecycle incrementally; do not combine large UI rewrites with behavior changes.
- GL effects: explicit ownership and release of GL resources; keep effect ordering deterministic.
- UI: translate gestures/actions into domain commands and render current state.

## Safe refactor sequence

1. Add deterministic tests around existing timeline behavior before changing algorithms.
2. Test split, invalid split, trim/source bounds, ripple delete, move/collision, snap, undo/redo, keyframe replacement, and viewport filtering.
3. Add codec compatibility tests for current and legacy project data before changing persistence formats.
4. Extract export/composition code in small behavior-preserving steps with regression builds.
5. Keep each change reviewable; avoid mass formatting and broad MainActivity rewrites.

## Release gates

- Unit tests, Android lint, and debug APK build pass in CI.
- Validate on physical devices with multiple clips, overlays, audio, trim/split, and repeated export.
- Keep release signing keys and generated artifacts out of Git.
- Confirm third-party dependency licenses and record required notices before distribution.
- Do not distribute unlicensed model weights or native dependencies.
