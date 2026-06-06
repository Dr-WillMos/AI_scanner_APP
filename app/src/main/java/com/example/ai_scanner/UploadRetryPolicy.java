package com.example.ai_scanner;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public final class UploadRetryPolicy {

    public static final int CLIENT_TIMEOUT_MS = 45_000;

    public static final int MAX_NETWORK_RETRIES = 3;
    private static final long[] NETWORK_RETRY_DELAYS_MS = { 2000L, 4000L, 8000L };

    private UploadRetryPolicy() {}

    public static long getNetworkRetryDelayMs(int attempt) {
        if (attempt < 0 || attempt >= NETWORK_RETRY_DELAYS_MS.length) return 8000L;
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
        if (statusCode == 502) return 30_000L;
        if (statusCode == 500 || statusCode == 503) return 10_000L;
        return 10_000L;
    }

    public static int getMaxHttpRetries(int statusCode) {
        if (statusCode == 502) return 2;
        if (statusCode == 500 || statusCode == 503) return 3;
        if (statusCode == 429) return 3;
        return 2;
    }
}
