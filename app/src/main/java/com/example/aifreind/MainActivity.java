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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;
    private TextView positiveCountView, negativeCountView, negativePercentageView;
    private TextView positiveTrendView, negativeTrendView, riskLevelText, lateNightSessions, weeklyInsights;
    private ProgressBar negativeProgressBar;
    private LinearLayout graphContainer;
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
    private int lateNightCount = 0;

    // Track previous counts for trend calculation
    private int previousPositiveCount = 0;
    private int previousNegativeCount = 0;

    // Depression risk tracking
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "MindShiftPrefs";
    private static final String DAILY_DATA_KEY = "daily_mental_data";
    private static final String LATE_NIGHT_KEY = "late_night_sessions";

    // Graph data
    private List<MentalHealthData> weeklyData = new ArrayList<>();
    private Paint graphPaint, riskPaint, gridPaint, textPaint;
    private int[] riskColors = {Color.GREEN, Color.YELLOW, Color.rgb(255, 165, 0), Color.RED};

    // Handler for late-night monitoring
    private Handler lateNightHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Initialize all UI components
        initializeViews();

        // Initialize graph paints
        initializeGraphPaints();

        // Load historical data
        loadHistoricalData();

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

        // Start late night monitoring
        startLateNightMonitoring();
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
        positiveTrendView = findViewById(R.id.positiveTrend);
        negativeTrendView = findViewById(R.id.negativeTrend);
        riskLevelText = findViewById(R.id.riskLevelText);
        lateNightSessions = findViewById(R.id.lateNightSessions);
        weeklyInsights = findViewById(R.id.weeklyInsights);
        graphContainer = findViewById(R.id.graphContainer);
    }

    /**
     * Initialize graph painting tools
     */
    private void initializeGraphPaints() {
        // Graph line paint
        graphPaint = new Paint();
        graphPaint.setColor(Color.parseColor("#1A73E8"));
        graphPaint.setStrokeWidth(6f);
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setAntiAlias(true);
        graphPaint.setStrokeCap(Paint.Cap.ROUND);

        // Risk area paint
        riskPaint = new Paint();
        riskPaint.setStyle(Paint.Style.FILL);
        riskPaint.setAntiAlias(true);

        // Grid paint
        gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#E0E0E0"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        // Text paint
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#666666"));
        textPaint.setTextSize(36f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Load historical mental health data
     */
    private void loadHistoricalData() {
        String dailyDataJson = sharedPreferences.getString(DAILY_DATA_KEY, "[]");
        lateNightCount = sharedPreferences.getInt(LATE_NIGHT_KEY, 0);

        try {
            JSONArray jsonArray = new JSONArray(dailyDataJson);
            weeklyData.clear();

            // Generate sample data for the last 7 days if none exists
            if (jsonArray.length() == 0) {
                generateSampleData();
            } else {
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    MentalHealthData data = new MentalHealthData(
                            jsonObject.getString("date"),
                            jsonObject.getInt("negativeContent"),
                            jsonObject.getInt("positiveContent"),
                            jsonObject.getInt("screenTime"),
                            jsonObject.getBoolean("lateNight")
                    );
                    weeklyData.add(data);
                }
            }
        } catch (JSONException e) {
            Log.e("MainActivity", "Error loading historical data", e);
            generateSampleData();
        }
    }

    /**
     * Generate sample data for demonstration
     */
    private void generateSampleData() {
        weeklyData.clear();
        Calendar calendar = Calendar.getInstance();
        Random random = new Random();

        for (int i = 6; i >= 0; i--) {
            calendar.add(Calendar.DAY_OF_YEAR, -1);
            Date date = calendar.getTime();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

            int negative = random.nextInt(20);
            int positive = random.nextInt(30) + 10;
            int screenTime = random.nextInt(120) + 60;
            boolean lateNight = random.nextBoolean();

            weeklyData.add(new MentalHealthData(
                    sdf.format(date),
                    negative,
                    positive,
                    screenTime,
                    lateNight
            ));
        }

        saveHistoricalData();
    }

    /**
     * Save current data to SharedPreferences
     */
    private void saveHistoricalData() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (MentalHealthData data : weeklyData) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("date", data.getDate());
                jsonObject.put("negativeContent", data.getNegativeContent());
                jsonObject.put("positiveContent", data.getPositiveContent());
                jsonObject.put("screenTime", data.getScreenTime());
                jsonObject.put("lateNight", data.isLateNight());
                jsonArray.put(jsonObject);
            }

            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(DAILY_DATA_KEY, jsonArray.toString());
            editor.putInt(LATE_NIGHT_KEY, lateNightCount);
            editor.apply();
        } catch (JSONException e) {
            Log.e("MainActivity", "Error saving historical data", e);
        }
    }

    /**
     * Start monitoring for late-night usage
     */
    private void startLateNightMonitoring() {
        Runnable lateNightChecker = new Runnable() {
            @Override
            public void run() {
                checkLateNightUsage();
                lateNightHandler.postDelayed(this, 60000); // Check every minute
            }
        };
        lateNightHandler.postDelayed(lateNightChecker, 1000);
    }

    /**
     * Check if current time is late night and user is active
     */
    private void checkLateNightUsage() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);

        // Late night hours: 10 PM to 4 AM
        boolean isLateNight = (hour >= 22 || hour <= 4);

        if (isLateNight && isServiceRunning()) {
            // Increment late night counter
            lateNightCount++;
            saveHistoricalData();

            // Show late night warning every 30 minutes
            if (lateNightCount % 30 == 0) {
                showLateNightWarning();
            }

            updateUI();
        }
    }

    /**
     * Check if monitoring service is running
     */
    private boolean isServiceRunning() {
        return positiveCount > 0 || negativeCount > 0;
    }

    /**
     * Show late night usage warning
     */
    private void showLateNightWarning() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "break_reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🌙 Late Night Usage Alert")
                    .setContentText("Consider taking a break for better sleep")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("📱 **Late Night Digital Usage Detected**\n\n" +
                                    "Using your phone late at night can:\n\n" +
                                    "• 🌙 Disrupt your sleep cycle\n" +
                                    "• 🧠 Affect mental clarity tomorrow\n" +
                                    "• 😴 Reduce sleep quality\n" +
                                    "• 📉 Impact overall mental health\n\n" +
                                    "💡 **Recommendation:**\n" +
                                    "Try to put your phone away at least 1 hour before bedtime for better mental wellness!"))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30000)
                    .setColor(Color.parseColor("#6A5ACD"));

            manager.notify(1006, builder.build());
            safeLog("✅ Late night warning notification sent");
        } catch (Exception e) {
            safeLog("❌ Late night notification failed: " + e.getMessage());
        }
    }

    /**
     * Update today's data with current counts
     */
    private void updateTodaysData() {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String today = sdf.format(calendar.getTime());

        // Find today's data or create new
        MentalHealthData todayData = null;
        for (MentalHealthData data : weeklyData) {
            if (data.getDate().equals(today)) {
                todayData = data;
                break;
            }
        }

        if (todayData == null) {
            // Remove oldest data if we have 7 days
            if (weeklyData.size() >= 7) {
                weeklyData.remove(0);
            }

            // Create new data for today
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            boolean isLateNight = (hour >= 22 || hour <= 4);

            todayData = new MentalHealthData(today, negativeCount, positiveCount, 0, isLateNight);
            weeklyData.add(todayData);
        } else {
            // Update existing data
            todayData.setNegativeContent(negativeCount);
            todayData.setPositiveContent(positiveCount);
        }

        saveHistoricalData();
        drawMentalHealthGraph();
    }

    /**
     * Draw mental health graph
     */
    private void drawMentalHealthGraph() {
        graphContainer.removeAllViews();

        // Create custom view for graph
        View graphView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                drawGraph(canvas);
            }
        };

        graphView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        graphContainer.addView(graphView);
    }

    /**
     * Draw the actual graph on canvas
     */
    private void drawGraph(Canvas canvas) {
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        int padding = 50;

        // Draw grid lines
        for (int i = 1; i <= 4; i++) {
            float y = height - padding - (i * (height - 2 * padding) / 4);
            canvas.drawLine(padding, y, width - padding, y, gridPaint);
        }

        if (weeklyData.isEmpty()) return;

        // Calculate depression risk scores and plot points
        List<Float> riskScores = new ArrayList<>();
        for (MentalHealthData data : weeklyData) {
            riskScores.add(calculateDepressionRisk(data));
        }

        // Draw risk areas
        drawRiskAreas(canvas, width, height, padding);

        // Draw graph line
        Path path = new Path();
        float xStep = (float) (width - 2 * padding) / (riskScores.size() - 1);

        for (int i = 0; i < riskScores.size(); i++) {
            float x = padding + (i * xStep);
            float y = height - padding - (riskScores.get(i) * (height - 2 * padding) / 100);

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }

            // Draw data points
            canvas.drawCircle(x, y, 8, graphPaint);
        }

        canvas.drawPath(path, graphPaint);

        // Update risk level text
        updateRiskLevel(riskScores.get(riskScores.size() - 1));
    }

    /**
     * Draw colored risk areas behind the graph
     */
    private void drawRiskAreas(Canvas canvas, int width, int height, int padding) {
        int[] riskLevels = {25, 50, 75, 100};

        for (int i = 0; i < riskLevels.length; i++) {
            float top = height - padding - (riskLevels[i] * (height - 2 * padding) / 100);
            float bottom = (i == 0) ? height - padding :
                    height - padding - (riskLevels[i-1] * (height - 2 * padding) / 100);

            riskPaint.setColor(riskColors[i]);
            riskPaint.setAlpha(30); // Semi-transparent

            canvas.drawRect(padding, top, width - padding, bottom, riskPaint);
        }
    }

    /**
     * Calculate depression risk score (0-100)
     */
    private float calculateDepressionRisk(MentalHealthData data) {
        float risk = 0f;

        // Negative content impact (40% weight)
        risk += (data.getNegativeContent() / 50f) * 40;

        // Positive content balance (20% weight)
        float positivityRatio = (data.getPositiveContent() == 0) ? 1 :
                (float) data.getNegativeContent() / data.getPositiveContent();
        risk += Math.min(positivityRatio * 20, 20);

        // Late night usage impact (20% weight)
        if (data.isLateNight()) {
            risk += 20;
        }

        // Screen time impact (20% weight)
        risk += Math.min(data.getScreenTime() / 300f * 20, 20);

        return Math.min(risk, 100);
    }

    /**
     * Update risk level text based on current score
     */
    private void updateRiskLevel(float currentRisk) {
        String riskLevel;
        int color;

        if (currentRisk <= 25) {
            riskLevel = "Low Risk";
            color = Color.parseColor("#4CAF50");
        } else if (currentRisk <= 50) {
            riskLevel = "Moderate Risk";
            color = Color.parseColor("#FFC107");
        } else if (currentRisk <= 75) {
            riskLevel = "High Risk";
            color = Color.parseColor("#FF9800");
        } else {
            riskLevel = "Very High Risk";
            color = Color.parseColor("#F44336");
        }

        riskLevelText.setText(riskLevel);
        riskLevelText.setTextColor(color);

        // Update insights
        updateWeeklyInsights();
    }

    /**
     * Generate weekly insights
     */
    private void updateWeeklyInsights() {
        if (weeklyData.size() < 2) return;

        MentalHealthData currentWeek = weeklyData.get(weeklyData.size() - 1);
        MentalHealthData previousWeek = weeklyData.get(weeklyData.size() - 2);

        int negativeChange = currentWeek.getNegativeContent() - previousWeek.getNegativeContent();
        int positiveChange = currentWeek.getPositiveContent() - previousWeek.getPositiveContent();

        StringBuilder insights = new StringBuilder();

        if (negativeChange < 0) {
            insights.append("• You watched ").append(Math.abs(negativeChange)).append("% less negative content this week\n");
        } else {
            insights.append("• Negative content increased by ").append(negativeChange).append("%\n");
        }

        if (positiveChange > 0) {
            insights.append("• Positive engagement up by ").append(positiveChange).append("%\n");
        }

        insights.append("• ").append(lateNightCount).append(" late-night sessions detected\n");
        insights.append("• Mental wellness trend: ").append(riskLevelText.getText());

        weeklyInsights.setText(insights.toString());
        lateNightSessions.setText("🌙 " + lateNightCount + " Late Nights");
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

                    safeLog("📊 Counts updated - Positive: " + positive + ", Negative: " + negative);
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
                    safeLog("🔄 Received fallback close Instagram broadcast");
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

            // Update today's data and graph
            updateTodaysData();

            safeLog("🔄 UI Updated - Positive: " + positiveCount +
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

        safeLog("📈 Trends - Positive: " + positiveTrendText + ", Negative: " + negativeTrendText);
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
        safeLog("🚪 Enhanced notification system activated");

        // 1. Stop monitoring services
        stopAllServices();

        // 2. Show primary break notification
        showPrimaryBreakNotification();

        // 3. Show wellness tips notification
        showWellnessTipsNotification();

        // 4. Navigate to home screen
        goToHomeScreenGently();

        safeLog("✅ Complete break system activated");
    }

    private void showPrimaryBreakNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            // Include current stats in notification
            String statsMessage = "📊 Your Session Stats:\n" +
                    "• Positive Content: " + positiveCount + "\n" +
                    "• Mindful Alerts: " + negativeCount + "\n" +
                    "• Negative Ratio: " + calculateNegativePercentage() + "%\n\n";

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "break_reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🌱 Mindful Break Activated")
                    .setContentText("Healthy Instagram break started")
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(statsMessage +
                                    "✨ Great job practicing digital wellness! ✨\n\n" +
                                    "Your Jarvis AI assistant has activated a mindful break from Instagram.\n\n" +
                                    "🎯 Benefits you're getting:\n" +
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
        // Stop late night monitoring
        lateNightHandler.removeCallbacksAndMessages(null);
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

    /**
     * Data class for mental health tracking
     */
    private static class MentalHealthData {
        private String date;
        private int negativeContent;
        private int positiveContent;
        private int screenTime; // in minutes
        private boolean lateNight;

        public MentalHealthData(String date, int negativeContent, int positiveContent,
                                int screenTime, boolean lateNight) {
            this.date = date;
            this.negativeContent = negativeContent;
            this.positiveContent = positiveContent;
            this.screenTime = screenTime;
            this.lateNight = lateNight;
        }

        // Getters and setters
        public String getDate() { return date; }
        public int getNegativeContent() { return negativeContent; }
        public int getPositiveContent() { return positiveContent; }
        public int getScreenTime() { return screenTime; }
        public boolean isLateNight() { return lateNight; }

        public void setNegativeContent(int negativeContent) { this.negativeContent = negativeContent; }
        public void setPositiveContent(int positiveContent) { this.positiveContent = positiveContent; }
        public void setScreenTime(int screenTime) { this.screenTime = screenTime; }
        public void setLateNight(boolean lateNight) { this.lateNight = lateNight; }
    }
}