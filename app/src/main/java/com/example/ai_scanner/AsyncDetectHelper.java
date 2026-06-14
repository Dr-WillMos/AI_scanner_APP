package com.example.ai_scanner;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Helper for async detection flow per APP_API_REFERENCE.md §4.2 / §4.3.
 *
 * Usage:
 *   1. {@link #submitAsyncTask(Context, Uri, String, String, String, String)}
 *      → returns a {@link AsyncTaskInfo} with a taskId (status PENDING).
 *   2. {@link #pollTaskStatus(Context, String, String, int)}
 *      → loop until status is DONE or FAILED (suggested: up to 60 polls, 1s interval).
 *   3. On DONE, extract the result as {@link AsyncDetectResult}.
 */
public final class AsyncDetectHelper {

    private static final String TAG = "AsyncDetectHelper";
    private static final int NETWORK_TIMEOUT_MS = 30000;
    private static final int IO_BUFFER_SIZE = 8192;

    private AsyncDetectHelper() {}

    // ──────────────────────────────────────────────
    //  Public data classes
    // ──────────────────────────────────────────────

    /** Returned by {@link #submitAsyncTask}. */
    public static class AsyncTaskInfo {
        public final String taskId;
        public final String status;
        public final String createdAt;

        public AsyncTaskInfo(String taskId, String status, String createdAt) {
            this.taskId = taskId;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    /** Returned by {@link #pollTaskStatus} when the task is DONE. */
    public static class AsyncDetectResult {
        public final String riskLevel;
        public final double score;
        public final String reason;
        public final String source;
        public final String transcription;

        public AsyncDetectResult(String riskLevel, double score,
                                  String reason, String source,
                                  String transcription) {
            this.riskLevel = riskLevel;
            this.score = score;
            this.reason = reason;
            this.source = source;
            this.transcription = transcription;
        }
    }

    /** Returned by {@link #pollTaskStatus} — encapsulates both in-flight and terminal states. */
    public static class TaskStatusResult {
        public final String taskId;
        public final String status;       // PENDING / PROCESSING / DONE / FAILED
        public final String createdAt;
        public final String updatedAt;
        public final AsyncDetectResult result;  // non-null only when status == DONE
        public final String error;              // non-null only when status == FAILED

        public TaskStatusResult(String taskId, String status, String createdAt,
                                 String updatedAt, AsyncDetectResult result, String error) {
            this.taskId = taskId;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.result = result;
            this.error = error;
        }
    }

    // ──────────────────────────────────────────────
    //  Submit async task
    // ──────────────────────────────────────────────

    /**
     * POST /api/v1/detect/async
     *
     * @param context  Android context (for prefs & content-resolver)
     * @param videoUri URI of the recorded MP4 file
     * @param deviceId device identifier
     * @param authorId publisher identifier
     * @param apiKey   X-API-Key header value
     * @param boundary MIME boundary for multipart
     * @return AsyncTaskInfo with taskId, or null on failure
     */
    public static AsyncTaskInfo submitAsyncTask(Context context, Uri videoUri,
                                                 String deviceId, String authorId,
                                                 String apiKey, String boundary) {
        String baseUrl = getBaseUrl(context);
        String requestId = java.util.UUID.randomUUID().toString();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + "/api/v1/detect/async");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("X-API-Key", apiKey);
            connection.setRequestProperty("X-Request-Id", requestId);

            writeMultipartBody(context, connection, videoUri, deviceId, authorId, boundary);

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection);

            Log.i(TAG, "Async submit response: code=" + responseCode + " body=" + responseBody);

            // Async endpoint returns HTTP 202 (or 2xx); code inside body is still 200 on success
            if (responseCode >= 200 && responseCode < 300) {
                return parseSubmitResponse(responseBody);
            }

            if (responseCode == 401) {
                Log.w(TAG, "Async submit got 401 — caller should re-register key and retry");
                return null;
            }

            Log.e(TAG, "Async submit failed: HTTP " + responseCode + " body=" + responseBody);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "submitAsyncTask failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * GET /api/v1/detect/{taskId}/status
     *
     * @param context Android context
     * @param taskId  the UUID returned by submitAsyncTask
     * @param apiKey  X-API-Key header value
     * @return TaskStatusResult, or null on failure
     */
    public static TaskStatusResult pollTaskStatus(Context context, String taskId, String apiKey) {
        String baseUrl = getBaseUrl(context);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + "/api/v1/detect/" + taskId + "/status");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setRequestProperty("X-API-Key", apiKey);

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection);

            if (responseCode == 404) {
                Log.w(TAG, "Task not found (may have expired): " + taskId);
                return null;
            }

            if (responseCode >= 200 && responseCode < 300) {
                return parseStatusResponse(responseBody);
            }

            Log.e(TAG, "pollTaskStatus failed: HTTP " + responseCode + " body=" + responseBody);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "pollTaskStatus failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Convenience: poll in a loop until DONE / FAILED or max polls exhausted.
     *
     * @param context     Android context
     * @param taskId      async task UUID
     * @param apiKey      X-API-Key
     * @param maxPolls    maximum number of polls (suggested: 60)
     * @param intervalMs  delay between polls in ms (suggested: 1000)
     * @return the terminal TaskStatusResult, or null if exhausted / error
     */
    public static TaskStatusResult pollUntilDone(Context context, String taskId,
                                                  String apiKey,
                                                  int maxPolls, long intervalMs) {
        for (int i = 0; i < maxPolls; i++) {
            TaskStatusResult current = pollTaskStatus(context, taskId, apiKey);
            if (current == null) {
                return null;
            }

            Log.i(TAG, "Poll " + (i + 1) + "/" + maxPolls + " status=" + current.status);

            if ("DONE".equals(current.status)) {
                return current;
            }
            if ("FAILED".equals(current.status)) {
                return current;
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        Log.e(TAG, "Polling exhausted for task " + taskId + " after " + maxPolls + " attempts");
        return null;
    }

    // ──────────────────────────────────────────────
    //  Parsing
    // ──────────────────────────────────────────────

    private static AsyncTaskInfo parseSubmitResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            if (code != 200) {
                Log.e(TAG, "Submit API error: code=" + code + " msg=" + json.optString("message"));
                return null;
            }
            JSONObject data = json.getJSONObject("data");
            return new AsyncTaskInfo(
                    data.getString("taskId"),
                    data.optString("status", "PENDING"),
                    data.optString("createdAt", "")
            );
        } catch (Exception e) {
            Log.e(TAG, "parseSubmitResponse failed", e);
            return null;
        }
    }

    private static TaskStatusResult parseStatusResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            if (code != 200) {
                Log.e(TAG, "Status API error: code=" + code + " msg=" + json.optString("message"));
                return null;
            }
            JSONObject data = json.getJSONObject("data");

            String taskId = data.optString("taskId", "");
            String status = data.optString("status", "PENDING");
            String createdAt = data.optString("createdAt", "");
            String updatedAt = data.optString("updatedAt", "");
            String error = data.isNull("error") ? null : data.optString("error", null);

            AsyncDetectResult result = null;
            if ("DONE".equals(status) && !data.isNull("result")) {
                JSONObject r = data.getJSONObject("result");
                result = new AsyncDetectResult(
                        r.optString("riskLevel", "SAFE"),
                        r.optDouble("score", 0d),
                        r.isNull("reason") ? null : r.optString("reason", null),
                        r.isNull("source") ? null : r.optString("source", null),
                        r.isNull("transcription") ? null : r.optString("transcription", null)
                );
            }

            return new TaskStatusResult(taskId, status, createdAt, updatedAt, result, error);
        } catch (Exception e) {
            Log.e(TAG, "parseStatusResponse failed", e);
            return null;
        }
    }

    // ──────────────────────────────────────────────
    //  Multipart writing (mirrors FloatingOverlayService)
    // ──────────────────────────────────────────────

    private static void writeMultipartBody(Context context, HttpURLConnection connection,
                                            Uri videoUri, String deviceId, String authorId,
                                            String boundary) throws java.io.IOException {
        String lineEnd = "\r\n";
        String fileName = resolveDisplayName(context, videoUri);
        String mimeType = context.getContentResolver().getType(videoUri);
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = "video/mp4";
        }

        try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream());
             InputStream rawInput = context.getContentResolver().openInputStream(videoUri);
             BufferedInputStream in = rawInput == null ? null : new BufferedInputStream(rawInput)) {
            if (in == null) {
                throw new FileNotFoundException("Video file not accessible: " + videoUri);
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

    private static String resolveDisplayName(Context context, Uri uri) {
        String fallbackName = "capture_" + System.currentTimeMillis() + ".mp4";
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
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

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

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
            try (InputStream in = stream; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
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
