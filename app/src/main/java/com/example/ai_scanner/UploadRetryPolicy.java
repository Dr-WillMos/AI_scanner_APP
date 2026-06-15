package com.example.ai_scanner;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public final class UploadRetryPolicy {

    // 连接超时：15秒；读超时：60秒（视频上传+分析可能需要较长时间）
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 60_000;

    public static final int MAX_NETWORK_RETRIES = 3;
    private static final long[] NETWORK_RETRY_DELAYS_MS = { 1000L, 2000L, 4000L };

    private UploadRetryPolicy() {}

    public static long getNetworkRetryDelayMs(int attempt) {
        if (attempt < 0 || attempt >= NETWORK_RETRY_DELAYS_MS.length) return 4000L;
        return NETWORK_RETRY_DELAYS_MS[attempt];
    }

    public static boolean isRetryableException(Throwable t) {
        if (t instanceof FileNotFoundException) {
            return false;
        }
        return t instanceof SocketTimeoutException
                || t instanceof UnknownHostException
                || t instanceof ConnectException
                || t instanceof java.io.IOException;
    }

    public static boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 429
                || statusCode == 502
                || statusCode == 500
                || statusCode == 503;
    }

    public static boolean isAuthFailure(int statusCode) {
        return statusCode == 401;
    }

    public static boolean isBadRequest(int statusCode) {
        return statusCode == 400;
    }

    public static long getHttpErrorRetryDelayMs(int statusCode, String retryAfterHeader) {
        if (statusCode == 429) {
            if (retryAfterHeader != null && !retryAfterHeader.isEmpty()) {
                try {
                    return TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfterHeader.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return 45_000L;
        }
        if (statusCode == 502) return 15_000L;
        if (statusCode == 500 || statusCode == 503) return 10_000L;
        return 10_000L;
    }

    public static int getMaxHttpRetries(int statusCode) {
        if (statusCode == 502) return 2;
        if (statusCode == 500 || statusCode == 503) return 3;
        if (statusCode == 429) return 3;
        return 2;
    }

    /**
     * 判断上一个错误是否为 HTTP 状态码错误（而非网络异常）。
     * 用于避免 HTTP 重试后再次执行网络重试延迟（双重等待）。
     */
    public static boolean isHttpError(int statusCode) {
        return statusCode >= 400;
    }
}
