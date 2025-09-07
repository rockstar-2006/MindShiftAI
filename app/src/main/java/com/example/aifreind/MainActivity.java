package com.example.aifreind;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ask RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }

        startBtn = findViewById(R.id.startServiceBtn);
        stopBtn = findViewById(R.id.stopServiceBtn);

        startBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, BackgroundTaskService.class);
            startService(intent);
        });

        stopBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, BackgroundTaskService.class);
            stopService(intent);
        });
    }
}