package com.example.ai_scanner;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class FloatingOverlayService extends Service {

    public static final String ACTION_START_CAPTURE = "com.example.ai_scanner.action.START_CAPTURE";
    public static final String ACTION_CAPTURE_DENIED = "com.example.ai_scanner.action.CAPTURE_DENIED";
    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    private static final long AUTO_FINISH_DELAY_MS = 5000L;
    private static final long COUNTDOWN_TICK_MS = 1000L;
    private static final long SAFE_BANNER_MIN_SHOW_MS = 2500L;
    private static final long RISK_BANNER_AUTO_HIDE_MS = 3000L;
    private static final long TOP_BANNER_REFRESH_DELAY_MS = 120L;
    private static final long PERMISSION_RECHECK_DELAY_MS = 450L;
    private static final int PANEL_MARGIN_DP = 8;
    private static final String RECORD_CHANNEL_ID = "screen_record_channel";
    private static final String UPLOAD_CHANNEL_ID = "upload_channel";
    private static final int RECORD_NOTIFICATION_ID = 1002;
    private static final int UPLOAD_NOTIFICATION_ID = 1003;
    private static final int RECORD_FPS = 30;
    private static final float BALL_ACTIVE_ALPHA = 0.8f;
    private static final float BALL_DOCKED_ALPHA = 0.3f;
    private static final float BALL_DISABLED_ALPHA = 0.45f;
    private static final int NETWORK_TIMEOUT_MS = 30000;
    private static final int IO_BUFFER_SIZE = 8192;
    private static final int MAX_CAPTURE_WIDTH = 1920;
    private static final int MAX_CAPTURE_HEIGHT = 1920;
    private static final int MAX_VIDEO_BITRATE = 4_000_000;
    private static final long MAX_VIDEO_FILE_SIZE = 10 * 1024 * 1024;
    private static final String TAG = "FloatingOverlayService";

    private WindowManager windowManager;
    private MediaProjectionManager mediaProjectionManager;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader idleImageReader;
    private MediaRecorder mediaRecorder;
    private ParcelFileDescriptor recordingPfd;
    private Uri recordingUri;
    private Intent cachedCaptureResultData;
    private int cachedCaptureResultCode = Activity.RESULT_CANCELED;

    private View floatingBallView;
    private View functionPopupView;
    private View topBannerView;
    private View midRiskView;
    private View highRiskDialogView;
    private View resultDialogView;

    private WindowManager.LayoutParams floatingBallParams;
    private WindowManager.LayoutParams functionPopupParams;
    private WindowManager.LayoutParams topBannerParams;
    private WindowManager.LayoutParams midRiskParams;
    private WindowManager.LayoutParams highRiskDialogParams;
    private WindowManager.LayoutParams resultDialogParams;

    private ImageView ballIcon;
    private TextView countdownText;
    private TextView bannerTitleText;
    private TextView bannerCountdownText;
    private Button bannerMoreButton;

    private Button highRiskExitButton;
    private Button highRiskContinueButton;
    private TextView resultStatusText;
    private TextView resultRiskLevelText;
    private TextView resultLine1Text;
    private TextView resultLine2Text;
    private TextView resultLine3Text;
    private TextView resultLine4Text;
    private TextView resultLine5Text;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoFinishRunnable = this::finishCheck;
    private final Runnable safeBannerAutoHideRunnable = () -> {
        hideTopBanner();
        restoreIdleEntryState();
    };
    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isChecking) {
                return;
            }
            remainingSeconds--;
            if (remainingSeconds > 0) {
                updateBallCountdown();
                updateBannerCountdown();
                mainHandler.postDelayed(this, COUNTDOWN_TICK_MS);
            }
        }
    };

    private ObjectAnimator rotateAnimator;
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();

    private boolean functionPopupAdded = false;
    private boolean topBannerAdded = false;
    private boolean midRiskAdded = false;
    private boolean highRiskDialogAdded = false;
    private boolean resultDialogAdded = false;
    private boolean isChecking = false;
    private boolean midRiskDismissedThisCycle = false;
    private boolean highRiskDismissedThisCycle = false;
    private boolean overlaysInitialized = false;
    private boolean capturePermissionPending = false;
    private boolean mediaRecorderStarted = false;
    private boolean projectionStopHandled = false;
    private boolean projectionStoppedBySystem = false;
    private boolean projectionForegroundActive = false;
    private boolean captureRegrantRequired = false;
    private boolean recordingActive = false;
    private int captureWidth;
    private int captureHeight;
    private int captureDensityDpi;
    private int remainingSeconds = 5;
    private AnalyzeResult latestAnalyzeResult;
    private String currentAuthorId = "unknown";
    private TextToSpeech tts;
    private Uri uriForUpload;

    private static class AnalyzeResult {
        final String riskLevel;
        final double riskScore;
        final String reason;
        final String source;
        final String transcription;

        AnalyzeResult(String riskLevel,
                      double riskScore,
                      String reason,
                      String source,
                      String transcription) {
            this.riskLevel = riskLevel;
            this.riskScore = riskScore;
            this.reason = reason;
            this.source = source;
            this.transcription = transcription;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        tts = new TextToSpeech(this, status -> {});
        retryFailedUploads();
        // 延迟到 onStartCommand 再初始化，避免权限状态抖动时直接 addView 崩溃。
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (!hasOverlayPermission()) {
            if (overlaysInitialized) {
                // 某些系统在权限页回切时会短暂返回 false，已展示时不立即销毁。
                Log.w(TAG, "Transient overlay permission false, keep current overlay.");
                return START_NOT_STICKY;
            }
            mainHandler.postDelayed(() -> {
                if (!hasOverlayPermission()) {
                    stopSelfResult(startId);
                }
            }, PERMISSION_RECHECK_DELAY_MS);
            return START_NOT_STICKY;
        }

        if (!overlaysInitialized) {
            try {
                setupFloatingBall();
                setupFunctionPopup();
                setupTopBanner();
                setupMidRiskBar();
                setupHighRiskDialog();
                setupResultDialog();
                overlaysInitialized = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Overlay init failed", e);
                cleanupOverlayViews();
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        if (ACTION_START_CAPTURE.equals(action)) {
            capturePermissionPending = false;
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            cacheCapturePermission(resultCode, resultData);
            startDetectAfterPermission(resultCode, resultData);
        } else if (ACTION_CAPTURE_DENIED.equals(action)) {
            capturePermissionPending = false;
            clearCapturePermissionGrant();
            restoreIdleEntryState();
        }

        return START_NOT_STICKY;
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void setupFloatingBall() {
        floatingBallView = LayoutInflater.from(this).inflate(R.layout.view_floating_ball, null);
        ballIcon = floatingBallView.findViewById(R.id.ivBallIcon);
        countdownText = floatingBallView.findViewById(R.id.tvCountdown);

        floatingBallParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        floatingBallParams.gravity = Gravity.TOP | Gravity.START;
        floatingBallParams.x = 32;
        floatingBallParams.y = 280;

        floatingBallView.setAlpha(BALL_ACTIVE_ALPHA);
        floatingBallView.setOnTouchListener(new DragTouchListener());
        floatingBallView.setOnClickListener(v -> {
            floatingBallView.setAlpha(BALL_ACTIVE_ALPHA);
            if (!isChecking) {
                toggleFunctionPopup();
            }
        });

        if (!safeAddView(floatingBallView, floatingBallParams)) {
            throw new IllegalStateException("Unable to attach floating ball");
        }
    }

    private void setupFunctionPopup() {
        functionPopupView = LayoutInflater.from(this).inflate(R.layout.view_control_panel, null);
        Button detectButton = functionPopupView.findViewById(R.id.btnStartCheck);
        Button settingsButton = functionPopupView.findViewById(R.id.btnOpenSettings);
        final android.widget.EditText etAuthorId = functionPopupView.findViewById(R.id.etAuthorId);

        functionPopupParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        functionPopupParams.gravity = Gravity.TOP | Gravity.START;

        detectButton.setOnClickListener(v -> {
            String input = etAuthorId.getText().toString().trim();
            currentAuthorId = input.isEmpty() ? "unknown" : input;
            hideFunctionPopup();
            startDetectionFromCurrentPermissionState();
        });

        settingsButton.setOnClickListener(v -> {
            hideFunctionPopup();
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
    }

    private void requestScreenCapturePermission() {
        if (isChecking || capturePermissionPending) {
            return;
        }
        capturePermissionPending = true;
        Intent intent = new Intent(this, ScreenCapturePermissionActivity.class);
        // Use an isolated temporary task so capture permission returns to the original foreground app.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void startDetectionFromCurrentPermissionState() {
        if (isChecking || capturePermissionPending) {
            return;
        }
        if (mediaProjection != null) {
            startDetectWithProjection();
            return;
        }
        if (hasCachedCapturePermission()) {
            if (ensureProjectionSession(cachedCaptureResultCode, new Intent(cachedCaptureResultData))) {
                startDetectWithProjection();
                return;
            }
            clearCapturePermissionGrant();
        }
        requestScreenCapturePermission();
    }

    private boolean hasCachedCapturePermission() {
        return cachedCaptureResultCode == Activity.RESULT_OK && cachedCaptureResultData != null;
    }

    private void cacheCapturePermission(int resultCode, Intent resultData) {
        if (resultCode == Activity.RESULT_OK && resultData != null) {
            cachedCaptureResultCode = resultCode;
            cachedCaptureResultData = new Intent(resultData);
        }
    }

    private void clearCapturePermissionGrant() {
        cachedCaptureResultCode = Activity.RESULT_CANCELED;
        cachedCaptureResultData = null;
    }

    private void startDetectAfterPermission(int resultCode, Intent resultData) {
        if (isChecking) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            clearCapturePermissionGrant();
            Toast.makeText(this, R.string.record_permission_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureProjectionSession(resultCode, resultData)) {
            clearCapturePermissionGrant();
            restoreIdleEntryState();
            Toast.makeText(this, R.string.record_permission_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        startDetectWithProjection();
    }

    private void startDetectWithProjection() {
        if (isChecking || mediaProjection == null) {
            return;
        }
        captureRegrantRequired = false;
        if (!startScreenRecording()) {
            stopScreenRecording(false);
            restoreIdleEntryState();
            if (captureRegrantRequired) {
                requestScreenCapturePermission();
                return;
            }
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        switchToCheckingState();
    }

    private boolean ensureProjectionSession(int resultCode, Intent resultData) {
        if (mediaProjection != null) {
            return true;
        }
        if (mediaProjectionManager == null || resultCode != Activity.RESULT_OK || resultData == null) {
            return false;
        }
        try {
            if (!projectionForegroundActive) {
                startRecordForeground();
                projectionForegroundActive = true;
            }
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                if (projectionForegroundActive) {
                    stopForeground(true);
                    projectionForegroundActive = false;
                }
                return false;
            }
            projectionStopHandled = false;
            projectionStoppedBySystem = false;
            mediaProjectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        if (projectionStopHandled) {
                            return;
                        }
                        projectionStopHandled = true;
                        projectionStoppedBySystem = true;
                        Log.w(TAG, "MediaProjection stopped by system.");
                        stopScreenRecording(false);
                        releaseProjectionSession();
                        clearCapturePermissionGrant();
                        if (isChecking) {
                            isChecking = false;
                            mainHandler.removeCallbacks(autoFinishRunnable);
                            mainHandler.removeCallbacks(countdownRunnable);
                            mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
                            stopBallAnimation();
                            hideTopBanner();
                            restoreIdleEntryState();
                        }
                    });
                }
            };
            mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);
            initCaptureMetrics();
            if (!ensureVirtualDisplayReady()) {
                releaseProjectionSession();
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "ensureProjectionSession failed", e);
            releaseProjectionSession();
            return false;
        }
    }

    private void toggleFunctionPopup() {
        if (functionPopupAdded) {
            hideFunctionPopup();
        } else {
            showFunctionPopup();
        }
    }

    private void showFunctionPopup() {
        if (functionPopupView == null || functionPopupParams == null || floatingBallView == null) {
            return;
        }

        functionPopupView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        );

        int popupWidth = functionPopupView.getMeasuredWidth();
        int popupHeight = functionPopupView.getMeasuredHeight();
        int margin = dpToPx(PANEL_MARGIN_DP);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        int desiredX = floatingBallParams.x + floatingBallView.getWidth() + margin;
        if (desiredX + popupWidth > screenWidth) {
            desiredX = floatingBallParams.x - popupWidth - margin;
        }
        if (desiredX < 0) {
            desiredX = margin;
        }

        int desiredY = floatingBallParams.y;
        int minY = getStatusBarHeight() + margin;
        int maxY = Math.max(minY, screenHeight - popupHeight - margin);
        desiredY = Math.max(minY, Math.min(desiredY, maxY));

        functionPopupParams.x = desiredX;
        functionPopupParams.y = desiredY;

        if (functionPopupAdded) {
            safeUpdateViewLayout(functionPopupView, functionPopupParams);
        } else {
            functionPopupAdded = safeAddView(functionPopupView, functionPopupParams);
        }

        functionPopupView.post(() -> {
            android.widget.EditText et = functionPopupView.findViewById(R.id.etAuthorId);
            if (et != null) {
                et.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideFunctionPopup() {
        if (!functionPopupAdded) {
            return;
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (functionPopupView != null) {
            imm.hideSoftInputFromWindow(functionPopupView.getWindowToken(), 0);
        }
        safeRemoveView(functionPopupView);
        functionPopupAdded = false;
    }

    private void setupTopBanner() {
        topBannerView = LayoutInflater.from(this).inflate(R.layout.view_top_status_banner, null);
        bannerTitleText = topBannerView.findViewById(R.id.tvBannerTitle);
        bannerCountdownText = topBannerView.findViewById(R.id.tvBannerCountdown);
        bannerMoreButton = topBannerView.findViewById(R.id.btnBannerMore);
        bannerMoreButton.setOnClickListener(v -> {
            if (latestAnalyzeResult != null) {
                showResultDialog(latestAnalyzeResult);
            }
        });

        topBannerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        topBannerParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topBannerParams.y = getStatusBarHeight() + dpToPx(8);
    }

    private void setupMidRiskBar() {
        midRiskView = LayoutInflater.from(this).inflate(R.layout.view_mid_risk_bar, null);
        View midRiskCloseButton = midRiskView.findViewById(R.id.btnMidRiskClose);
        midRiskCloseButton.setOnClickListener(v -> {
            midRiskDismissedThisCycle = true;
            hideMidRiskBar();
        });
        midRiskView.setOnTouchListener(new MidRiskDragTouchListener());

        midRiskParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        midRiskParams.gravity = Gravity.TOP | Gravity.START;
        midRiskParams.y = getStatusBarHeight() + dpToPx(8);
    }

    private void setupHighRiskDialog() {
        highRiskDialogView = LayoutInflater.from(this).inflate(R.layout.view_high_risk_dialog, null);
        highRiskExitButton = highRiskDialogView.findViewById(R.id.btnExitNow);
        highRiskContinueButton = highRiskDialogView.findViewById(R.id.btnContinueWatch);

        highRiskDialogParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        highRiskDialogParams.gravity = Gravity.CENTER;

        highRiskContinueButton.setOnClickListener(v -> {
            highRiskDismissedThisCycle = true;
            hideHighRiskDialog();
        });

        highRiskExitButton.setOnClickListener(v -> {
            highRiskDismissedThisCycle = true;
            hideHighRiskDialog();
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
        });
    }

    private void setupResultDialog() {
        resultDialogView = LayoutInflater.from(this).inflate(R.layout.view_result_dialog, null);
        resultStatusText = resultDialogView.findViewById(R.id.tvResultStatus);
        resultRiskLevelText = resultDialogView.findViewById(R.id.tvRiskLevel);
        resultLine1Text = resultDialogView.findViewById(R.id.tvRisk1);
        resultLine2Text = resultDialogView.findViewById(R.id.tvRisk2);
        resultLine3Text = resultDialogView.findViewById(R.id.tvRisk3);
        resultLine4Text = resultDialogView.findViewById(R.id.tvRisk4);
        resultLine5Text = resultDialogView.findViewById(R.id.tvRisk5);
        resultLine4Text.setVisibility(View.GONE);
        resultLine5Text.setVisibility(View.GONE);

        Button againCheckButton = resultDialogView.findViewById(R.id.btnAgainCheck);
        Button closeResultButton = resultDialogView.findViewById(R.id.btnCloseResult);

        resultDialogParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        resultDialogParams.gravity = Gravity.CENTER;

        againCheckButton.setOnClickListener(v -> {
            hideResultDialog();
            startDetectionFromCurrentPermissionState();
        });
        closeResultButton.setOnClickListener(v -> hideResultDialog());
    }

    private void showTopBanner(boolean safeState) {
        if (topBannerView == null) {
            return;
        }
        // Stop any stale auto-hide task from previous result cycle.
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
        updateTopBannerTouchable(false);
        if (topBannerAdded) {
            updateTopBannerBackground(safeState);
            updateBannerTexts(safeState);
            safeUpdateViewLayout(topBannerView, topBannerParams);
            return;
        }
        updateTopBannerBackground(safeState);
        updateBannerTexts(safeState);
        topBannerAdded = safeAddView(topBannerView, topBannerParams);
    }

    private void showRiskLevelBanner(AnalyzeResult result) {
        if (topBannerView == null || result == null) {
            return;
        }
        latestAnalyzeResult = result;
        topBannerView.setBackgroundResource(resolveRiskBannerBackground(result.riskLevel));
        bannerTitleText.setText(getString(R.string.risk_banner_level_format, safeText(result.riskLevel)));
        bannerCountdownText.setVisibility(View.GONE);
        if (bannerMoreButton != null) {
            bannerMoreButton.setVisibility(View.VISIBLE);
        }
        updateTopBannerTouchable(true);

        if (topBannerAdded) {
            safeUpdateViewLayout(topBannerView, topBannerParams);
        } else {
            topBannerAdded = safeAddView(topBannerView, topBannerParams);
        }
        ensureTopBannerVisibleSoon();

        // Auto-hide risk result banner after 3s instead of keeping it always visible.
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
        mainHandler.postDelayed(safeBannerAutoHideRunnable, RISK_BANNER_AUTO_HIDE_MS);
    }

    private int resolveRiskBannerBackground(String riskLevel) {
        if (isHighRisk(riskLevel)) {
            return R.drawable.bg_top_banner_high;
        }
        if (isMediumRisk(riskLevel)) {
            return R.drawable.bg_top_banner_mid;
        }
        return R.drawable.bg_top_banner_safe;
    }

    private void updateTopBannerTouchable(boolean touchable) {
        if (topBannerParams == null) {
            return;
        }
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (touchable) {
            topBannerParams.flags = flags;
        } else {
            topBannerParams.flags = flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    private void ensureTopBannerVisibleSoon() {
        if (topBannerView == null || topBannerParams == null) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (topBannerView == null || topBannerParams == null) {
                return;
            }
            if (!topBannerAdded || !topBannerView.isAttachedToWindow()) {
                topBannerAdded = safeAddView(topBannerView, topBannerParams);
            } else {
                safeUpdateViewLayout(topBannerView, topBannerParams);
            }
            topBannerView.requestLayout();
            topBannerView.invalidate();
        }, TOP_BANNER_REFRESH_DELAY_MS);
    }

    private void hideTopBanner() {
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
        if (!topBannerAdded) {
            return;
        }
        safeRemoveView(topBannerView);
        topBannerAdded = false;
    }

    private void showMidRiskBar() {
        if (midRiskDismissedThisCycle || midRiskAdded) {
            return;
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        midRiskParams.x = Math.max(0, (screenWidth - dpToPx(240)) / 2);
        midRiskAdded = safeAddView(midRiskView, midRiskParams);
    }

    private void hideMidRiskBar() {
        if (!midRiskAdded) {
            return;
        }
        safeRemoveView(midRiskView);
        midRiskAdded = false;
    }

    private void showHighRiskDialog() {
        if (highRiskDismissedThisCycle || highRiskDialogAdded) {
            return;
        }
        highRiskDialogAdded = safeAddView(highRiskDialogView, highRiskDialogParams);
    }

    private void hideHighRiskDialog() {
        if (!highRiskDialogAdded) {
            return;
        }
        safeRemoveView(highRiskDialogView);
        highRiskDialogAdded = false;
    }

    private void showResultDialog(AnalyzeResult result) {
        if (resultDialogView == null) {
            return;
        }

        resultStatusText.setText(getString(R.string.result_status_format, safeText(result.riskLevel)));
        resultRiskLevelText.setText(getString(R.string.result_level_format, safeText(result.riskLevel)));
        resultLine1Text.setText(getString(R.string.result_risk_score_format, toPercent(result.riskScore)));

        if (result.reason != null && !result.reason.isEmpty()) {
            resultLine2Text.setVisibility(View.VISIBLE);
            resultLine2Text.setText(getString(R.string.result_reason_format, result.reason));
        } else {
            resultLine2Text.setVisibility(View.GONE);
        }

        if (result.source != null && !result.source.isEmpty()) {
            resultLine3Text.setVisibility(View.VISIBLE);
            resultLine3Text.setText(getString(R.string.result_source_format, result.source));
        } else {
            resultLine3Text.setVisibility(View.GONE);
        }

        if (result.transcription != null && !result.transcription.isEmpty()) {
            resultLine4Text.setVisibility(View.VISIBLE);
            resultLine4Text.setText(getString(R.string.result_transcription_format, result.transcription));
        } else {
            resultLine4Text.setVisibility(View.GONE);
        }

        resultRiskLevelText.setTextColor(resolveRiskColor(result.riskLevel));

        if (resultDialogAdded) {
            safeUpdateViewLayout(resultDialogView, resultDialogParams);
            return;
        }
        resultDialogAdded = safeAddView(resultDialogView, resultDialogParams);
    }

    private void hideResultDialog() {
        if (!resultDialogAdded) {
            return;
        }
        safeRemoveView(resultDialogView);
        resultDialogAdded = false;
    }

    private void switchToIdleState() {
        isChecking = false;
        capturePermissionPending = false;
        mainHandler.removeCallbacks(autoFinishRunnable);
        mainHandler.removeCallbacks(countdownRunnable);
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
        stopBallAnimation();
        countdownText.setVisibility(View.GONE);
        hideTopBanner();
        hideFunctionPopup();
        hideResultDialog();

        boolean hasRecording = stopScreenRecording(true);

        if (hasRecording) {
            switchToUploadForeground();
            showUploadInProgressBanner();
            uploadRecordingToAnalyze();
        } else {
            releaseProjectionSession();
        }
    }

    private void restoreIdleEntryState() {
        if (countdownText != null) {
            countdownText.setVisibility(View.GONE);
        }
        if (floatingBallView != null) {
            floatingBallView.setAlpha(BALL_ACTIVE_ALPHA);
        }
    }

    private void switchToCheckingState() {
        if (isChecking) {
            return;
        }
        isChecking = true;
        latestAnalyzeResult = null;
        midRiskDismissedThisCycle = false;
        highRiskDismissedThisCycle = false;
        hideFunctionPopup();
        hideMidRiskBar();
        hideHighRiskDialog();
        hideResultDialog();
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);

        remainingSeconds = 5;
        updateBallCountdown();
        updateBannerCountdown();
        countdownText.setVisibility(View.VISIBLE);
        showTopBanner(false);

        mainHandler.removeCallbacks(autoFinishRunnable);
        mainHandler.removeCallbacks(countdownRunnable);
        mainHandler.postDelayed(autoFinishRunnable, AUTO_FINISH_DELAY_MS);
        mainHandler.postDelayed(countdownRunnable, COUNTDOWN_TICK_MS);

        startBallAnimation();
    }

    private void finishCheck() {
        if (!isChecking) {
            return;
        }
        switchToIdleState();
        Toast.makeText(this, R.string.uploading_analyze, Toast.LENGTH_SHORT).show();
    }

    private void updateTopBannerBackground(boolean safeState) {
        if (safeState) {
            topBannerView.setBackgroundResource(R.drawable.bg_top_banner_safe);
        } else {
            topBannerView.setBackgroundResource(R.drawable.bg_top_banner_checking);
        }
    }

    private void updateBannerTexts(boolean safeState) {
        if (safeState) {
            bannerTitleText.setText(R.string.safe_banner_text);
            bannerCountdownText.setVisibility(View.GONE);
        } else {
            bannerTitleText.setText(R.string.checking_banner_text);
            bannerCountdownText.setVisibility(View.VISIBLE);
            updateBannerCountdown();
        }
        if (bannerMoreButton != null) {
            bannerMoreButton.setVisibility(View.GONE);
        }
    }

    private void updateBannerCountdown() {
        if (bannerCountdownText != null) {
            bannerCountdownText.setText(getString(R.string.banner_countdown, remainingSeconds));
        }
    }

    private void updateBallCountdown() {
        if (countdownText != null) {
            countdownText.setText(String.valueOf(remainingSeconds));
        }
    }

    private void startBallAnimation() {
        if (rotateAnimator == null) {
            rotateAnimator = ObjectAnimator.ofFloat(ballIcon, View.ROTATION, 0f, 360f);
            rotateAnimator.setDuration(900);
            rotateAnimator.setInterpolator(new LinearInterpolator());
            rotateAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        }
        rotateAnimator.start();
    }

    private void stopBallAnimation() {
        if (rotateAnimator != null && rotateAnimator.isRunning()) {
            rotateAnimator.cancel();
        }
        ballIcon.setRotation(0f);
    }

    private void dockFloatingBallToEdge() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int targetX = floatingBallParams.x < screenWidth / 2 ? 0 : screenWidth - floatingBallView.getWidth();
        floatingBallParams.x = Math.max(0, targetX);
        safeUpdateViewLayout(floatingBallView, floatingBallParams);
        floatingBallView.setAlpha(BALL_DOCKED_ALPHA);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mainHandler.removeCallbacks(autoFinishRunnable);
        mainHandler.removeCallbacks(countdownRunnable);
        mainHandler.removeCallbacks(safeBannerAutoHideRunnable);
        stopBallAnimation();

        cleanupOverlayViews();
        uploadExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class DragTouchListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchStartX;
        private float touchStartY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    moved = false;
                    startX = floatingBallParams.x;
                    startY = floatingBallParams.y;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    floatingBallView.setAlpha(BALL_ACTIVE_ALPHA);
                    hideFunctionPopup();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int deltaX = (int) (event.getRawX() - touchStartX);
                    int deltaY = (int) (event.getRawY() - touchStartY);
                    if (Math.abs(deltaX) > 8 || Math.abs(deltaY) > 8) {
                        moved = true;
                        floatingBallParams.x = startX + deltaX;
                        floatingBallParams.y = startY + deltaY;
                        safeUpdateViewLayout(floatingBallView, floatingBallParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        v.performClick();
                    } else {
                        dockFloatingBallToEdge();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private class MidRiskDragTouchListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchStartX;
        private float touchStartY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = midRiskParams.x;
                    startY = midRiskParams.y;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    midRiskParams.x = startX + (int) (event.getRawX() - touchStartX);
                    midRiskParams.y = Math.max(getStatusBarHeight() + dpToPx(4), startY + (int) (event.getRawY() - touchStartY));
                    safeUpdateViewLayout(midRiskView, midRiskParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    dockMidRiskToEdge();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void dockMidRiskToEdge() {
        if (!midRiskAdded) {
            return;
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int viewWidth = midRiskView.getWidth();
        if (viewWidth <= 0) {
            return;
        }
        int leftDockX = -(viewWidth * 2 / 3);
        int rightDockX = screenWidth - (viewWidth / 3);
        int midX = midRiskParams.x + viewWidth / 2;
        midRiskParams.x = midX < screenWidth / 2 ? leftDockX : rightDockX;
        safeUpdateViewLayout(midRiskView, midRiskParams);
    }

    private boolean safeAddView(View view, WindowManager.LayoutParams params) {
        if (view == null || params == null || windowManager == null || !hasOverlayPermission()) {
            return false;
        }
        try {
            windowManager.addView(view, params);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "addView failed", e);
            return false;
        }
    }

    private boolean startScreenRecording() {
        if (mediaProjection == null || !ensureVirtualDisplayReady()) {
            return false;
        }
        mediaRecorderStarted = false;
        try {
            createRecordingOutput();
            if (recordingPfd == null || recordingUri == null) {
                return false;
            }

            if (mediaRecorder != null) {
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {
                }
                mediaRecorder = null;
            }
            mediaRecorderStarted = false;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            int bitrate = Math.min(captureWidth * captureHeight * 4, MAX_VIDEO_BITRATE);
            mediaRecorder.setVideoEncodingBitRate(bitrate);
            mediaRecorder.setVideoFrameRate(RECORD_FPS);
            mediaRecorder.setVideoSize(captureWidth, captureHeight);
            mediaRecorder.setOutputFile(recordingPfd.getFileDescriptor());
            mediaRecorder.prepare();

            virtualDisplay.setSurface(mediaRecorder.getSurface());
            mediaRecorder.start();
            mediaRecorderStarted = true;
            recordingActive = true;
            return true;
        } catch (Exception e) {
            if (isProjectionTokenExpired(e)) {
                captureRegrantRequired = true;
                clearCapturePermissionGrant();
                releaseProjectionSession();
            }
            Log.e(TAG, "startScreenRecording failed", e);
            return false;
        }
    }

    private boolean isProjectionTokenExpired(Exception e) {
        if (!(e instanceof SecurityException)) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Don't re-use the resultData")
                || message.contains("token that has timed out")
                || message.contains("multiple captures");
    }

    private void createRecordingOutput() {
        recordingUri = null;
        recordingPfd = null;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "ShortVideoGuard_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ShortVideoGuard");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
        }

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return;
        }
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            if (pfd == null) {
                getContentResolver().delete(uri, null, null);
                return;
            }
            recordingUri = uri;
            recordingPfd = pfd;
        } catch (Exception e) {
            Log.e(TAG, "createRecordingOutput failed", e);
            getContentResolver().delete(uri, null, null);
        }
    }

    private boolean stopScreenRecording(boolean keepFile) {
        if (projectionStoppedBySystem) {
            keepFile = false;
        }

        Uri finishedRecordingUri = recordingUri;

        if (mediaRecorder != null) {
            if (mediaRecorderStarted) {
                try {
                    mediaRecorder.stop();
                } catch (RuntimeException e) {
                    keepFile = false;
                    Log.w(TAG, "mediaRecorder stop failed", e);
                }
            }
            try {
                mediaRecorder.reset();
            } catch (Exception e) {
                Log.w(TAG, "mediaRecorder reset failed", e);
            }
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "mediaRecorder release failed", e);
            }
            mediaRecorder = null;
            mediaRecorderStarted = false;
            restoreIdleSurface();
        }

        if (recordingPfd != null) {
            try {
                recordingPfd.close();
            } catch (Exception e) {
                Log.w(TAG, "close recording fd failed", e);
            }
            recordingPfd = null;
        }

        uriForUpload = null;

        if (finishedRecordingUri != null) {
            if (!keepFile) {
                long salvagedSize = getVideoFileSize(finishedRecordingUri);
                if (salvagedSize > 0) {
                    keepFile = true;
                    Log.i(TAG, "Salvaged partial recording: " + salvagedSize + " bytes");
                }
            }

            if (keepFile) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues doneValues = new ContentValues();
                    doneValues.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(finishedRecordingUri, doneValues, null, null);
                }
                long fileSize = getVideoFileSize(finishedRecordingUri);
                if (fileSize > MAX_VIDEO_FILE_SIZE) {
                    Log.w(TAG, "Video file exceeds 10MB: " + fileSize + " bytes");
                }
                uriForUpload = finishedRecordingUri;
                Toast.makeText(this, R.string.record_saved, Toast.LENGTH_SHORT).show();
            } else {
                getContentResolver().delete(finishedRecordingUri, null, null);
            }
            recordingUri = null;
        }

        recordingActive = false;
        projectionStoppedBySystem = false;
        return uriForUpload != null;
    }

    private void uploadRecordingToAnalyze() {
        SharedPreferences prefs = getSharedPreferences(
                getString(R.string.key_settings_prefs), MODE_PRIVATE);
        if (NetworkInfoHelper.isMobileData(this)
                && !prefs.getBoolean(getString(R.string.key_mobile_data_enabled), false)) {
            mainHandler.post(() -> Toast.makeText(this, R.string.mobile_data_blocked, Toast.LENGTH_SHORT).show());
            releaseProjectionSession();
            return;
        }

        String baseUrl = prefs.getString(getString(R.string.key_api_base_url),
                getString(R.string.api_url_default));
        String deviceId = DeviceIdProvider.getDeviceId(this);
        final String[] apiKeyHolder = { getApiKey() };

        final Uri videoUri = uriForUpload;
        uriForUpload = null;
        if (videoUri == null) {
            releaseProjectionSession();
            return;
        }

        final String requestId = UUID.randomUUID().toString();
        final String boundary = "----ShortVideoGuard" + UUID.randomUUID();

        final long recordId = System.currentTimeMillis();
        final String createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date(recordId));

        uploadExecutor.execute(() -> {
            HistoryEntity pendingEntity = new HistoryEntity();
            pendingEntity.id = recordId;
            pendingEntity.deviceId = deviceId;
            pendingEntity.authorId = currentAuthorId;
            pendingEntity.requestId = requestId;
            pendingEntity.uploadStatus = "UPLOADING";
            pendingEntity.videoUri = videoUri.toString();
            pendingEntity.createdAt = createdAt;
            try {
                AppDatabase.getInstance(FloatingOverlayService.this).historyDao().insert(pendingEntity);
            } catch (Exception e) {
                Log.w(TAG, "Failed to pre-insert upload tracking record, skipping upload", e);
                mainHandler.post(() -> releaseProjectionSession());
                return;
            }

            int attempt = 0;
            boolean completed = false;
            HttpURLConnection connection = null;

            while (!completed && attempt <= UploadRetryPolicy.MAX_NETWORK_RETRIES) {
                connection = null;
                try {
                    if (attempt > 0) {
                        long delay = UploadRetryPolicy.getNetworkRetryDelayMs(attempt - 1);
                        int currentAttempt = attempt;
                        Log.i(TAG, "Upload retry attempt " + currentAttempt + ", waiting " + delay + "ms");
                        mainHandler.post(() -> Toast.makeText(this,
                                getString(R.string.upload_retry_toast, currentAttempt), Toast.LENGTH_SHORT).show());
                        Thread.sleep(delay);
                    }

                    URL url = new URL(baseUrl + "/api/v1/detect");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(UploadRetryPolicy.CLIENT_TIMEOUT_MS);
                    connection.setReadTimeout(UploadRetryPolicy.CLIENT_TIMEOUT_MS);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    connection.setRequestProperty("X-API-Key", apiKeyHolder[0]);
                    connection.setRequestProperty("X-Request-Id", requestId);

                    writeMultipartBody(connection, videoUri, deviceId, currentAuthorId, boundary);

                    int responseCode = connection.getResponseCode();
                    String retryAfter = connection.getHeaderField("Retry-After");
                    String responseBody = readResponseBody(connection);

                    Log.i(TAG, "Upload response: code=" + responseCode);

                    monitorRateLimitHeaders(connection);

                    if (responseCode >= 200 && responseCode < 300) {
                        Log.i(TAG, "upload success: code=" + responseCode + ", body=" + responseBody);
                        AnalyzeResult result = parseAnalyzeResponse(responseBody);
                        if (result != null) {
                            saveDetectionToHistory(recordId, result, createdAt);
                            dispatchAnalyzeResult(result);
                        } else {
                            mainHandler.post(() -> Toast.makeText(this,
                                    R.string.analyze_parse_failed, Toast.LENGTH_SHORT).show());
                            updateUploadRecordStatus(recordId, "FAILED", "解析响应失败", attempt);
                        }
                        completed = true;
                        break;
                    }

                    if (responseCode == 400) {
                        handlePermanentFailure(recordId, responseCode, responseBody, "请求参数错误或视频过大");
                        completed = true;
                        break;
                    }

                    if (responseCode == 401) {
                        Log.w(TAG, "Got 401, attempting key re-registration");
                        mainHandler.post(() -> Toast.makeText(this,
                                R.string.key_re_registering, Toast.LENGTH_SHORT).show());
                        boolean reRegOk = ApiKeyManager.registerKey(FloatingOverlayService.this);
                        if (reRegOk) {
                            apiKeyHolder[0] = getApiKey();
                            mainHandler.post(() -> Toast.makeText(this,
                                    R.string.key_re_register_success, Toast.LENGTH_SHORT).show());
                            attempt++;
                            continue;
                        }
                        handlePermanentFailure(recordId, responseCode, responseBody, "API Key 无效且重新注册失败");
                        completed = true;
                        break;
                    }

                    if (UploadRetryPolicy.isRetryableHttpStatus(responseCode)) {
                        int maxHttpRetries = UploadRetryPolicy.getMaxHttpRetries(responseCode);
                        if (attempt >= maxHttpRetries) {
                            Log.e(TAG, "HTTP retries exhausted for status " + responseCode);
                            handlePermanentFailure(recordId, responseCode, responseBody,
                                    "HTTP " + responseCode + " 重试耗尽");
                            completed = true;
                            break;
                        }
                        long delay = UploadRetryPolicy.getHttpErrorRetryDelayMs(responseCode, retryAfter);
                        Log.w(TAG, "HTTP " + responseCode + ", waiting " + delay + "ms before retry");
                        if (responseCode == 502) {
                            mainHandler.post(() -> Toast.makeText(this,
                                    R.string.upload_ai_busy, Toast.LENGTH_SHORT).show());
                        } else if (responseCode == 429) {
                            mainHandler.post(() -> Toast.makeText(this,
                                    R.string.upload_rate_limited, Toast.LENGTH_SHORT).show());
                        }
                        Thread.sleep(delay);
                        attempt++;
                        continue;
                    }

                    handlePermanentFailure(recordId, responseCode, responseBody, "HTTP " + responseCode);
                    completed = true;
                    break;

                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "Upload timeout on attempt " + (attempt + 1), e);
                    attempt++;
                } catch (UnknownHostException e) {
                    Log.e(TAG, "DNS resolution failed on attempt " + (attempt + 1), e);
                    attempt++;
                } catch (ConnectException e) {
                    Log.e(TAG, "Connection refused on attempt " + (attempt + 1), e);
                    attempt++;
                } catch (java.io.IOException e) {
                    Log.e(TAG, "IO error on attempt " + (attempt + 1), e);
                    if (!UploadRetryPolicy.isRetryableException(e)) {
                        handlePermanentFailure(recordId, -1, null, e.getMessage());
                        completed = true;
                        break;
                    }
                    attempt++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Retry sleep interrupted", e);
                    handlePermanentFailure(recordId, -1, null, "上传被中断");
                    completed = true;
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error on attempt " + (attempt + 1), e);
                    handlePermanentFailure(recordId, -1, null, e.getMessage());
                    completed = true;
                    break;
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            if (!completed) {
                Log.e(TAG, "All retries exhausted for upload");
                String errorMsg = getString(R.string.upload_retry_exhausted, attempt);
                updateUploadRecordStatus(recordId, "FAILED", errorMsg, attempt);
                mainHandler.post(() -> {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    releaseProjectionSession();
                });
            } else {
                mainHandler.post(this::releaseProjectionSession);
            }
        });
    }

    private void dispatchAnalyzeResult(AnalyzeResult result) {
        mainHandler.post(() -> {
            hideMidRiskBar();
            hideHighRiskDialog();
            hideResultDialog();
            showRiskLevelBanner(result);

            if (isHighRisk(result.riskLevel)) {
                speakWarning();
                sendEmergencySms();
            }
        });
    }

    private void speakWarning() {
        if (tts == null) return;
        SharedPreferences prefs = getSharedPreferences(
                getString(R.string.key_settings_prefs), MODE_PRIVATE);
        if (!prefs.getBoolean(getString(R.string.key_tts_enabled), true)) return;

        String text = getString(R.string.tts_warning);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "high_risk_warning");
    }

    private void sendEmergencySms() {
        SharedPreferences prefs = getSharedPreferences(
                getString(R.string.key_settings_prefs), MODE_PRIVATE);
        if (!prefs.getBoolean(getString(R.string.key_sms_enabled), true)) return;

        String phone = prefs.getString(getString(R.string.key_emergency_phone), "");
        if (phone.isEmpty()) return;

        try {
            SmsManager smsManager = SmsManager.getDefault();
            String message = getString(R.string.sms_warning);
            smsManager.sendTextMessage(phone, null, message, null, null);
            Log.i(TAG, "emergency SMS sent to " + phone);
        } catch (Exception e) {
            Log.e(TAG, "sendEmergencySms failed", e);
        }
    }

    private void saveDetectionToHistory(long recordId, AnalyzeResult result, String createdAt) {
        try {
            HistoryDao dao = AppDatabase.getInstance(this).historyDao();
            dao.updateUploadResult(
                    recordId,
                    "SUCCESS",
                    null,
                    0,
                    result.riskLevel,
                    result.riskScore,
                    result.reason,
                    result.source,
                    result.transcription
            );
            Log.i(TAG, "Updated detection record " + recordId + " with result: " + result.riskLevel);
        } catch (Exception e) {
            Log.e(TAG, "saveDetectionToHistory failed", e);
        }
    }

    private void updateUploadRecordStatus(long id, String status, String error, int retryCount) {
        try {
            HistoryDao dao = AppDatabase.getInstance(this).historyDao();
            dao.updateUploadResult(id, status, error, retryCount, null, null, null, null, null);
        } catch (Exception e) {
            Log.w(TAG, "updateUploadRecordStatus failed", e);
        }
    }

    private void handlePermanentFailure(long recordId, int httpCode, String body, String message) {
        Log.e(TAG, "Permanent upload failure: HTTP " + httpCode + " " + message);
        updateUploadRecordStatus(recordId, "FAILED", "HTTP " + httpCode + ": " + message, 0);
        mainHandler.post(() -> {
            int stringRes;
            if (httpCode == 400) {
                stringRes = resolve400ErrorMessage(body);
            } else if (httpCode == 401) {
                stringRes = R.string.key_re_register_failed;
            } else {
                stringRes = R.string.analyze_request_failed;
            }
            Toast.makeText(this, stringRes, Toast.LENGTH_LONG).show();
        });
    }

    private int resolve400ErrorMessage(String responseBody) {
        if (responseBody == null) return R.string.upload_bad_request;
        String lower = responseBody.toLowerCase();
        if (lower.contains("不能为空") || lower.contains("empty") || lower.contains("null")) {
            return R.string.upload_error_empty_video;
        }
        if (lower.contains("损坏") || lower.contains("corrupted") || lower.contains("格式不正确")) {
            return R.string.upload_error_corrupted_video;
        }
        if (lower.contains("仅支持") || lower.contains("mp4") || lower.contains("格式")) {
            return R.string.upload_error_not_mp4;
        }
        if (lower.contains("缺少") || lower.contains("missing") || lower.contains("参数")) {
            return R.string.upload_error_missing_params;
        }
        if (lower.contains("过大") || lower.contains("too large") || lower.contains("10mb")) {
            return R.string.upload_error_video_too_large;
        }
        return R.string.upload_bad_request;
    }

    private void writeMultipartBody(HttpURLConnection connection, Uri videoUri,
                                     String deviceId, String authorId,
                                     String boundary) throws java.io.IOException {
        String lineEnd = "\r\n";
        String fileName = resolveDisplayName(videoUri);
        String mimeType = getContentResolver().getType(videoUri);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = "video/mp4";
        }

        try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream());
             InputStream rawInput = getContentResolver().openInputStream(videoUri);
             BufferedInputStream in = rawInput == null ? null : new BufferedInputStream(rawInput)) {
            if (in == null) {
                throw new FileNotFoundException("Recording file not accessible: " + videoUri);
            }

            out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"deviceId\"" + lineEnd)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
            out.write(deviceId.getBytes(StandardCharsets.UTF_8));
            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"authorId\"" + lineEnd)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
            out.write(authorId.getBytes(StandardCharsets.UTF_8));
            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"video\"; filename=\"" + fileName + "\"" + lineEnd)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + mimeType + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));

            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }

            out.write(lineEnd.getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--" + lineEnd).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private AnalyzeResult parseAnalyzeResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            if (code != 200) {
                Log.e(TAG, "API error: code=" + code + ", message=" + json.optString("message"));
                return null;
            }
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                Log.e(TAG, "API response missing data field");
                return null;
            }
            return new AnalyzeResult(
                    data.optString("riskLevel", "SAFE"),
                    data.optDouble("score", 0d),
                    data.isNull("reason") ? null : data.optString("reason", null),
                    data.isNull("source") ? null : data.optString("source", null),
                    data.isNull("transcription") ? null : data.optString("transcription", null)
            );
        } catch (Exception e) {
            Log.e(TAG, "parseAnalyzeResponse failed: " + responseBody, e);
            return null;
        }
    }

    private int resolveRiskColor(String riskLevel) {
        if (isHighRisk(riskLevel)) {
            return ContextCompat.getColor(this, R.color.risk_high);
        }
        if (isMediumRisk(riskLevel)) {
            return ContextCompat.getColor(this, R.color.risk_mid);
        }
        return ContextCompat.getColor(this, R.color.safe_green);
    }

    private boolean isHighRisk(String riskLevel) {
        String n = safeText(riskLevel);
        return n.contains("高") || n.equalsIgnoreCase("HIGH");
    }

    private boolean isMediumRisk(String riskLevel) {
        String n = safeText(riskLevel);
        return n.contains("中") || n.equalsIgnoreCase("MEDIUM");
    }

    private String toPercent(double value) {
        double percent = Math.max(0d, Math.min(100d, value * 100d));
        return String.format(java.util.Locale.US, "%.2f%%", percent);
    }

    private String safeText(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    private String resolveDisplayName(Uri uri) {
        String fallbackName = "capture_" + System.currentTimeMillis() + ".mp4";
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String displayName = cursor.getString(index);
                    if (displayName != null && !displayName.isEmpty()) {
                        return displayName;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveDisplayName failed", e);
        }
        return fallbackName;
    }

    private long getVideoFileSize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return cursor.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "getVideoFileSize failed", e);
        }
        return -1;
    }

    private String readResponseBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream == null) {
                return "";
            }
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[IO_BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            Log.w(TAG, "readResponseBody failed", e);
            return "";
        }
    }

    private void switchToUploadForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = manager.getNotificationChannel(UPLOAD_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        UPLOAD_CHANNEL_ID,
                        getString(R.string.upload_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, UPLOAD_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.uploading_notification_title))
                .setContentText(getString(R.string.uploading_notification_text))
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(UPLOAD_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } catch (RuntimeException e) {
                Log.e(TAG, "startForeground (dataSync) failed, falling back", e);
                startForeground(UPLOAD_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
        } else {
            startForeground(UPLOAD_NOTIFICATION_ID, notification);
        }
    }

    private void releaseProjectionSession() {
        releaseVirtualDisplayResources();
        if (mediaProjection == null) {
            if (projectionForegroundActive) {
                stopForeground(true);
                projectionForegroundActive = false;
            }
            cancelUploadNotification();
            return;
        }
        projectionStopHandled = true;
        if (mediaProjectionCallback != null) {
            try {
                mediaProjection.unregisterCallback(mediaProjectionCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregisterCallback ignored", e);
            }
        }
        try {
            mediaProjection.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "mediaProjection stop ignored", e);
        }
        mediaProjection = null;
        mediaProjectionCallback = null;
        projectionStoppedBySystem = false;
        if (projectionForegroundActive) {
            stopForeground(true);
            projectionForegroundActive = false;
        }
        cancelUploadNotification();
    }

    private void cancelUploadNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(UPLOAD_NOTIFICATION_ID);
        }
    }

    private void initCaptureMetrics() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        captureDensityDpi = metrics.densityDpi;

        if (captureWidth > MAX_CAPTURE_WIDTH || captureHeight > MAX_CAPTURE_HEIGHT) {
            float scale = Math.min(
                    (float) MAX_CAPTURE_WIDTH / captureWidth,
                    (float) MAX_CAPTURE_HEIGHT / captureHeight);
            captureWidth = Math.round(captureWidth * scale);
            captureHeight = Math.round(captureHeight * scale);
            Log.i(TAG, "Capture resolution scaled to " + captureWidth + "x" + captureHeight);
        }
    }

    private boolean ensureVirtualDisplayReady() {
        if (mediaProjection == null) {
            return false;
        }
        if (captureWidth <= 0 || captureHeight <= 0 || captureDensityDpi <= 0) {
            initCaptureMetrics();
        }
        if (virtualDisplay != null && idleImageReader != null) {
            return true;
        }
        try {
            idleImageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ShortVideoGuardCapture",
                    captureWidth,
                    captureHeight,
                    captureDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    idleImageReader.getSurface(),
                    null,
                    null
            );
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "ensureVirtualDisplayReady failed", e);
            releaseVirtualDisplayResources();
            return false;
        }
    }

    private void restoreIdleSurface() {
        if (virtualDisplay == null || idleImageReader == null) {
            return;
        }
        try {
            virtualDisplay.setSurface(idleImageReader.getSurface());
        } catch (RuntimeException e) {
            Log.w(TAG, "restore idle surface failed", e);
        }
    }

    private void releaseVirtualDisplayResources() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "virtualDisplay release ignored", e);
            }
            virtualDisplay = null;
        }
        if (idleImageReader != null) {
            idleImageReader.close();
            idleImageReader = null;
        }
    }

    private void startRecordForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = manager.getNotificationChannel(RECORD_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        RECORD_CHANNEL_ID,
                        getString(R.string.recording_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                );
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, RECORD_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(getString(R.string.recording_notification_text))
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(RECORD_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (RuntimeException e) {
                Log.e(TAG, "startForeground (record) failed, falling back", e);
                startForeground(RECORD_NOTIFICATION_ID, notification);
            }
        } else {
            startForeground(RECORD_NOTIFICATION_ID, notification);
        }
    }

    private void safeRemoveView(View view) {
        if (view == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "removeView ignored", e);
        }
    }

    private void safeUpdateViewLayout(View view, WindowManager.LayoutParams params) {
        if (view == null || params == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(view, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "updateViewLayout ignored", e);
        }
    }

    private void cleanupOverlayViews() {
        try {
            hideHighRiskDialog();
            hideMidRiskBar();
            hideTopBanner();
            hideFunctionPopup();
            hideResultDialog();

            if (floatingBallView != null) {
                safeRemoveView(floatingBallView);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error cleaning up overlay views", e);
        } finally {
            floatingBallView = null;
            functionPopupView = null;
            topBannerView = null;
            midRiskView = null;
            highRiskDialogView = null;
            resultDialogView = null;

            functionPopupAdded = false;
            topBannerAdded = false;
            midRiskAdded = false;
            highRiskDialogAdded = false;
            resultDialogAdded = false;
            overlaysInitialized = false;
        }

        stopScreenRecording(false);
        releaseProjectionSession();
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private String getApiKey() {
        return getSharedPreferences(getString(R.string.key_settings_prefs), MODE_PRIVATE)
                .getString(getString(R.string.key_api_key), "");
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void retryFailedUploads() {
        uploadExecutor.execute(() -> {
            try {
                List<HistoryEntity> failed = AppDatabase.getInstance(this)
                        .historyDao().getByUploadStatus("FAILED");
                if (failed != null && !failed.isEmpty()) {
                    HistoryEntity latest = failed.get(0);
                    if (latest.videoUri != null && !latest.videoUri.isEmpty()) {
                        Log.i(TAG, "Retrying failed upload: id=" + latest.id);
                        Uri uri = Uri.parse(latest.videoUri);
                        mainHandler.post(() -> {
                            Toast.makeText(this, R.string.upload_retry_on_start, Toast.LENGTH_SHORT).show();
                            switchToUploadForeground();
                            showUploadInProgressBanner();
                        });
                        uriForUpload = uri;
                        uploadRecordingToAnalyze();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "retryFailedUploads failed", e);
            }
        });
    }

    private void showUploadInProgressBanner() {
        if (topBannerView == null) return;
        bannerTitleText.setText(R.string.upload_progress_banner);
        bannerCountdownText.setVisibility(View.GONE);
        bannerMoreButton.setVisibility(View.GONE);
        topBannerView.setBackgroundResource(R.drawable.bg_top_banner_checking);
        if (!topBannerAdded) {
            topBannerAdded = safeAddView(topBannerView, topBannerParams);
        } else {
            safeUpdateViewLayout(topBannerView, topBannerParams);
        }
    }

    private void monitorRateLimitHeaders(HttpURLConnection connection) {
        String limit = connection.getHeaderField("X-RateLimit-Limit");
        String remaining = connection.getHeaderField("X-RateLimit-Remaining");
        String reset = connection.getHeaderField("X-RateLimit-Reset");

        if (limit != null || remaining != null) {
            Log.i(TAG, "RateLimit: limit=" + limit + ", remaining=" + remaining + ", reset=" + reset);
        }

        if (remaining != null) {
            try {
                int remainingVal = Integer.parseInt(remaining.trim());
                if (remainingVal <= 3) {
                    mainHandler.post(() -> Toast.makeText(this,
                            R.string.rate_limit_near, Toast.LENGTH_SHORT).show());
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
