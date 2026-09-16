# Phase 2 — Detection Architecture Refactor

## Goal

Create one owner for the CameraX → MediaPipe → landmark → classifier pipeline while preserving the existing gesture recognition and action behavior.

## Changes

- Added `GestureDetectionEngine` under `android/app/src/main/java/com/hci/gesturetouchless/detection/`.
- Moved CameraX image analysis, MediaPipe HandLandmarker, landmark flatten/rotation, classifier invocation, and detection-confidence filtering into the engine.
- `GestureDetectionService` now owns one engine instance and remains responsible for gesture-to-action dispatch and the existing 5-second cooldown.
- The service owns the CameraX `Preview` use case too; `MainActivity` only supplies its `PreviewView` surface through a local service binding, so the Activity does not own the camera.
- `MainActivity` no longer creates or owns CameraX, MediaPipe, or `GestureClassifier` instances. It is now a UI/permission/service entry point.
- Removed the Activity lifecycle handoff that previously stopped the service and restarted a second detection pipeline in the Activity.

## Intentionally deferred

The following are **not** changed in Phase 2 so behavior can be compared against the baseline:

- 5-second gesture cooldown/state semantics
- classifier temporal smoothing
- hardcoded `-90°` landmark rotation
- camera resolution/frame-rate tuning
- classifier buffer allocation/runtime ownership
- accessibility capability cleanup
- settings/DataStore migration

Those belong to later phases in the roadmap.

## Verification

The local environment could not execute the Gradle wrapper because the Gradle distribution was not cached and outbound access to `services.gradle.org` is unavailable. The project was therefore not claimed to have a locally successful build. GitHub Actions should be used for the authoritative compile check.

## Expected ownership

```text
MainActivity
    │
    │ starts
    ▼
GestureDetectionService
    │
    ▼
GestureDetectionEngine
    │
    ├── CameraX ImageAnalysis
    ├── MediaPipe HandLandmarker
    └── GestureClassifier
            │
            ▼
       gesture + confidence
            │
            ▼
GestureDetectionService
            │
            ▼
   ACTION_PERFORM_GESTURE
            │
            ▼
GestureAccessibilityService
```
