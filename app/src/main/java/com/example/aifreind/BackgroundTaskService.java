package com.example.aifreind;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final String MURF_URL = "https://api.murf.ai/v1/speech/generate";

    private SpeechRecognizer speechRecognizer;
    private Handler handler;
    private boolean isSpeaking = false;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String geminiApiKey = BuildConfig.GEMINI_API_KEY;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;

    private final Queue<String> conversationHistory = new LinkedList<>();
    private final Queue<String> responseQueue = new LinkedList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission not granted! Please grant it in MainActivity.");
            stopSelf();
            return;
        }

        createNotificationChannel();
        startForeground(1, getNotification());

        initSpeechRecognizer();
        startListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        executorService.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis AI")
                .setContentText("Listening for you...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis AI Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { Log.d(TAG, "Ready for speech"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { if (!isSpeaking) restartListening(); }
            @Override public void onError(int error) { handler.postDelayed(() -> { if (!isSpeaking) restartListening(); }, 1000); }
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String userText = matches.get(0);
                    Log.d(TAG, "Recognized text: " + userText);

                    String sentiment = detectSentiment(userText);
                    Log.d(TAG, "Detected sentiment: " + sentiment);

                    updateConversationHistory("User: " + userText);

                    String prompt = buildJarvisPrompt(userText, sentiment);

                    callGemini(prompt);
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        if (speechRecognizer == null) return;
        handler.post(() -> {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            speechRecognizer.startListening(intent);
        });
    }

    private void restartListening() {
        handler.postDelayed(this::startListening, 400);
    }

    private String detectSentiment(String text) {
        if (text.toLowerCase().contains("sad") || text.toLowerCase().contains("stressed"))
            return "negative";
        else if (text.toLowerCase().contains("happy") || text.toLowerCase().contains("great"))
            return "positive";
        return "neutral";
    }

    private void updateConversationHistory(String message) {
        if (conversationHistory.size() >= 5) conversationHistory.poll();
        conversationHistory.offer(message);
    }

    private String buildJarvisPrompt(String userText, String sentiment) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Jarvis, a friendly AI friend. ")
                .append("Speak casually, like a human friend. Use contractions and natural phrasing. ")
                .append("Add tiny, subtle jokes or relatable comments, but avoid cringe or over-the-top humor. ")
                .append("Always empathize first, then explain. Do not ask the user to share more. ")
                .append("Keep responses short, natural, and human-like.\n\n");

        // Add conversation history
        for (String msg : conversationHistory) {
            sb.append(msg).append("\n");
        }

        // Add current user input
        sb.append("User: ").append(userText).append("\n");
        sb.append("AI:");
        return sb.toString();
    }


    private void callGemini(String prompt) {
        if (geminiApiKey == null || geminiApiKey.isEmpty()) {
            Log.e(TAG, "Gemini API key missing!");
            return;
        }

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
                String respStr = response.body() != null ? response.body().string() : null;
                Log.d(TAG, "Gemini raw response: " + respStr);

                String reply = respStr != null ? extractTextFromGeminiResponse(respStr) : "";

                if (!reply.isEmpty()) {
                    queueResponse(reply);
                    updateConversationHistory("AI: " + reply);
                }

            } catch (JSONException | IOException e) {
                Log.e(TAG, "Gemini request error: " + e.getMessage());
            }
        });
    }

    private String extractTextFromGeminiResponse(String respStr) {
        try {
            JSONObject root = new JSONObject(respStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        JSONObject part = parts.getJSONObject(0);
                        String text = part.optString("text", null);
                        if (text != null && !text.isEmpty()) return text.trim();
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Parse Gemini JSON: " + e.getMessage());
        }
        return "";
    }

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
            startListening();
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
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject jsonResponse = new JSONObject(response.body().string());
                    String audioUrl = jsonResponse.optString("audioFile");
                    if (!audioUrl.isEmpty()) {
                        handler.post(() -> {
                            try {
                                MediaPlayer mediaPlayer = new MediaPlayer();
                                mediaPlayer.setDataSource(audioUrl);
                                mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                                mediaPlayer.setOnCompletionListener(mp -> {
                                    mp.release();
                                    playNextResponse();
                                });
                                mediaPlayer.prepareAsync();
                            } catch (Exception e) {
                                Log.e(TAG, "MediaPlayer error: " + e.getMessage());
                                playNextResponse();
                            }
                        });
                    } else {
                        Log.e(TAG, "Murf TTS audio URL missing!");
                        playNextResponse();
                    }
                } else {
                    Log.e(TAG, "Murf TTS request failed: " + response.code());
                    playNextResponse();
                }

            } catch (Exception e) {
                Log.e(TAG, "Murf TTS error: " + e.getMessage());
                playNextResponse();
            }
        });
    }

}
