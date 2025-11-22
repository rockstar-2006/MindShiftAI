package com.example.aifreind;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class NotificationDismissReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra("notification_id", -1);

        if (notificationId != -1) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(notificationId);
                Toast.makeText(context, "Notification dismissed", Toast.LENGTH_SHORT).show();
            }
        }
    }
}