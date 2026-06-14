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

Gradle 9.3.1, AGP 9.1.0, Java 11 source/target compatibility.

## Project Overview

**ShortVideoGuard** — an Android demo that overlays a floating ball on top of other apps, records 5 seconds of screen content via MediaProjection, uploads the video to a backend for risk analysis, and displays risk-level banners (safe/medium/high) as overlay UI. For high-risk results, it triggers emergency TTS voice warnings and sends SMS to a configured emergency contact.

- Package: `com.example.ai_scanner`
- Language: Java 11
- Min/target SDK: 24 / 36
- No OCR, ASR, or on-device AI — this is a UI+recording+upload pipeline demo.
- Single-module project (`:app` only, no library modules).

## Architecture

### Activities & Service

1. **`MainActivity`** — Entry point. Checks and requests `SYSTEM_ALERT_WINDOW` permission. Launches `FloatingOverlayService` once granted. Also requests SMS permission, shows a developer info dialog with network diagnostics on first launch, and performs a backend health check (`GET /actuator/health`) on service start.

2. **`ScreenCapturePermissionActivity`** — Invisible bridge activity. Launches the system `MediaProjection` permission dialog via `ActivityResultLauncher`, forwards the result to `FloatingOverlayService` via intent actions (`ACTION_START_CAPTURE` / `ACTION_CAPTURE_DENIED`), then calls `finishAndRemoveTask()`.

3. **`FloatingOverlayService`** — The central hub (~2000 lines). Manages all overlay windows, the recording session, file I/O, network upload, emergency response, and state machine. Owns the detection lifecycle: idle → permission check → recording (5s) → upload → banner display (3s auto-hide).

4. **`SettingsActivity`** — Settings screen with toggles/text inputs persisted to `SharedPreferences`: floating ball enabled, emergency contact phone, SMS notification toggle, TTS voice warning toggle, API base URL, API Key, mobile data upload toggle.

5. **`HistoryActivity`** — RecyclerView-based history list with infinite scrolling (page size 20), on-start remote sync from backend, and room database persistence.

### Overlay Windows (all managed by FloatingOverlayService)

- **Floating ball** (`view_floating_ball.xml`) — draggable, docks to edge on release, opens control panel on tap.
- **Control panel** (`view_control_panel.xml`) — author ID input, "检测" (detect) and "设置" (settings) buttons.
- **Top status banner** (`view_top_status_banner.xml`) — shows countdown during recording, risk result after analysis, auto-hides after 3s for risk results. `...` button opens detail dialog.
- **Result dialog** (`view_result_dialog.xml`) — detailed risk breakdown (score, AI glitch prob, violence prob, trigger rules, reasoning, transcription).
- **Mid-risk bar / High-risk dialog** — legacy views retained for compatibility; not in the primary flow.

### Detection State Machine

```
Idle → ControlPanelVisible → PermissionCheck → Recording (5s) → Uploading → RiskBannerVisible (3s) → Idle
```

State guards (`isChecking`, `capturePermissionPending`) prevent re-entry. Post-recording, the file is saved to `Movies/ShortVideoGuard` via MediaStore and uploaded as `multipart/form-data`.

### Recording Subsystem

- `MediaProjection` + `VirtualDisplay` + `ImageReader` (idle surface) + `MediaRecorder`
- H.264 / MP4 / 30fps / screen-native resolution (capped at 1920×1920, 4 Mbps bitrate, max file size 10 MB)
- Caches `MediaProjection` result data to avoid re-prompting on subsequent detections
- Detects token expiry (`SecurityException`) and triggers re-authorization
- Foreground service with `mediaProjection` and `dataSync` types (Android Q+)

### Database & History (Room)

- **`AppDatabase`** — Room database singleton (`aiscanner.db`), version 2 with a migration from v1 (adds `request_id`, `upload_status`, `error_message`, `retry_count`, `video_uri` columns).
- **`HistoryEntity`** — Room entity (`history` table): `id`, `deviceId`, `authorId`, `riskLevel`, `score`, `createdAt`, `reason`, `source`, `transcription`, `requestId`, `uploadStatus`, `errorMessage`, `retryCount`, `videoUri`.
- **`HistoryDao`** — DAO with CRUD, paged queries, upload status filtering, and partial update via COALESCE.
- **`HistoryRepository`** — Mediates between local Room storage and remote backend (`GET /api/v1/history` with cursor-based pagination). `syncRemoteToLocal()` loops until `hasMore=false` per the API doc incremental sync spec, using `afterId` (latest local ID) for cursor-based sync.

### Network & Upload

- Endpoint: `{api_base_url}/api/v1/detect` (base URL configurable in Settings, default `http://10.0.2.2:8080` from strings.xml)
- Auth: `X-API-Key` header, configurable in Settings. Backend supports root keys (unlimited) and dynamic device keys (rate-limited to 20 req/60s).
- Protocol: `POST` multipart/form-data with `file` field, `boundary` header
- Timeout: 30s connect/read
- Retry: up to 3 attempts with exponential backoff (2s/4s/8s delays). Retryable status codes: 429, 502, 500, 503.
- Upload runs on `uploadExecutor` (single-threaded), UI updates via `mainHandler.post()`
- API reference: `docs/APP_API_REFERENCE.md` — the authoritative document for request/response schemas, error codes, and auth model.

