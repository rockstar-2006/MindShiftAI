package com.example.aifreind;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BackgroundTaskService extends Service {

    private static final String TAG = "BackgroundService";
    private static final String CHANNEL_ID = "BackgroundTaskChannel";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String MODEL = "gemini-1.5-flash";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final String MURF_URL = "https://api.murf.ai/v1/speech/generate";

    private Handler handler;
    private boolean isSpeaking = false;
    private boolean stopDetection = false;

    private boolean jarvisActive = false;
    private boolean waitingForUser = false;
    private boolean askingForContinueOrExit = false;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String geminiApiKey = BuildConfig.GEMINI_API_KEY;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;

    private final Queue<String> responseQueue = new ArrayDeque<>();
    private MediaPlayer mediaPlayer;

    private static int negativeCount = 0;
    private static int positiveCount = 0; // Add positive count

    private static String lastCaption = "";
    private static long lastCaptionAt = 0;
    private static final long COOLDOWN_MS = 8000;

    private static final String[] NEGATIVE_KEYWORDS = new String[]{
            "sad","alone","lonely","cry","crying","hurt","broken","breakup","heartbreak",
            "depressed","hate","angry","pain","tears","lost","dark","suicidal","kill","die",
            "empty","fear","stress","anxiety","overthinking","broken heart","very sad","heavy"
    };

    private SpeechRecognizer speechRecognizer;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            safeLog(Log.ERROR, "❌ Microphone permission not granted!");
        }

        createNotificationChannel();
        startForeground(1, getNotification());

        initSpeechRecognizer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "RESET_COUNTS".equals(intent.getAction())) {
            negativeCount = 0;
            positiveCount = 0;
            broadcastCounts();
            return START_STICKY;
        }

        if (stopDetection || jarvisActive || isSpeaking) {
            safeLog(Log.INFO, "🛑 Detection paused.");
            return START_STICKY;
        }

        if (intent != null && "PROCESS_CAPTION".equals(intent.getAction())) {
            String caption = intent.getStringExtra("EXTRA_CAPTION");
            if (caption != null && !caption.trim().isEmpty()) {
                handleCaption(caption.trim());
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (mediaPlayer != null) mediaPlayer.release();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis AI")
                .setContentText("Watching for negative reels...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Jarvis Background", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void initSpeechRecognizer() {
        try {
            if (speechRecognizer != null) speechRecognizer.destroy();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override
                public void onError(int error) {
                    safeLog(Log.ERROR,"Speech error: "+error);
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        initSpeechRecognizer();
                    }
                    if (waitingForUser) handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 800);
                }
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts != null && !texts.isEmpty()) onUserSpeechDetected(texts.get(0));
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) { safeLog(Log.ERROR, "SpeechRecognizer init failed: " + e.getMessage()); }
    }

    private void startListeningToUser() {
        if (speechRecognizer != null && waitingForUser && !isSpeaking) {
            handler.post(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    speechRecognizer.startListening(intent);
                    safeLog(Log.INFO, "🎤 Jarvis is now listening...");
                } catch (Exception e) {
                    safeLog(Log.ERROR,"❌ startListening failed: "+e.getMessage());
                    initSpeechRecognizer();
                    if (waitingForUser) handler.postDelayed(this::startListeningToUser, 800);
                }
            });
        }
    }

    public static void enqueueCaption(Context context, String caption) {
        Intent intent = new Intent(context, BackgroundTaskService.class);
        intent.setAction("PROCESS_CAPTION");
        intent.putExtra("EXTRA_CAPTION", caption);
        context.startService(intent);
    }

    private void handleCaption(String caption) {
        String normalized = normalizeCaption(caption);
        long now = System.currentTimeMillis();

        if (normalized.equalsIgnoreCase(lastCaption) && (now - lastCaptionAt) < COOLDOWN_MS) return;
        lastCaption = normalized;
        lastCaptionAt = now;

        if (containsLocalNegative(normalized)) {
            negativeCount++;
            safeLog(Log.WARN, "🚨 Negative reel #" + negativeCount);
            broadcastCounts(); // Broadcast the updated counts

            if (negativeCount >= 6 && !jarvisActive) {
                jarvisActive = true;
                stopDetection = true;
                safeLog(Log.WARN, "🛑 Jarvis activated after 6th negative reel!");
                queueResponse("Hey buddy, I feel you. That was a little heavy. You're not alone, okay? Are you alright?");
                handler.postDelayed(() -> {
                    waitingForUser = true;
                    startListeningToUser();
                    askingForContinueOrExit = true;
                }, 500);
            }
        } else {
            positiveCount++; // Count positive content
            safeLog(Log.INFO, "✅ Positive/safe content #" + positiveCount);
            broadcastCounts(); // Broadcast the updated counts
        }
    }

    // Add this method to broadcast counts to MainActivity
    private void broadcastCounts() {
        Intent intent = new Intent("COUNT_UPDATE");
        intent.putExtra("positive_count", positiveCount);
        intent.putExtra("negative_count", negativeCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private boolean containsLocalNegative(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : NEGATIVE_KEYWORDS) if (lower.contains(kw)) return true;
        return false;
    }

    private String normalizeCaption(String caption) {
        String s = caption.replaceAll("\\n+", " ").replaceAll("\\s{2,}", " ").trim();
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private String buildJarvisPrompt(String userReply, boolean isContinueExit) {
        if (isContinueExit) {
            return "You are Jarvis, a supportive AI. User reply: \"" + userReply +
                    "\" in context of continue/exit choice. Respond empathetically and guide user appropriately.";
        } else {
            return "You are Jarvis, a supportive AI. User said: \"" + userReply +
                    "\". Respond empathetically in one line.";
        }
    }

    private void callGeminiAndEnqueue(String prompt, boolean isContinueExit) {
        executorService.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject contentBlock = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", prompt);
                parts.put(part);
                contentBlock.put("parts", parts);
                contents.put(contentBlock);
                root.put("contents", contents);

                RequestBody body = RequestBody.create(root.toString(), JSON);
                Request request = new Request.Builder()
                        .url(GEMINI_URL)
                        .addHeader("x-goog-api-key", geminiApiKey)
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";
                String reply = extractTextFromGeminiResponse(respStr);
                if (reply != null && !reply.trim().isEmpty()) {
                    queueResponse(reply.trim());
                }
            } catch (Exception e) { safeLog(Log.ERROR, " Gemini error: " + e.getMessage()); }
        });
    }

    private String extractTextFromGeminiResponse(String respStr) {
        try {
            if (respStr == null || respStr.isEmpty()) return "";
            JSONObject root = new JSONObject(respStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        return parts.getJSONObject(0).optString("text", "");
                    }
                }
            }
        } catch (JSONException e) { safeLog(Log.ERROR, "❌ Parse error: " + e.getMessage()); }
        return "";
    }

    private synchronized void queueResponse(String text) {
        responseQueue.offer(text);
        if (!isSpeaking) playNextResponse();
    }

    private synchronized void playNextResponse() {
        String text = responseQueue.poll();
        if (text == null) {
            isSpeaking = false;
            if (jarvisActive && waitingForUser) startListeningToUser();
            return;
        }
        isSpeaking = true;

        executorService.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("text", text);
                json.put("voice_id", "en-IN-aarav");
                json.put("style", "Conversational");

                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request request = new Request.Builder()
                        .url(MURF_URL)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .addHeader("api-key", murfApiKey)
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful() && !respStr.isEmpty()) {
                    JSONObject jsonResponse = new JSONObject(respStr);
                    String audioUrl = jsonResponse.optString("audioFile");

                    if (!audioUrl.isEmpty()) {
                        File cacheFile = new File(getCacheDir(), "murf_audio_" + System.currentTimeMillis() + ".mp3");
                        okhttp3.Request downloadReq = new okhttp3.Request.Builder().url(audioUrl).build();
                        okhttp3.Response downloadResp = httpClient.newCall(downloadReq).execute();
                        if (downloadResp.isSuccessful() && downloadResp.body() != null) {
                            try (InputStream in = downloadResp.body().byteStream();
                                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            handler.post(() -> playAudio(cacheFile));
                            return;
                        }
                    }
                }
            } catch (Exception e) { safeLog(Log.ERROR,"❌ Murf TTS error: "+e.getMessage()); }
            isSpeaking = false;
            playNextResponse();
        });
    }

    private void playAudio(File file) {
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mediaPlayer = null;
                isSpeaking = false;
                playNextResponse();
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            safeLog(Log.ERROR,"❌ MediaPlayer setup error: "+e.getMessage());
            isSpeaking = false;
            playNextResponse();
        }
    }

    private void onUserSpeechDetected(String userText) {
        if (!waitingForUser || isSpeaking) return;

        waitingForUser = false;
        safeLog(Log.INFO,"🎤 User said: "+userText);

        if (askingForContinueOrExit) {
            String prompt;
            if (userText.toLowerCase().contains("exit")) {
                jarvisActive = false;
                askingForContinueOrExit = false;
                negativeCount = 0;
                stopDetection = true;

                prompt = "User wants to exit from reels. Respond empathetically and suggest taking a break.";
                showGoHomeNotification();

            } else if (userText.toLowerCase().contains("continue")) {
                jarvisActive = false;
                askingForContinueOrExit = false;
                negativeCount = 0;
                stopDetection = false;

                prompt = "User wants to continue watching reels. Respond friendly, but recommend refreshing feed for a better experience.";

            } else {
                waitingForUser = true;
                startListeningToUser();
                queueResponse("Please say 'continue' or 'exit'.");
                return;
            }

            callGeminiAndEnqueue(buildJarvisPrompt(prompt, true), true);
        } else {
            callGeminiAndEnqueue(buildJarvisPrompt(userText, false), false);
        }
    }

    private void showGoHomeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, homeIntent, PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis AI")
                .setContentText("Tap to go Home and take a break from reels.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(new NotificationCompat.Action(0, "Go Home", pendingIntent))
                .build();

        if (nm != null) nm.notify(2, notification);
    }

    private void safeLog(int level, String msg) {
        if (BuildConfig.DEBUG) {
            switch (level) {
                case Log.ERROR: Log.e(TAG, msg); break;
                case Log.WARN:  Log.w(TAG, msg); break;
                case Log.INFO:  Log.i(TAG, msg); break;
                default:        Log.d(TAG, msg); break;
            }
        }
    }
}