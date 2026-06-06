package com.example.ai_scanner;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HistoryRepository {

    private static final String TAG = "HistoryRepository";
    private static final int NETWORK_TIMEOUT_MS = 15000;

    private HistoryRepository() {
    }

    public static HistoryPage fetchLocalHistory(Context context, int page, int size) {
        HistoryDao dao = AppDatabase.getInstance(context).historyDao();
        int total = dao.getCount();
        int offset = (page - 1) * size;
        List<HistoryEntity> entities = dao.getPagedHistory(size, offset);
        List<HistoryItem> records = new ArrayList<>();
        for (HistoryEntity entity : entities) {
            records.add(entity.toHistoryItem());
        }

        boolean hasMore = offset + size < total;
        long latestId = 0;
        if (!entities.isEmpty()) {
            latestId = entities.get(0).id;
        }

        return new HistoryPage(records, total, page, records.size(), hasMore, latestId);
    }

    private static String getBaseUrl(Context context) {
        return context.getSharedPreferences(
                context.getString(R.string.key_settings_prefs), Context.MODE_PRIVATE)
                .getString(context.getString(R.string.key_api_base_url),
                        context.getString(R.string.api_url_default));
    }

    private static String getApiKey(Context context) {
        return context.getSharedPreferences(
                context.getString(R.string.key_settings_prefs), Context.MODE_PRIVATE)
                .getString(context.getString(R.string.key_api_key), "");
    }

    public static HistoryPage fetchRemoteHistory(Context context, String deviceId,
                                                  Long afterId, int page, int size) {
        HttpURLConnection connection = null;
        try {
            String baseUrl = getBaseUrl(context);
            StringBuilder urlBuilder = new StringBuilder(baseUrl)
                    .append("/api/v1/history?deviceId=").append(deviceId);

            if (afterId != null && afterId > 0) {
                urlBuilder.append("&afterId=").append(afterId);
                if (size > 0) {
                    urlBuilder.append("&size=").append(size);
                }
            } else {
                urlBuilder.append("&page=").append(page > 0 ? page : 1);
                if (size > 0) {
                    urlBuilder.append("&size=").append(size);
                }
            }

            URL url = new URL(urlBuilder.toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(NETWORK_TIMEOUT_MS);
            connection.setReadTimeout(NETWORK_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setRequestProperty("X-API-Key", getApiKey(context));

            int responseCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection);
            if (responseCode >= 200 && responseCode < 300) {
                return parseHistoryResponse(responseBody);
            } else {
                Log.e(TAG, "history request failed: code=" + responseCode + ", body=" + responseBody);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchRemoteHistory failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static void syncRemoteToLocal(Context context, String deviceId) {
        HistoryDao dao = AppDatabase.getInstance(context).historyDao();
        Long lastId = dao.getLatestId();

        HistoryPage result = fetchRemoteHistory(context, deviceId, lastId, 1, 50);
        if (result == null || result.records.isEmpty()) {
            return;
        }

        for (HistoryItem item : result.records) {
            HistoryEntity entity = new HistoryEntity();
            entity.id = item.id;
            entity.deviceId = item.deviceId;
            entity.authorId = item.authorId;
            entity.riskLevel = item.riskLevel;
            entity.score = item.score;
            entity.createdAt = item.createdAt;
            entity.reason = "";
            entity.source = "";
            entity.transcription = "";
            dao.insert(entity);
        }

        if (result.hasMore && result.latestId > 0) {
            HistoryPage nextResult = fetchRemoteHistory(context, deviceId, result.latestId, 1, 50);
            if (nextResult != null && !nextResult.records.isEmpty()) {
                for (HistoryItem item : nextResult.records) {
                    HistoryEntity entity = new HistoryEntity();
                    entity.id = item.id;
                    entity.deviceId = item.deviceId;
                    entity.authorId = item.authorId;
                    entity.riskLevel = item.riskLevel;
                    entity.score = item.score;
                    entity.createdAt = item.createdAt;
                    entity.reason = "";
                    entity.source = "";
                    entity.transcription = "";
                    dao.insert(entity);
                }
            }
        }
    }

    private static HistoryPage parseHistoryResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            int code = json.optInt("code", -1);
            if (code != 200) {
                Log.e(TAG, "API error: code=" + code + ", message=" + json.optString("message"));
                return null;
            }

            JSONObject data = json.getJSONObject("data");
            JSONArray recordsArray = data.getJSONArray("records");
            List<HistoryItem> records = new ArrayList<>();

            for (int i = 0; i < recordsArray.length(); i++) {
                JSONObject item = recordsArray.getJSONObject(i);
                records.add(new HistoryItem(
                        item.getLong("id"),
                        item.optString("deviceId", ""),
                        item.optString("authorId", ""),
                        item.optString("riskLevel", "SAFE"),
                        item.optDouble("score", 0),
                        item.optString("createdAt", "")
                ));
            }

            return new HistoryPage(
                    records,
                    data.optLong("total", 0),
                    data.optInt("page", 1),
                    data.optInt("size", records.size()),
                    data.optBoolean("hasMore", false),
                    data.optLong("latestId", 0)
            );
        } catch (Exception e) {
            Log.e(TAG, "parseHistoryResponse failed", e);
            return null;
        }
    }

    private static String readResponseBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (stream == null) {
                return "";
            }
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

    public static class HistoryPage {
        public final List<HistoryItem> records;
        public final long total;
        public final int page;
        public final int size;
        public final boolean hasMore;
        public final long latestId;

        HistoryPage(List<HistoryItem> records, long total, int page, int size,
                    boolean hasMore, long latestId) {
            this.records = records;
            this.total = total;
            this.page = page;
            this.size = size;
            this.hasMore = hasMore;
            this.latestId = latestId;
        }
    }
}