### API Key Lifecycle

**`ApiKeyManager`** handles the full key lifecycle per APP_API_REFERENCE.md section 3:

1. **First-launch auto-registration**: `MainActivity` calls `ApiKeyManager.ensureKeyRegistered()` on startup. If no key exists, it `POST`s to `/api/v1/keys/register` with `deviceId` and `deviceName` (Build.MODEL), then persists the returned `apiKey` to SharedPreferences.
2. **401 auto re-registration**: When any API call receives a 401 response, both `FloatingOverlayService` (upload) and `HistoryRepository` (history fetch) call `ApiKeyManager.registerKey()` to obtain a fresh key, then retry the original request with the new key. Only if re-registration itself fails does the user see an auth error.
3. **User override**: The Settings page still allows manual key entry, which takes precedence over the auto-registered key.

### Rate Limit Monitoring

Both `FloatingOverlayService` and `HistoryRepository` read `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` response headers. When remaining quota drops to 3 or fewer, the user sees a warning toast. The upload flow also respects `Retry-After` on 429 responses.

### 400 Error Differentiation

The upload flow parses the server's 400 error message body to show specific user guidance:
- "视频文件为空" / empty → `upload_error_empty_video`
- "损坏" / corrupted → `upload_error_corrupted_video`
- "仅支持" / MP4 format → `upload_error_not_mp4`
- "缺少" / missing params → `upload_error_missing_params`
- "过大" / too large → `upload_error_video_too_large`

### Emergency Response (High-Risk Only)

When risk level is `HIGH`:
1. TTS voice warning plays with the warning message.
2. SMS is sent to the configured emergency contact via `SmsManager`.
3. Both features are toggleable in Settings and require corresponding permissions.

### Mock Backend

A Python Flask mock server is available at `docs/server_fake/mock_ai_server.py`:

```bash
pip install flask && python docs/server_fake/mock_ai_server.py
```

Listens on port 8080. Supports `POST /api/v1/detect` (sync), `POST /api/v1/detect/async` (async with in-memory task storage), `GET /api/v1/detect/<taskId>/status`, and `GET /actuator/health`. Returns a configurable `FIXED_RESULT` (default: SAFE).

### Utility Classes

- **`ApiKeyManager`** — Manages API key lifecycle: first-launch auto-registration (`POST /api/v1/keys/register`), 401-triggered re-registration, persistence to SharedPreferences.
- **`DeviceIdProvider`** — Generates/persists a device ID from `Settings.Secure.ANDROID_ID`, falling back to `UUID.randomUUID()`.
- **`NetworkInfoHelper`** — Collects network state (connectivity, type, SSID, IPs). Provides `isMobileData()` check for mobile-data upload gating.
- **`UploadRetryPolicy`** — Retry configuration constants (45s client timeout, 3 retries, exponential backoff, retryable status codes and exception types).
- **`CrashLogger`** — Custom `UncaughtExceptionHandler` that writes crash details to a text file in the app's cache directory.

## Permissions

- `SYSTEM_ALERT_WINDOW` — required for overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` + `FOREGROUND_SERVICE_DATA_SYNC` — required for recording
- `INTERNET` — for upload
- `SEND_SMS` — for emergency SMS notifications
- `usesCleartextTraffic="true"` — allows HTTP (demo only)
- Screen capture permission is obtained at runtime via `MediaProjectionManager.createScreenCaptureIntent()`

## Configuration Switches (in FloatingOverlayService)

| Constant | Default | Purpose |
|---|---|---|
| `AUTO_FINISH_DELAY_MS` | `5000` | Recording duration |
| `RISK_BANNER_AUTO_HIDE_MS` | `3000` | Result banner auto-dismiss |
| `NETWORK_TIMEOUT_MS` | `30000` | HTTP connect/read timeout |
| `MAX_CAPTURE_WIDTH` / `MAX_CAPTURE_HEIGHT` | `1920` | Capture resolution cap |
| `MAX_VIDEO_BITRATE` | `4000000` | MediaRecorder bitrate cap |
| `MAX_VIDEO_FILE_SIZE` | `10485760` | Max video file size before upload (10 MB) |
| `RECORD_FPS` | `30` | Recording frame rate |

Runtime-configurable settings (stored in SharedPreferences, keyed by strings.xml resource IDs):
- `api_base_url` — Backend base URL (default `http://10.0.2.2:8080`)
- `api_key` — API key for `X-API-Key` header
- `floating_enabled` — Toggle floating ball visibility
- `sms_enabled` / `tts_enabled` — Emergency notification toggles
- `mobile_data_enabled` — Allow upload over cellular data
- `emergency_phone` — Emergency contact phone number

## Tests

Current test coverage is minimal — two placeholder tests in `ExampleUnitTest.java` and `ExampleInstrumentedTest.java`. No meaningful unit or integration test suite exists.
