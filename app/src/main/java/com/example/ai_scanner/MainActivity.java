package com.example.ai_scanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.ai_scanner.NetworkInfoHelper.NetworkSnapshot;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final long SERVICE_START_DELAY_MS = 250L;
    private static final long PERMISSION_RECHECK_DELAY_MS = 500L;
    private static final String PREFS_NAME = "dev_info_prefs";
    private static final String KEY_NO_MORE_PROMPT = "no_more_dev_info_prompt";

    private TextView statusText;
    private Button permissionButton;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int refreshVersion = 0;
    private boolean overlayServiceRequested = false;
    private AlertDialog devInfoDialog;
    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                refreshOverlayStatusAndService();
            });

    private final ActivityResultLauncher<String> smsPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Toast.makeText(this, R.string.sms_permission_granted, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(getCacheDir());
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.tvStatus);
        permissionButton = findViewById(R.id.btnGrantPermission);
        Button historyButton = findViewById(R.id.btnHistory);

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS);
        }

        permissionButton.setOnClickListener(v -> showPermissionExplainDialog());
        historyButton.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class))
        );

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))
        );

        refreshOverlayStatusAndService();

        if (savedInstanceState == null) {
            showDevInfoDialogIfNeeded();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayStatusAndService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (devInfoDialog != null && devInfoDialog.isShowing()) {
            devInfoDialog.dismiss();
        }
        devInfoDialog = null;
    }

    private void showDevInfoDialogIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NO_MORE_PROMPT, false)) {
            return;
        }

        NetworkSnapshot net = NetworkInfoHelper.collect(this);

        String appInfo = buildAppInfo();
        String netInfo = buildNetInfo(net);

        android.view.View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dev_info, null);
        TextView tvDevInfo = dialogView.findViewById(R.id.tvDevInfo);
        TextView tvNetInfo = dialogView.findViewById(R.id.tvNetInfo);
        CheckBox cbNoMore = dialogView.findViewById(R.id.cbNoMorePrompt);

        tvDevInfo.setText(appInfo);
        tvNetInfo.setText(netInfo);

        devInfoDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dev_info_title)
                .setView(dialogView)
                .setPositiveButton(R.string.dev_info_ok, (dialog, which) -> {
                    if (cbNoMore.isChecked()) {
                        prefs.edit().putBoolean(KEY_NO_MORE_PROMPT, true).apply();
                        Toast.makeText(this, R.string.dev_info_disabled, Toast.LENGTH_SHORT).show();
                    }
                })
                .setCancelable(false)
                .create();

        devInfoDialog.show();
    }

    private String buildAppInfo() {
        String versionName = "未知";
        long versionCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = pi.getLongVersionCode();
            } else {
                versionCode = pi.versionCode;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        return "应用: " + getString(R.string.app_name) + "\n"
                + "版本: " + versionName + " (" + versionCode + ")\n"
                + "包名: " + getPackageName() + "\n"
                + "设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "分析端点: http://localhost:2333/analyze";
    }

    private String buildNetInfo(NetworkSnapshot net) {
        StringBuilder sb = new StringBuilder();
        sb.append("网络状态: ").append(net.connected ? "已连接" : "未连接").append("\n");
        sb.append("连接类型: ").append(net.networkType);

        if (net.wifiSsid != null) {
            sb.append("\nWiFi 名称: ").append(net.wifiSsid);
        }

        sb.append("\nIPv4 地址:");
        if (net.ipv4Addresses.isEmpty()) {
            sb.append(" 无");
        } else {
            for (String ip : net.ipv4Addresses) {
                sb.append("\n  ").append(ip);
            }
        }

        sb.append("\nIPv6 地址:");
        if (net.ipv6Addresses.isEmpty()) {
            sb.append(" 无");
        } else {
            for (String ip : net.ipv6Addresses) {
                sb.append("\n  ").append(ip);
            }
        }

        return sb.toString();
    }

    private void showPermissionExplainDialog() {
        if (canShowOverlay()) {
            refreshOverlayStatusAndService();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_explain_title)
                .setMessage(R.string.permission_explain_message)
                .setPositiveButton(R.string.go_grant, (dialog, which) -> requestOverlayPermission())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlayPermissionLauncher.launch(intent);
        }
    }

    private void refreshOverlayStatusAndService() {
        final int version = ++refreshVersion;
        boolean overlayGranted = canShowOverlay();
        updatePermissionButtonUi(overlayGranted);

        SharedPreferences settingsPrefs = getSharedPreferences(
                getString(R.string.key_settings_prefs), MODE_PRIVATE);
        boolean floatingEnabled = settingsPrefs.getBoolean(
                getString(R.string.key_floating_enabled), true);

        if (overlayGranted && floatingEnabled) {
            statusText.setText(getString(R.string.overlay_ready));
            if (overlayServiceRequested) {
                return;
            }
            mainHandler.postDelayed(() -> {
                if (version != refreshVersion) {
                    return;
                }
                if (canShowOverlay()) {
                    startService(new Intent(this, FloatingOverlayService.class));
                    overlayServiceRequested = true;
                    performHealthCheck();
                }
            }, SERVICE_START_DELAY_MS);
        } else {
            if (!overlayGranted) {
                statusText.setText(getString(R.string.overlay_not_ready));
            }
            mainHandler.postDelayed(() -> {
                if (version != refreshVersion) {
                    return;
                }
                if (canShowOverlay() && settingsPrefs.getBoolean(
                        getString(R.string.key_floating_enabled), true)) {
                    refreshOverlayStatusAndService();
                    return;
                }
                stopService(new Intent(this, FloatingOverlayService.class));
                overlayServiceRequested = false;
            }, PERMISSION_RECHECK_DELAY_MS);
        }
    }

    private void updatePermissionButtonUi(boolean granted) {
        if (permissionButton == null) {
            return;
        }
        if (granted) {
            permissionButton.setText(R.string.overlay_permission_granted);
            permissionButton.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.safe_green))
            );
        } else {
            permissionButton.setText(R.string.grant_overlay_permission);
            permissionButton.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_primary))
            );
        }
    }

    private boolean canShowOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void performHealthCheck() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                        getString(R.string.key_settings_prefs), MODE_PRIVATE);
                String baseUrl = prefs.getString(getString(R.string.key_api_base_url),
                        getString(R.string.api_url_default));
                URL url = new URL(baseUrl + "/actuator/health");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code != 200) {
                    mainHandler.post(() -> Toast.makeText(this,
                            "后端服务异常 (HTTP " + code + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.w("MainActivity", "Health check failed", e);
                mainHandler.post(() -> Toast.makeText(this,
                        "后端不可达，请检查网络和服务地址", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
