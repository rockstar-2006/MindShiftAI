package com.example.aifreind;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;
    private TextView positiveCountView, negativeCountView, negativePercentageView;
    private TextView positiveTrendView, negativeTrendView;
    private ProgressBar negativeProgressBar;
    private static final int REQUEST_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_PERMISSIONS = 2001;
    private BroadcastReceiver closeInstagramFallbackReceiver;
    private BroadcastReceiver continueExitReceiver;
    private BroadcastReceiver feedRefreshReceiver;
    private BroadcastReceiver countsReceiver;
    private BroadcastReceiver closeInstagramReceiver;

    // Track counts
    private int positiveCount = 0;
    private int negativeCount = 0;

    // Track previous counts for trend calculation
    private int previousPositiveCount = 0;
    private int previousNegativeCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize all UI components
        initializeViews();

        // Initialize UI with default values
        updateUI();

        // Create notification channels
        createNotificationChannels();

        // Check and request permissions
        checkAndRequestPermissions();

        // Set up button click listeners
        setupButtonListeners();

        // Register all broadcast receivers
        registerBroadcastReceivers();
    }

    /**
     * Initialize all UI components
     */
    private void initializeViews() {
        startBtn = findViewById(R.id.startServiceBtn);
        stopBtn = findViewById(R.id.stopServiceBtn);
        positiveCountView = findViewById(R.id.positiveCount);
        negativeCountView = findViewById(R.id.negativeCount);
        negativePercentageView = findViewById(R.id.negativePercentage);
        negativeProgressBar = findViewById(R.id.negativeProgress);

        // Initialize trend views

    }

    /**
     * Set up button click listeners
     */
    private void setupButtonListeners() {
        startBtn.setOnClickListener(v -> startScreenCapture());
        stopBtn.setOnClickListener(v -> stopAllServices());
    }

    /**
     * Register all broadcast receivers
     */
    private void registerBroadcastReceivers() {
        // ---------------------------
        // Listen for COUNT_UPDATE broadcast
        // ---------------------------
        countsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("COUNT_UPDATE".equals(intent.getAction())) {
                    int positive = intent.getIntExtra("positive_count", 0);
                    int negative = intent.getIntExtra("negative_count", 0);

                    // Update counts
                    positiveCount = positive;
                    negativeCount = negative;

                    // Update UI
                    updateUI();

                    safeLog(" Counts updated - Positive: " + positive + ", Negative: " + negative);
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

        // ---------------------------
        // Listen for CLOSE_INSTAGRAM broadcast
        // ---------------------------
        closeInstagramReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("CLOSE_INSTAGRAM".equals(intent.getAction())) {
                    safeLog("📡 Received CLOSE_INSTAGRAM broadcast");
                    showEnhancedBreakNotification();
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(closeInstagramReceiver, new IntentFilter("CLOSE_INSTAGRAM"));

        // ---------------------------
        // Listen for CLOSE_INSTAGRAM_FALLBACK broadcast
        // ---------------------------
        closeInstagramFallbackReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("CLOSE_INSTAGRAM_FALLBACK".equals(intent.getAction())) {
                    safeLog(" Received fallback close Instagram broadcast");
                    showEnhancedBreakNotification();
                }
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(closeInstagramFallbackReceiver, new IntentFilter("CLOSE_INSTAGRAM_FALLBACK"));
    }

    /**
     * Update all UI elements with current counts and percentages
     */
    private void updateUI() {
        runOnUiThread(() -> {
            // Update count text views
            positiveCountView.setText(String.valueOf(positiveCount));
            negativeCountView.setText(String.valueOf(negativeCount));

            // Calculate and update negative percentage
            int negativePercentage = calculateNegativePercentage();
            negativePercentageView.setText(negativePercentage + "%");

            // Update progress bar
            negativeProgressBar.setProgress(negativePercentage);

            // Update trend percentages
            updateTrendPercentages();

            safeLog(" UI Updated - Positive: " + positiveCount +
                    ", Negative: " + negativeCount +
                    ", Percentage: " + negativePercentage + "%");
        });
    }

    /**
     * Calculate and update trend percentages for positive and negative content
     */
    private void updateTrendPercentages() {
        // Calculate positive trend
        int positiveTrend = calculateTrendPercentage(previousPositiveCount, positiveCount);
        String positiveTrendText = formatTrendText(positiveTrend, true);
        if (positiveTrendView != null) {
            positiveTrendView.setText(positiveTrendText);
        }

        // Calculate negative trend
        int negativeTrend = calculateTrendPercentage(previousNegativeCount, negativeCount);
        String negativeTrendText = formatTrendText(negativeTrend, false);
        if (negativeTrendView != null) {
            negativeTrendView.setText(negativeTrendText);
        }

        // Update previous counts for next calculation
        previousPositiveCount = positiveCount;
        previousNegativeCount = negativeCount;

        safeLog(" Trends - Positive: " + positiveTrendText + ", Negative: " + negativeTrendText);
    }

    /**
     * Calculate trend percentage between previous and current count
     */
    private int calculateTrendPercentage(int previousCount, int currentCount) {
        if (previousCount == 0) {
            // If no previous data, show 0% change
            return 0;
        }

        double change = ((double) (currentCount - previousCount) / previousCount) * 100;
        return (int) Math.round(change);
    }

    /**
     * Format trend text with appropriate sign and color indicator
     */
    private String formatTrendText(int trendPercentage, boolean isPositive) {
        if (trendPercentage > 0) {
            return "+" + trendPercentage + "%";
        } else if (trendPercentage < 0) {
            return trendPercentage + "%";
        } else {
            return "0%";
        }
    }

    /**
     * Calculate negative content percentage
     * Formula: (Negative Count / Total Count) * 100
     * Returns 0 if no content viewed
     */
    private int calculateNegativePercentage() {
        int totalContent = positiveCount + negativeCount;

        if (totalContent == 0) {
            return 0; // No content viewed yet
        }

        // Calculate percentage and round to integer
        double percentage = ((double) negativeCount / totalContent) * 100;
        return (int) Math.round(percentage);
    }

    /**
     * Get wellness message based on negative percentage
     */
    private String getWellnessMessage() {
        int percentage = calculateNegativePercentage();

        if (percentage == 0) {
            return "Great start! No negative content detected yet.";
        } else if (percentage <= 20) {
            return "Excellent! Your content consumption is very positive.";
        } else if (percentage <= 40) {
            return "Good balance! You're maintaining healthy content habits.";
        } else if (percentage <= 60) {
            return "Moderate alert. Consider taking more breaks from negative content.";
        } else if (percentage <= 80) {
            return "High negative content. Time for a mindful break!";
        } else {
            return "Very high negative content. Let's take an extended break.";
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            // Break Reminders Channel
            NotificationChannel breakChannel = new NotificationChannel(
                    "break_reminders",
                    "Break Reminders",
                    NotificationManager.IMPORTANCE_HIGH
            );
            breakChannel.setDescription("Notifications for healthy break reminders");
            breakChannel.enableLights(true);
            breakChannel.setLightColor(0xFF4CAF50);
            breakChannel.enableVibration(true);
            breakChannel.setVibrationPattern(new long[]{0, 500, 200, 500});

            // Wellness Tips Channel
            NotificationChannel wellnessChannel = new NotificationChannel(
                    "wellness_tips",
                    "Wellness Tips",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            wellnessChannel.setDescription("Digital wellness and mindfulness tips");

            manager.createNotificationChannel(breakChannel);
            manager.createNotificationChannel(wellnessChannel);
        }
    }

    // ENHANCED NOTIFICATION SYSTEM
    private void showEnhancedBreakNotification() {
        safeLog(" Enhanced notification system activated");

        // 1. Stop monitoring services
        stopAllServices();

        // 2. Show primary break notification
        showPrimaryBreakNotification();

        // 3. Show wellness tips notification
        showWellnessTipsNotification();

        // 4. Navigate to home screen
        goToHomeScreenGently();

        safeLog(" Complete break system activated");
    }

    private void showPrimaryBreakNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            // Include current stats in notification
            String statsMessage = " Your Session Stats:\n" +
                    "• Positive Content: " + positiveCount + "\n" +
                    "• Mindful Alerts: " + negativeCount + "\n" +
                    "• Negative Ratio: " + calculateNegativePercentage() + "%\n\n";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "break_reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🌱 Mindful Break Activated")
                    .setContentText("Healthy Instagram break started")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(statsMessage +
                                    " Great job practicing digital wellness! ✨\n\n" +
                                    "Your Jarvis AI assistant has activated a mindful break from Instagram.\n\n" +
                                    " Benefits you're getting:\n" +
                                    "• Reduced digital fatigue\n" +
                                    "• Improved mental clarity\n" +
                                    "• Better focus and productivity\n" +
                                    "• Healthier screen habits\n\n" +
                                    "You've been automatically navigated to home screen. Enjoy your break! 💚"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30000); // Auto-dismiss after 30 seconds

            manager.notify(1001, builder.build());
            safeLog("✅ Primary break notification sent");

        } catch (Exception e) {
            safeLog("❌ Primary notification failed: " + e.getMessage());
        }
    }

    private void showWellnessTipsNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            // Different wellness tips each time
            String[] tips = {
                    "💡 Tip: Try the 20-20-20 rule - every 20 minutes, look at something 20 feet away for 20 seconds",
                    "💧 Reminder: Stay hydrated! Drink water during your screen breaks",
                    "🏃‍♂️ Move: Stretch your neck and shoulders to relieve tension",
                    "🧠 Mindful: Take 3 deep breaths to reset your focus",
                    "👀 Rest: Your eyes deserve a break from the screen too!",
                    "💚 Wellness: Regular breaks improve both mental and physical health"
            };

            String randomTip = tips[(int) (Math.random() * tips.length)];

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "wellness_tips")
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("💡 Digital Wellness Tip")
                    .setContentText("Healthy habit suggestion")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(randomTip + "\n\n" +
                                    "Small habits create big changes in your digital wellbeing! 🌟"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setTimeoutAfter(45000); // Auto-dismiss after 45 seconds

            manager.notify(1002, builder.build());
            safeLog("✅ Wellness tips notification sent");

        } catch (Exception e) {
            safeLog("❌ Wellness tips failed: " + e.getMessage());
        }
    }

    private void showScheduledBreakNotification(int minutes) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            // Include current wellness message
            String wellnessMsg = getWellnessMessage();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "break_reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🕒 " + minutes + "-Minute Break Started")
                    .setContentText("Scheduled break from Instagram")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(wellnessMsg + "\n\n" +
                                    "Your " + minutes + "-minute mindful break has started! ⏰\n\n" +
                                    "Activity suggestions:\n" +
                                    "• Stretch and walk around 🚶‍♂️\n" +
                                    "• Hydrate with water 💧\n" +
                                    "• Practice deep breathing 🌬️\n" +
                                    "• Look at distant objects 👀\n" +
                                    "• Quick physical activity 🏃‍♂️\n\n" +
                                    "I'll notify you when the break is over! ✨"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            manager.notify(1003, builder.build());
            safeLog("✅ Scheduled break notification sent");

        } catch (Exception e) {
            safeLog("❌ Scheduled break notification failed: " + e.getMessage());
        }
    }

    private void showBreakCompleteNotification(int minutes) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "break_reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("⏰ Break Complete! 🎉")
                    .setContentText(minutes + "-minute break finished")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("Excellent! You completed a " + minutes + "-minute mindful break! 🌟\n\n" +
                                    "✨ How do you feel now? ✨\n\n" +
                                    "Ready to continue mindfully or take a longer break?\n\n" +
                                    "Your Jarvis AI is here to support your digital wellbeing journey! 💚"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            manager.notify(1004, builder.build());
            safeLog("✅ Break complete notification sent");

        } catch (Exception e) {
            safeLog("❌ Break complete notification failed: " + e.getMessage());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Unregister all receivers
        unregisterBroadcastReceivers();
    }

    /**
     * Unregister all broadcast receivers
     */
    private void unregisterBroadcastReceivers() {
        if (continueExitReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(continueExitReceiver);
        }
        if (feedRefreshReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(feedRefreshReceiver);
        }
        if (countsReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(countsReceiver);
        }
        if (closeInstagramReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(closeInstagramReceiver);
        }
        if (closeInstagramFallbackReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(closeInstagramFallbackReceiver);
        }
    }

    private void startScreenCapture() {
        // Reset ALL counts when starting service
        positiveCount = 0;
        negativeCount = 0;
        previousPositiveCount = 0;
        previousNegativeCount = 0;
        updateUI();

        // Send reset command to background service
        Intent resetIntent = new Intent(this, BackgroundTaskService.class);
        resetIntent.setAction("RESET_COUNTS");
        startService(resetIntent);

        // Start screen capture
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            Intent intent = mpm.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_SCREEN_CAPTURE);
        }

        Toast.makeText(this, "Service started - counters reset", Toast.LENGTH_SHORT).show();
    }

    private void stopAllServices() {
        stopService(new Intent(this, BackgroundTaskService.class));
        stopService(new Intent(this, ScreenCaptureService.class));

        Toast.makeText(this, "All services stopped", Toast.LENGTH_SHORT).show();
    }

    private void showContinueExitDialog() {
        runOnUiThread(() -> {
            // Include current stats in dialog
            String statsInfo = "\n📊 Current Session:\n" +
                    "• Positive Content: " + positiveCount + "\n" +
                    "• Mindful Alerts: " + negativeCount + "\n" +
                    "• Negative Ratio: " + calculateNegativePercentage() + "%\n\n";

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("🌱 Mindful Browsing")
                    .setMessage(statsInfo +
                            "Taking regular breaks helps:\n\n" +
                            "• Reduce eye strain 👀\n" +
                            "• Improve focus 🎯\n" +
                            "• Prevent mindless scrolling 🧠\n\n" +
                            "Would you like to take a break now?")
                    .setCancelable(false)
                    .setPositiveButton("Continue Browsing", (dialog, which) -> {
                        dialog.dismiss();
                        sendUserContinue();
                        showMindfulUsageTip();
                    })
                    .setNegativeButton("Take a Break", (dialog, which) -> {
                        dialog.dismiss();
                        showScheduledBreakDialog();
                    });
            builder.show();
        });
    }

    private void sendUserContinue() {
        Intent intent = new Intent("USER_CONTINUE");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void showScheduledBreakDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("🕒 Scheduled Break")
                    .setMessage("Let's take a mindful break!\n\n" +
                            "Choose your break duration:\n\n" +
                            "This helps refresh your mind and prevent digital fatigue.")
                    .setCancelable(false)
                    .setPositiveButton("2-Minute Break", (dialog, which) -> {
                        dialog.dismiss();
                        startScheduledBreak(2);
                    })
                    .setNeutralButton("5-Minute Break", (dialog, which) -> {
                        dialog.dismiss();
                        startScheduledBreak(5);
                    })
                    .setNegativeButton("Custom Break", (dialog, which) -> {
                        dialog.dismiss();
                        showCustomBreakDialog();
                    });
            builder.show();
        });
    }

    private void showCustomBreakDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("⏰ Custom Break")
                    .setMessage("How long would you like your break to be?\n\n" +
                            "Even short breaks can make a big difference in your digital wellness!")
                    .setCancelable(false)
                    .setPositiveButton("10 Minutes", (dialog, which) -> {
                        dialog.dismiss();
                        startScheduledBreak(10);
                    })
                    .setNeutralButton("15 Minutes", (dialog, which) -> {
                        dialog.dismiss();
                        startScheduledBreak(15);
                    })
                    .setNegativeButton("30 Minutes", (dialog, which) -> {
                        dialog.dismiss();
                        startScheduledBreak(30);
                    });
            builder.show();
        });
    }

    private void startScheduledBreak(int minutes) {
        stopAllServices();

        runOnUiThread(() -> {
            Toast.makeText(this, "Starting " + minutes + "-minute break... 🌱", Toast.LENGTH_LONG).show();
        });

        // Show break notification
        showScheduledBreakNotification(minutes);

        // Go to home screen gently
        goToHomeScreenGently();

        // Show break complete notification after timer
        new Handler().postDelayed(() -> {
            showBreakCompleteNotification(minutes);
        }, minutes * 60 * 1000);
    }

    private void goToHomeScreenGently() {
        new Thread(() -> {
            try {
                safeLog("🔹 Pressing HOME button gently...");
                Runtime.getRuntime().exec("input keyevent KEYCODE_HOME");
                safeLog("✅ Gently navigated to home screen");
            } catch (Exception e) {
                safeLog("❌ Home navigation failed: " + e.getMessage());
            }

            // Close our app after a delay
            runOnUiThread(() -> {
                new Handler().postDelayed(() -> {
                    safeLog("📱 Closing our app...");
                    finishAffinity();
                }, 2000);
            });
        }).start();
    }

    private void showMindfulUsageTip() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Remember to take regular breaks! ⏰", Toast.LENGTH_LONG).show();
        });
    }

    private void checkAndRequestPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.KILL_BACKGROUND_PROCESSES,
                Manifest.permission.POST_NOTIFICATIONS
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.INTERNET,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                    Manifest.permission.KILL_BACKGROUND_PROCESSES,
                    Manifest.permission.POST_NOTIFICATIONS
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
            startService(new Intent(this, BackgroundTaskService.class));

            Intent captureIntent = new Intent(this, ScreenCaptureService.class);
            captureIntent.putExtra("resultCode", resultCode);
            captureIntent.putExtra("data", data);
            startService(captureIntent);

            Toast.makeText(this, "Screen capture service started", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show();
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
                    Toast.makeText(this, "Permissions are required for full functionality!", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFeed() {
        Toast.makeText(this, "Feed refreshed!", Toast.LENGTH_SHORT).show();
    }

    // Helper method for logging
    private void safeLog(String message) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("MainActivity", message);
        }
    }
}