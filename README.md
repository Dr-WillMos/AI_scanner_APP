# ShortVideoGuard (Demo Prototype)

This Android demo focuses on UI and interaction flow only.

## Current Flow

1. Launch app in `MainActivity`.
2. Tap `开启悬浮窗权限`, read the permission explanation dialog, and grant overlay permission.
3. Floating ball appears and stays above other apps.
4. Tap floating ball to open the white control panel (`检测` / `设置`).
5. Tap `检测` to request screen-capture permission, then record the screen for 5 seconds and save to the system video library (`Movies/ShortVideoGuard`).
6. During recording, a non-interruptive top banner shows the detecting countdown.
7. After 5 seconds, demo result rotates by cycle: safe top banner, medium-risk draggable bar, or high-risk modal dialog.
8. For high risk, use `立即退出` or `继续观看`; for medium risk, drag-to-edge or close for this cycle.

No OCR/ASR, network request, or AI inference is implemented.

## Key Components

- `MainActivity`: permission guidance, overlay permission request, and service startup.
- `FloatingOverlayService`: floating ball, drag-dock behavior, screen recording, top risk banners, medium-risk bar, and high-risk modal dialog.
- `ScreenCapturePermissionActivity`: system MediaProjection permission bridge.

## Run

Open project in Android Studio and run the `app` module on a device/emulator.
