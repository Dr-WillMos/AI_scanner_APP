# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Assemble debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

Open the project in Android Studio and run the `app` module directly for interactive development.

## Project Overview

**ShortVideoGuard** — an Android demo that overlays a floating ball on top of other apps, records 5 seconds of screen content via MediaProjection, uploads the video to a backend for risk analysis, and displays risk-level banners (safe/medium/high) as overlay UI.

- Package: `com.example.ai_scanner`
- Language: Java 11
- Min/target SDK: 24 / 36
- No OCR, ASR, or on-device AI — this is a UI+recording+upload pipeline demo.

## Architecture: Three-Component Pipeline

1. **`MainActivity`** — Entry point. Checks and requests `SYSTEM_ALERT_WINDOW` permission. Launches `FloatingOverlayService` once granted. Handles permission state jitter with delayed re-check (`PERMISSION_RECHECK_DELAY_MS` = 500ms).

2. **`ScreenCapturePermissionActivity`** — Invisible bridge activity. Launches the system `MediaProjection` permission dialog via `ActivityResultLauncher`, forwards the result to `FloatingOverlayService` via intent actions (`ACTION_START_CAPTURE` / `ACTION_CAPTURE_DENIED`), then calls `finishAndRemoveTask()`.

3. **`FloatingOverlayService`** — The central hub (~1500 lines). Manages all overlay windows, the recording session, file I/O, network upload, and state machine. Owns the detection lifecycle: idle → permission check → recording (5s) → upload → banner display (3s auto-hide).

## Key Overlay Windows (all managed by FloatingOverlayService)

- **Floating ball** (`view_floating_ball.xml`) — draggable, docks to edge on release, opens control panel on tap.
- **Control panel** (`view_control_panel.xml`) — "检测" (detect) and "设置" (settings) buttons.
- **Top status banner** (`view_top_status_banner.xml`) — shows countdown during recording, risk result after analysis, auto-hides after 3s for risk results. `...` button opens detail dialog.
- **Result dialog** (`view_result_dialog.xml`) — detailed risk breakdown (score, AI glitch prob, violence prob).
- **Mid-risk bar / High-risk dialog** — legacy views retained for compatibility; not in the primary flow.

## Detection State Machine

```
Idle → ControlPanelVisible → PermissionCheck → Recording (5s) → Uploading → RiskBannerVisible (3s) → Idle
```

State guards (`isChecking`, `capturePermissionPending`) prevent re-entry. Post-recording, the file is saved to `Movies/ShortVideoGuard` via MediaStore and uploaded as `multipart/form-data`.

## Recording Subsystem

- `MediaProjection` + `VirtualDisplay` + `ImageReader` (idle surface) + `MediaRecorder`
- H.264 / MP4 / 30fps / screen-native resolution
- Caches `MediaProjection` result data to avoid re-prompting on subsequent detections
- Detects token expiry (`SecurityException`) and triggers re-authorization
- Foreground service with `mediaProjection` type (required on Android Q+)

## Network

- Endpoint: `ANALYZE_ENDPOINT` (default `http://localhost:2333/analyze`)
- Protocol: `POST` multipart/form-data with `file` field
- Timeout: 15s connect/read
- Currently has `USE_LOCAL_ANALYZE_MOCK = true` — cycles through low/medium/high mock results instead of real network calls
- Upload runs on `uploadExecutor` (single-threaded), UI updates via `mainHandler.post()`
- JSON response: `risk_level`, `risk_score`, `ai_glitch_prob`, `violence_prob`, `inference_time_ms`, `status`

## Permissions

- `SYSTEM_ALERT_WINDOW` — required for overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — required for recording
- `INTERNET` — for upload
- `usesCleartextTraffic="true"` — allows HTTP (demo only)
- Screen capture permission is obtained at runtime via `MediaProjectionManager.createScreenCaptureIntent()`

## Configuration Switches (in FloatingOverlayService)

| Constant | Default | Purpose |
|---|---|---|
| `USE_LOCAL_ANALYZE_MOCK` | `true` | Skip real upload, cycle mock results |
| `ANALYZE_ENDPOINT` | `http://localhost:2333/analyze` | Backend URL (used when mock is false) |
| `AUTO_FINISH_DELAY_MS` | `5000` | Recording duration |
| `RISK_BANNER_AUTO_HIDE_MS` | `3000` | Result banner auto-dismiss |
| `NETWORK_TIMEOUT_MS` | `15000` | HTTP connect/read timeout |
