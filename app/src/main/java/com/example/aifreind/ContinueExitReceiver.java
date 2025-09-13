package com.example.aifreind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class ContinueExitReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Show a simple dialog with Continue / Exit
        new AlertDialog.Builder(context)
                .setTitle("Pause Detected")
                .setMessage("Do you want to continue watching or exit?")
                .setPositiveButton("Continue", (dialog, which) -> {
                    Toast.makeText(context, "Continuing...", Toast.LENGTH_SHORT).show();
                    // Call service to resume
                    Intent serviceIntent = new Intent(context, BackgroundTaskService.class);
                    serviceIntent.setAction("RESUME_DETECTION");
                    context.startService(serviceIntent);
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    Toast.makeText(context, "Exiting...", Toast.LENGTH_SHORT).show();
                    // Stop your service
                    context.stopService(new Intent(context, BackgroundTaskService.class));
                })
                .setCancelable(false)
                .show();
    }
}
