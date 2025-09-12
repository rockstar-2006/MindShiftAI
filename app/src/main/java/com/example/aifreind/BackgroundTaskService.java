package com.example.aifreind;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    private static final String ACTION_PROCESS_CAPTION = "PROCESS_CAPTION";
    private static final String EXTRA_CAPTION = "EXTRA_CAPTION";

    private Handler handler;
    private boolean isSpeaking = false;
    private boolean stopDetection = false;

    private boolean jarvisActive = false;
    private boolean waitingForUser = false;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String geminiApiKey = BuildConfig.GEMINI_API_KEY;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;

    private final Queue<String> responseQueue = new ArrayDeque<>();

    private static int negativeCount = 0;

    private static String lastCaption = "";
    private static long lastCaptionAt = 0;
    private static final long COOLDOWN_MS = 8000;

    private static final String[] NEGATIVE_KEYWORDS = new String[]{
            "sad","alone","lonely","cry","crying","hurt","broken","breakup","heartbreak",
            "depressed","hate","angry","pain","tears","lost","dark","suicidal","kill","die",
            "empty","fear","stress","anxiety","overthinking","broken heart","very sad","heavy"
    };

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
        try {
            startForeground(1, getNotification());
        } catch (SecurityException se) {
            safeLog(Log.WARN, "startForeground SecurityException: " + se.getMessage());
        }

        initSpeechRecognizer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopDetection || jarvisActive || isSpeaking) {
            safeLog(Log.INFO, "🛑 Detection paused.");
            return START_STICKY;
        }

        if (intent != null && ACTION_PROCESS_CAPTION.equals(intent.getAction())) {
            String caption = intent.getStringExtra(EXTRA_CAPTION);
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
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
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

    // ----------------------------
    // Speech recognizer
    // ----------------------------
    private SpeechRecognizer speechRecognizer;
    private void initSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) { safeLog(Log.ERROR,"Speech error: "+error); }
                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> texts =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts != null && !texts.isEmpty()) {
                        onUserSpeechDetected(texts.get(0));
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            safeLog(Log.ERROR, "SpeechRecognizer init failed: " + e.getMessage());
        }
    }

    private void startListeningToUser() {
        if (speechRecognizer != null) {
            handler.post(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
                    speechRecognizer.startListening(intent);
                    safeLog(Log.INFO, "🎤 Jarvis is now listening to the user...");
                } catch (Exception e) {
                    safeLog(Log.ERROR,"❌ startListening failed: "+e.getMessage());
                }
            });
        }
    }

    // ----------------------------
    // Entry
    // ----------------------------
    public static void enqueueCaption(Context context, String caption) {
        Intent intent = new Intent(context, BackgroundTaskService.class);
        intent.setAction(ACTION_PROCESS_CAPTION);
        intent.putExtra(EXTRA_CAPTION, caption);
        context.startService(intent);
    }

    // ----------------------------
    // Main caption handler
    // ----------------------------
    private void handleCaption(String caption) {
        String normalized = normalizeCaption(caption);
        long now = System.currentTimeMillis();

        if (normalized.equalsIgnoreCase(lastCaption) && (now - lastCaptionAt) < COOLDOWN_MS) {
            safeLog(Log.DEBUG, "⏭ Skipped duplicate caption");
            return;
        }
        lastCaption = normalized;
        lastCaptionAt = now;

        boolean localHit = containsLocalNegative(normalized);
        if (localHit) {
            negativeCount++;
            safeLog(Log.WARN, "🚨 Negative reel #" + negativeCount);

            if (negativeCount >= 6 && !jarvisActive) {
                jarvisActive = true;
                stopDetection = true;
                safeLog(Log.WARN, "🛑 Jarvis activated after 6th negative reel!");

                // 👇 Comfort + check-in
                queueResponse("Hey buddy, I feel you. That was a little heavy. You're not alone, okay? " +
                        "You've been watching a lot of sad reels. Are you okay?");

                // Start listening after comfort
                handler.postDelayed(this::startListeningToUser, 3000);

                notifyFeedRefresh();
            }
        } else {
            safeLog(Log.DEBUG, "✅ Safe caption");
        }
    }

    private void notifyFeedRefresh() {
        Intent refreshIntent = new Intent("REFRESH_FEED");
        sendBroadcast(refreshIntent);
    }

    private boolean containsLocalNegative(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : NEGATIVE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private String normalizeCaption(String caption) {
        String s = caption;
        s = s.replace("💔", " broken heart ")
                .replace("😢", " crying ")
                .replace("😭", " very sad ")
                .replace("😡", " angry ")
                .replace("😔", " sad ")
                .replace("❤️", " love ")
                .replace("💕", " love ")
                .replace("💖", " love ")
                .replace("😞", " disappointed ")
                .replace("😂", " laughing ")
                .replace("🤣", " laughing hard ")
                .replaceAll("\\n+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (s.length() > 2000) s = s.substring(0, 2000);
        return s;
    }

    // ----------------------------
    // Jarvis persona
    // ----------------------------
    private String buildJarvisPrompt(String userReply) {
        return "You are Jarvis, a supportive AI friend. Reply casually, like a close friend. " +
                "Be empathetic, short, and natural. Add light humor only if it feels supportive. " +
                "The user just said: \"" + userReply + "\"";
    }

    private void callGeminiAndEnqueue(String prompt) {
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
                    safeLog(Log.INFO, "💬 Jarvis: " + reply.trim());
                    queueResponse(reply.trim());
                }
            } catch (Exception e) {
                safeLog(Log.ERROR, "❌ Gemini error: " + e.getMessage());
            }
        });
    }

    private String extractTextFromGeminiResponse(String respStr) {
        try {
            if (respStr == null || respStr.isEmpty()) return "";
            JSONObject root = new JSONObject(respStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        return parts.getJSONObject(0).optString("text", "").trim();
                    }
                }
            }
        } catch (JSONException e) {
            safeLog(Log.ERROR, "❌ Parse error: " + e.getMessage());
        }
        return "";
    }

    // ----------------------------
    // TTS Queue (Murf)
    // ----------------------------
    private synchronized void queueResponse(String text) {
        responseQueue.offer(text);
        if (!isSpeaking) {
            playNextResponse();
        }
    }

    private synchronized void playNextResponse() {
        String text = responseQueue.poll();
        if (text == null) {
            isSpeaking = false;
            if (jarvisActive) {
                waitingForUser = true;
                startListeningToUser();
            }
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
                        File cacheFile = new File(getCacheDir(), "murf_audio.mp3");
                        okhttp3.Request downloadReq = new okhttp3.Request.Builder()
                                .url(audioUrl)
                                .build();
                        okhttp3.Response downloadResp = httpClient.newCall(downloadReq).execute();
                        if (downloadResp.isSuccessful() && downloadResp.body() != null) {
                            try (InputStream in = downloadResp.body().byteStream();
                                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                }
                            }
                            handler.post(() -> {
                                try {
                                    MediaPlayer mediaPlayer = new MediaPlayer();
                                    mediaPlayer.setDataSource(cacheFile.getAbsolutePath());
                                    mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                                    mediaPlayer.setOnCompletionListener(mp -> {
                                        mp.release();
                                        isSpeaking = false;
                                        playNextResponse();
                                    });
                                    mediaPlayer.prepareAsync();
                                } catch (Exception e) {
                                    safeLog(Log.ERROR,"❌ Local MediaPlayer error: "+e.getMessage());
                                    isSpeaking = false;
                                    playNextResponse();
                                }
                            });
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                safeLog(Log.ERROR,"❌ Murf TTS error: "+e.getMessage());
            }
            isSpeaking = false;
            playNextResponse();
        });
    }

    // ----------------------------
    // Handle user speech reply
    // ----------------------------
    private void onUserSpeechDetected(String userText) {
        if (waitingForUser && !isSpeaking) {
            waitingForUser = false;
            safeLog(Log.INFO,"🎤 User said: "+userText);

            // Build empathetic Gemini prompt
            String prompt = buildJarvisPrompt(userText);
            callGeminiAndEnqueue(prompt);
        }
    }

    // ----------------------------
    // Privacy-safe logging
    // ----------------------------
    private void safeLog(int level, String msg) {
        if (BuildConfig.DEBUG) {  // only log in debug builds
            switch (level) {
                case Log.ERROR: Log.e(TAG, msg); break;
                case Log.WARN:  Log.w(TAG, msg); break;
                case Log.INFO:  Log.i(TAG, msg); break;
                default:        Log.d(TAG, msg); break;
            }
        }
    }
}
