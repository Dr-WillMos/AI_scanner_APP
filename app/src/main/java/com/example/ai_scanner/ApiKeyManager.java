package com.example.ai_scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Manages API key lifecycle: first-launch registration and 401-triggered re-registration.
 *
 * Follows the flow defined in APP_API_REFERENCE.md section 3 and 9.1:
 *   deviceId → POST /api/v1/keys/register → persist apiKey
 */
public final class ApiKeyManager {

    private static final String TAG = "ApiKeyManager";
    private static final int TIMEOUT_MS = 15000;
    private static final String PREFS_NAME = "api_key_registration";
    private static final String KEY_REGISTERED = "key_registered";

    private ApiKeyManager() {}

    /**
     * Called on app startup. Registers a key if one hasn't been registered yet.
     * Returns true if a key is available (either existing or newly registered).
     */
    public static boolean ensureKeyRegistered(Context context) {
        String existingKey = getApiKey(context);
        if (existingKey != null && !existingKey.isEmpty()) {
            return true;
        }
        return registerKey(context);
    }

    /**
     * Force re-registration. Used for 401 recovery.
     * Returns true on success.
     */
    public static boolean registerKey(Context context) {
        String deviceId = DeviceIdProvider.getDeviceId(context);
        String baseUrl = getBaseUrl(context);
        String deviceName = Build.MANUFACTURER + " " + Build.MODEL;

        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + "/api/v1/keys/register");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            body.put("deviceName", deviceName);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection);

            if (responseCode == 200) {
                JSONObject json = new JSONObject(responseBody);
                int code = json.optInt("code", -1);
                if (code == 200) {
                    JSONObject data = json.getJSONObject("data");
                    String apiKey = data.getString("apiKey");
                    saveApiKey(context, apiKey);
                    markRegistered(context);
                    Log.i(TAG, "API key registered successfully");
                    return true;
                } else {
                    Log.e(TAG, "Register failed: code=" + code + " message=" + json.optString("message"));
                }
            } else {
                Log.e(TAG, "Register HTTP " + responseCode + ": " + responseBody);
            }
        } catch (Exception e) {
            Log.e(TAG, "registerKey failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    public static boolean isRegistered(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_REGISTERED, false);
    }

    private static void markRegistered(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REGISTERED, true).apply();
    }

    public static String getApiKey(Context context) {
        return context.getSharedPreferences(
                context.getString(R.string.key_settings_prefs), Context.MODE_PRIVATE)
                .getString(context.getString(R.string.key_api_key), "");
    }

    private static void saveApiKey(Context context, String apiKey) {
        context.getSharedPreferences(
                context.getString(R.string.key_settings_prefs), Context.MODE_PRIVATE)
                .edit().putString(context.getString(R.string.key_api_key), apiKey).apply();
    }

    private static String getBaseUrl(Context context) {
        return context.getSharedPreferences(
                context.getString(R.string.key_settings_prefs), Context.MODE_PRIVATE)
                .getString(context.getString(R.string.key_api_base_url),
                        context.getString(R.string.api_url_default));
    }

    private static String readResponseBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream == null) return "";
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
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
}
