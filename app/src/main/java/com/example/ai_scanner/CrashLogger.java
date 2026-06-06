package com.example.ai_scanner;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static final String CRASH_FILE_NAME = "crash_log.txt";
    private static File crashLogFile;
    private static Thread.UncaughtExceptionHandler originalHandler;

    private CrashLogger() {}

    public static void install(File cacheDir) {
        crashLogFile = new File(cacheDir, CRASH_FILE_NAME);
        originalHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashToFile(throwable);

            if (originalHandler != null) {
                originalHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static void writeCrashToFile(Throwable throwable) {
        try (FileWriter fw = new FileWriter(crashLogFile, true);
             PrintWriter pw = new PrintWriter(fw)) {

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            pw.println("=== Crash at " + timestamp + " ===");
            pw.println("Thread: " + Thread.currentThread().getName());
            pw.println();

            throwable.printStackTrace(pw);

            Throwable cause = throwable.getCause();
            while (cause != null) {
                pw.println("Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(pw);
                cause = cause.getCause();
            }

            pw.println();
            pw.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
        }
    }

    public static boolean hasCrashLog() {
        return crashLogFile != null && crashLogFile.exists() && crashLogFile.length() > 0;
    }

    public static String readCrashLog() {
        if (!hasCrashLog()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(crashLogFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }

    public static void clearCrashLog() {
        if (crashLogFile != null && crashLogFile.exists()) {
            crashLogFile.delete();
        }
    }
}
