package com.example.ai_scanner;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class ScreenCapturePermissionActivity extends AppCompatActivity {

    private MediaProjectionManager mediaProjectionManager;

    private final ActivityResultLauncher<Intent> captureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent serviceIntent = new Intent(this, FloatingOverlayService.class);
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    serviceIntent.setAction(FloatingOverlayService.ACTION_START_CAPTURE);
                    serviceIntent.putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, result.getResultCode());
                    serviceIntent.putExtra(FloatingOverlayService.EXTRA_RESULT_DATA, result.getData());
                } else {
                    serviceIntent.setAction(FloatingOverlayService.ACTION_CAPTURE_DENIED);
                    Toast.makeText(this, R.string.record_permission_missing, Toast.LENGTH_SHORT).show();
                }
                startService(serviceIntent);
                closePermissionTask();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mediaProjectionManager == null) {
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show();
            closePermissionTask();
            return;
        }
        captureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
    }

    private void closePermissionTask() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }
}

