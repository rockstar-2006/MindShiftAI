package com.example.aifreind;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;
    private TextView positiveCountView, negativeCountView;
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_PERMISSIONS = 2001;

    private BroadcastReceiver continueExitReceiver;
    private BroadcastReceiver feedRefreshReceiver;
    private BroadcastReceiver countsReceiver; // Add this

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.startServiceBtn);
        stopBtn = findViewById(R.id.stopServiceBtn);
        positiveCountView = findViewById(R.id.positiveCount); // Add this
        negativeCountView = findViewById(R.id.negativeCount); // Add this

        checkAndRequestPermissions();

        startBtn.setOnClickListener(v -> startScreenCapture());
        stopBtn.setOnClickListener(v -> stopAllServices());

        // ---------------------------
        // Listen for COUNT_UPDATE broadcast
        // ---------------------------
        countsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("COUNT_UPDATE".equals(intent.getAction())) {
                    int positive = intent.getIntExtra("positive_count", 0);
                    int negative = intent.getIntExtra("negative_count", 0);

                    // Update UI with the counts
                    positiveCountView.setText(String.valueOf(positive));
                    negativeCountView.setText(String.valueOf(negative));
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(countsReceiver, new IntentFilter("COUNT_UPDATE"));

        // ---------------------------
        // Listen for SHOW_CONTINUE_OR_EXIT_DIALOG broadcast
        // ---------------------------
        continueExitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("SHOW_CONTINUE_OR_EXIT_DIALOG".equals(intent.getAction())) {
                    showContinueExitDialog();
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(continueExitReceiver, new IntentFilter("SHOW_CONTINUE_OR_EXIT_DIALOG"));

        // ---------------------------
        // Listen for REFRESH_FEED broadcast
        // ---------------------------
        feedRefreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("REFRESH_FEED".equals(intent.getAction())) {
                    refreshFeed();
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(feedRefreshReceiver, new IntentFilter("REFRESH_FEED"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (continueExitReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(continueExitReceiver);
        }
        if (feedRefreshReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(feedRefreshReceiver);
        }
        if (countsReceiver != null) { // Add this
            LocalBroadcastManager.getInstance(this).unregisterReceiver(countsReceiver);
        }
    }

    private void startScreenCapture() {
        // Reset counts when starting service
        Intent resetIntent = new Intent(this, BackgroundTaskService.class);
        resetIntent.setAction("RESET_COUNTS");
        startService(resetIntent);

        positiveCountView.setText("0");
        negativeCountView.setText("0");

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mpm.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_SCREEN_CAPTURE);
    }

    private void stopAllServices() {
        stopService(new Intent(this, BackgroundTaskService.class));
        stopService(new Intent(this, ScreenCaptureService.class));

        // Reset the UI counts
        positiveCountView.setText("0");
        negativeCountView.setText("0");

        Toast.makeText(this, "All services stopped", Toast.LENGTH_SHORT).show();
    }

    private void showContinueExitDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Jarvis AI")
                    .setMessage("Do you want to continue watching reels or take a break?")
                    .setCancelable(false)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        dialog.dismiss();
                        sendUserContinue();
                        Toast.makeText(this, "Resuming feed...", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Exit", (dialog, which) -> {
                        dialog.dismiss();
                        takeBreak();
                    });
            builder.show();
        });
    }

    private void sendUserContinue() {
        // Notify the service to resume
        Intent intent = new Intent("USER_CONTINUE");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void takeBreak() {
        Toast.makeText(this, "Taking a 2-minute break...", Toast.LENGTH_SHORT).show();

        // Stop services temporarily
        stopAllServices();

        // Restart services after 2 minutes
        new Handler().postDelayed(() -> {
            startService(new Intent(this, BackgroundTaskService.class));
            startService(new Intent(this, ScreenCaptureService.class));
            Toast.makeText(this, "Break over. Resuming...", Toast.LENGTH_SHORT).show();
        }, 2 * 60 * 1000);
    }

    private void checkAndRequestPermissions() {
        String[] permissions = { Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.INTERNET,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            };
        }

        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
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
            Intent bgIntent = new Intent(this, BackgroundTaskService.class);
            startService(bgIntent);

            Intent captureIntent = new Intent(this, ScreenCaptureService.class);
            captureIntent.putExtra("resultCode", resultCode);
            captureIntent.putExtra("data", data);
            startService(captureIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions are required!", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
        }
    }

    private void refreshFeed() {
        // Implement your feed refresh logic here
        Toast.makeText(this, "Feed refreshed!", Toast.LENGTH_SHORT).show();
    }
}