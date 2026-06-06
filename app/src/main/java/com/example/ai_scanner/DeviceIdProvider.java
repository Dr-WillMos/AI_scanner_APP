package com.example.ai_scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.UUID;

public final class DeviceIdProvider {

    private static final String PREFS_NAME = "device_id_prefs";
    private static final String KEY_DEVICE_ID = "device_id";

    private DeviceIdProvider() {
    }

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = prefs.getString(KEY_DEVICE_ID, null);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        String androidId = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        String deviceId = (androidId != null && !androidId.isEmpty()) ? androidId : UUID.randomUUID().toString();

        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        return deviceId;
    }
}
