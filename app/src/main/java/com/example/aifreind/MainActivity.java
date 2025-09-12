package com.example.aifreind;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_PERMISSIONS = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.startServiceBtn);
        stopBtn = findViewById(R.id.stopServiceBtn);

        // Ask required permissions (audio + storage for OCR logs if needed)
        checkAndRequestPermissions();

        startBtn.setOnClickListener(v -> {
            // Ask system for screen capture permission
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent intent = mpm.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_SCREEN_CAPTURE);
        });

        stopBtn.setOnClickListener(v -> {
            // Stop both services when user clicks stop
            stopService(new Intent(this, BackgroundTaskService.class));
            stopService(new Intent(this, ScreenCaptureService.class));
        });
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Add FOREGROUND_SERVICE_MEDIA_PROJECTION for Android 13+
            permissions = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.INTERNET,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            };
        }

        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK) {
            // Start background service (voice + AI pipeline)
            Intent bgIntent = new Intent(this, BackgroundTaskService.class);
            startService(bgIntent);

            // Start screen capture service (OCR captions → AI pipeline)
            Intent captureIntent = new Intent(this, ScreenCaptureService.class);
            captureIntent.putExtra("resultCode", resultCode);
            captureIntent.putExtra("data", data);
            startService(captureIntent);
        }
    }

    // Optional: log permission results
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    // Permission denied → close app or warn user
                    finish();
                    return;
                }
            }
        }
    }
}
