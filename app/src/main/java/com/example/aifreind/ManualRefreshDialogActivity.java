package com.example.aifreind;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class ManualRefreshDialogActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_manual_refresh);

        TextView message = findViewById(R.id.dialog_message);
        Button exitBtn = findViewById(R.id.btn_exit);
        Button continueBtn = findViewById(R.id.btn_continue);

        // Jarvis speech can be triggered from service before showing dialog
        // message.setText("Jarvis noticed negative content. Swipe down in Instagram to refresh feed.");

        exitBtn.setOnClickListener(v -> {
            finishAffinity(); // closes app
        });

        continueBtn.setOnClickListener(v -> {
            finish(); // closes dialog
        });
    }

    @Override
    public void onBackPressed() {
        // Prevent dialog dismissal by back press
    }
}
