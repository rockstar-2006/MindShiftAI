package com.example.aifreind;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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

    // Use a faster model
    private static final String MODEL = "models/gemini-2.0-flash-exp";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/" + MODEL + ":generateContent";

    private static final String MURF_URL = "https://api.murf.ai/v1/speech/generate";

    private Handler handler;
    private boolean isSpeaking = false;
    private boolean stopDetection = false;

    private boolean jarvisActive = false;
    private boolean waitingForUser = false;
    private boolean userInterrupted = false;

    // Faster HTTP client with shorter timeouts
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private final String geminiApiKey = BuildConfig.GEMINI_API_KEY;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;

    private final Queue<String> responseQueue = new ArrayDeque<>();
    private MediaPlayer mediaPlayer;

    // Android TTS as fallback
    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;

    private static int negativeCount = 0;
    private static int positiveCount = 0;
    private static int totalReelsCount = 0;

    private static String lastCaption = "";
    private static long lastCaptionAt = 0;
    private static final long COOLDOWN_MS = 5000; // Reduced from 8000 to 5000

    // Dynamic language tracking
    private String currentConversationLanguage = "en";

    // Enhanced negative keywords with weights
    private static final HashMap<String, Integer> NEGATIVE_KEYWORDS = new HashMap<String, Integer>() {{
        // High weight keywords (3 points)
        put("suicidal", 3); put("kill myself", 3); put("want to die", 3); put("end my life", 3);
        put("depressed", 3); put("hopeless", 3); put("worthless", 3);

        // Medium weight keywords (2 points)
        put("heartbreak", 2); put("broken heart", 2); put("breakup", 2); put("cheated", 2);
        put("crying", 2); put("tears", 2); put("hurt", 2); put("pain", 2); put("sad", 2);
        put("lonely", 2); put("alone", 2); put("empty", 2); put("dark", 2);
        put("hate", 2); put("angry", 2); put("stress", 2); put("anxiety", 2);

        // Low weight keywords (1 point)
        put("miss", 1); put("lost", 1); put("confused", 1); put("tired", 1);
        put("overthinking", 1); put("worried", 1); put("nervous", 1);
    }};

    private SpeechRecognizer speechRecognizer;

    private int conversationRound = 0;
    private static final int MAX_CONVERSATION_ROUNDS = 6; // Reduced from 8 to 6
    private String currentContext = "";
    private String conversationHistory = "";

    // Negative percentage tracking
    private static final double NEGATIVE_THRESHOLD = 0.70; // 70% threshold
    private static final int MIN_REELS_FOR_ACCURACY = 5; // Minimum reels before calculating percentage

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        if (!hasAudioPermission()) {
            safeLog(Log.ERROR, " Microphone permission not granted!");
            stopSelf();
            return;
        }

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= 34) {
            startForegroundServiceWithMicrophone();
        } else {
            startForeground(1, getNotification());
        }

        initSpeechRecognizer();
        initTextToSpeech();
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true;
                int result1 = textToSpeech.setLanguage(Locale.US);
                int result2 = textToSpeech.setLanguage(new Locale("hi", "IN"));

                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        safeLog(Log.INFO, " TTS started speaking");
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        handler.post(() -> {
                            safeLog(Log.INFO, " TTS finished speaking");
                            isSpeaking = false;
                            playNextResponse();
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        handler.post(() -> {
                            safeLog(Log.ERROR, " TTS error");
                            isSpeaking = false;
                            playNextResponse();
                        });
                    }
                });
                safeLog(Log.INFO, "TTS initialized successfully");
            } else {
                safeLog(Log.ERROR, " TTS initialization failed");
            }
        });
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void startForegroundServiceWithMicrophone() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        1,
                        getNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                );
            }
        } catch (SecurityException e) {
            safeLog(Log.ERROR, " Foreground service microphone permission missing: " + e.getMessage());
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "RESET_COUNTS".equals(intent.getAction())) {
            negativeCount = 0;
            positiveCount = 0;
            totalReelsCount = 0;
            broadcastCounts();
            return START_STICKY;
        }

        if (stopDetection || jarvisActive || isSpeaking) {
            safeLog(Log.INFO, " Detection paused.");
            return START_STICKY;
        }

        if (intent != null && "PROCESS_CAPTION".equals(intent.getAction())) {
            String caption = intent.getStringExtra("EXTRA_CAPTION");
            if (caption != null && !caption.trim().isEmpty()) {
                handleCaption(caption.trim());
            }
        }

        if (intent != null && "EXIT_APP".equals(intent.getAction())) {
            safeLog(Log.INFO, " Received exit command via broadcast");
            closeInstagram();
            endConversation();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdownNow();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification getNotification() {
        double negativePercentage = calculateNegativePercentage();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis AI")
                .setContentText(String.format("Negative reels: %.1f%%", negativePercentage))
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

                @Override
                public void onBeginningOfSpeech() {
                    if (isSpeaking) {
                        safeLog(Log.INFO, " User started speaking - stopping TTS immediately");
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.stop();
                        }
                        if (ttsInitialized) {
                            textToSpeech.stop();
                        }
                        isSpeaking = false;
                        userInterrupted = true;
                    }
                }

                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    safeLog(Log.ERROR,"Speech error: "+error);
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        initSpeechRecognizer();
                    }
                    if (waitingForUser && jarvisActive) {
                        handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 500); // Reduced delay
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (texts != null && !texts.isEmpty()) {
                        String userSpeech = texts.get(0);
                        safeLog(Log.INFO, "🎤 User speech detected: " + userSpeech);
                        onUserSpeechDetected(userSpeech);
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
        if (speechRecognizer != null && waitingForUser && !isSpeaking && jarvisActive) {
            handler.post(() -> {
                try {
                    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US,hi-IN");
                    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
                    intent.putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, new String[]{"en-US", "hi-IN"});
                    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
                    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

                    speechRecognizer.startListening(intent);
                    safeLog(Log.INFO, "🎤 Jarvis is now listening in both English and Hindi...");
                } catch (Exception e) {
                    safeLog(Log.ERROR," startListening failed: "+e.getMessage());
                    initSpeechRecognizer();
                    if (waitingForUser && jarvisActive) {
                        handler.postDelayed(this::startListeningToUser, 500); // Reduced delay
                    }
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

    public static void triggerExit(Context context) {
        Intent intent = new Intent(context, BackgroundTaskService.class);
        intent.setAction("EXIT_APP");
        context.startService(intent);
    }

    private void handleCaption(String caption) {
        String normalized = normalizeCaption(caption);
        long now = System.currentTimeMillis();

        if (normalized.equalsIgnoreCase(lastCaption) && (now - lastCaptionAt) < COOLDOWN_MS) return;
        lastCaption = normalized;
        lastCaptionAt = now;

        totalReelsCount++;

        // Calculate negative score (0-100)
        int negativeScore = calculateNegativeScore(normalized);
        boolean isNegative = negativeScore >= 40; // 40% threshold for individual reel

        // Detect language
        currentConversationLanguage = detectLanguage(normalized);

        if (isNegative) {
            negativeCount++;
            double currentPercentage = calculateNegativePercentage();
            safeLog(Log.WARN, String.format("📊 Negative reel #%d | Score: %d%% | Overall: %.1f%%",
                    negativeCount, negativeScore, currentPercentage));
            broadcastCounts();

            // Check if we should trigger Jarvis based on percentage
            if (shouldTriggerJarvis() && !jarvisActive && !isSpeaking) {
                jarvisActive = true;
                stopDetection = true;
                conversationRound = 0;
                currentContext = "user is watching negative content about relationships and heartbreak";
                conversationHistory = "";

                boolean isHindi = currentConversationLanguage.equals("hi");
                double percentage = calculateNegativePercentage();

                safeLog(Log.INFO, String.format("🤖 Triggering Jarvis - %.1f%% negative content detected", percentage));
                safeLog(Log.INFO, "🤖 Starting in " + (isHindi ? "Hindi" : "English") + " mode");

                // Faster, more direct prompt
                String prompt = buildQuickJarvisPrompt("", isHindi, percentage);
                callGeminiWithRetry(prompt, isHindi, 512, 0); // Reduced token count for faster response
            }
        } else {
            positiveCount++;
            safeLog(Log.INFO, "✅ Positive/safe content #" + positiveCount);
            broadcastCounts();
        }
    }

    private int calculateNegativeScore(String text) {
        if (text == null || text.isEmpty()) return 0;

        String lower = text.toLowerCase();
        int totalScore = 0;
        int keywordCount = 0;

        for (String keyword : NEGATIVE_KEYWORDS.keySet()) {
            if (lower.contains(keyword)) {
                totalScore += NEGATIVE_KEYWORDS.get(keyword);
                keywordCount++;
            }
        }

        // Calculate percentage score (0-100)
        if (keywordCount == 0) return 0;

        int maxPossibleScore = keywordCount * 3; // Assuming 3 is max weight
        return (totalScore * 100) / maxPossibleScore;
    }

    private double calculateNegativePercentage() {
        if (totalReelsCount == 0) return 0.0;
        return (negativeCount * 100.0) / totalReelsCount;
    }

    private boolean shouldTriggerJarvis() {
        if (totalReelsCount < MIN_REELS_FOR_ACCURACY) {
            return negativeCount >= 3; // Trigger after 3 negative reels if not enough data
        }

        double percentage = calculateNegativePercentage();
        return percentage >= (NEGATIVE_THRESHOLD * 100);
    }

    private void broadcastCounts() {
        Intent intent = new Intent("COUNT_UPDATE");
        intent.putExtra("positive_count", positiveCount);
        intent.putExtra("negative_count", negativeCount);
        intent.putExtra("total_count", totalReelsCount);
        intent.putExtra("negative_percentage", calculateNegativePercentage());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private String normalizeCaption(String caption) {
        String s = caption.replaceAll("\\n+", " ").replaceAll("\\s{2,}", " ").trim();
        return s.length() > 1500 ? s.substring(0, 1500) : s; // Reduced from 2000 to 1500
    }

    private String detectLanguage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return currentConversationLanguage.isEmpty() ? "en" : currentConversationLanguage;
        }

        if (text.matches(".*[\\u0900-\\u097F].*")) {
            return "hi";
        }

        return "en";
    }

    private String detectLanguageFromSpeech(String userSpeech) {
        if (userSpeech == null || userSpeech.trim().isEmpty()) {
            return currentConversationLanguage;
        }

        String lower = userSpeech.toLowerCase().trim();

        if (userSpeech.matches(".*[\\u0900-\\u097F].*")) {
            safeLog(Log.INFO, "🗣️ Detected: Hindi (Devanagari script)");
            return "hi";
        }

        String[] hindiWords = {
                "kya", "hai", "hoon", "haan", "nahi", "main", "mujhe", "mere",
                "tum", "tumhe", "tumhara", "aap", "aapka", "kaise", "kaisa",
                "kyun", "kahan", "kab", "theek", "thik", "accha", "acha"
        };

        int hindiWordCount = 0;
        String[] words = lower.split("\\s+");

        for (String word : words) {
            for (String hindiWord : hindiWords) {
                if (word.equals(hindiWord) || word.startsWith(hindiWord)) {
                    hindiWordCount++;
                    break;
                }
            }
        }

        if (words.length > 0 && (hindiWordCount * 100.0 / words.length) >= 30) {
            safeLog(Log.INFO, "🗣️ Detected: Hindi (Roman script) - " + hindiWordCount + "/" + words.length + " Hindi words");
            return "hi";
        }

        safeLog(Log.INFO, "🗣️ Detected: English");
        return "en";
    }

    // Faster, more concise prompt for quick responses
    private String buildQuickJarvisPrompt(String userText, boolean isHindi, double negativePercentage) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are Jarvis, a warm AI friend. Be concise (1-2 sentences). ");
        sb.append("Speak naturally like a real friend.\n\n");

        sb.append(String.format("Context: User watched %.1f%% negative content. ", negativePercentage));
        sb.append("Reach out gently.\n");

        if (conversationRound == 0) {
            sb.append("FIRST message: Gently ask if they're okay. Be brief and caring.");
        } else {
            sb.append("Respond directly to user. Keep it short and natural.");
        }

        if (isHindi) {
            sb.append("\n**LANGUAGE: HINDI** - Use brief Roman Hindi. 1-2 short sentences max.");
        } else {
            sb.append("\n**LANGUAGE: ENGLISH** - Use brief conversational English. 1-2 short sentences max.");
        }

        if (!userText.isEmpty()) {
            sb.append("\nUser: \"").append(userText).append("\"");
        }

        sb.append("\n\nIMPORTANT: Respond in EXACTLY 1-2 short sentences. No long responses.");

        return sb.toString();
    }

    private void callGeminiWithRetry(String prompt, boolean isHindi, int maxOutputTokens, int retryCount) {
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

                JSONObject generationConfig = new JSONObject();
                generationConfig.put("temperature", 0.8); // Slightly higher for more varied but faster responses
                generationConfig.put("topK", 20); // Reduced for faster generation
                generationConfig.put("topP", 0.9);
                generationConfig.put("maxOutputTokens", maxOutputTokens);
                root.put("generationConfig", generationConfig);

                RequestBody body = RequestBody.create(root.toString(), JSON);
                String url = GEMINI_URL + "?key=" + geminiApiKey;

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                safeLog(Log.INFO, "🚀 Calling Gemini API (Fast Mode)...");

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";

                if (response.isSuccessful()) {
                    String reply = extractTextFromGeminiResponse(respStr);
                    String finishReason = extractFinishReason(respStr);

                    if ((reply == null || reply.trim().isEmpty()) && "MAX_TOKENS".equalsIgnoreCase(finishReason) && retryCount < 1) {
                        safeLog(Log.WARN, "⚠️ Retrying with more tokens...");
                        callGeminiWithRetry(prompt, isHindi, 768, retryCount + 1);
                        return;
                    }

                    if (reply != null && !reply.trim().isEmpty()) {
                        // Trim response to be shorter
                        String trimmedReply = trimResponse(reply.trim());
                        conversationHistory += "Jarvis: " + trimmedReply + "\n";
                        safeLog(Log.INFO, "✅ Gemini Success: " + trimmedReply);
                        queueResponse(trimmedReply, isHindi);
                        return;
                    }
                }

                // Faster fallback - use pre-defined responses if API fails
                if (retryCount < 1) {
                    safeLog(Log.WARN, "🔄 Quick retry...");
                    handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), 1000);
                } else {
                    // Use fallback response
                    String fallbackResponse = getFallbackResponse(isHindi, conversationRound);
                    safeLog(Log.INFO, "🔄 Using fallback response");
                    queueResponse(fallbackResponse, isHindi);
                }

            } catch (Exception e) {
                safeLog(Log.ERROR, " Gemini error: " + e.getMessage());
                if (retryCount < 1) {
                    handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), 1500);
                } else {
                    // Use fallback response
                    String fallbackResponse = getFallbackResponse(isHindi, conversationRound);
                    queueResponse(fallbackResponse, isHindi);
                }
            }
        });
    }

    private String trimResponse(String response) {
        // Ensure response is short (2 sentences max)
        String[] sentences = response.split("[.!?]+");
        if (sentences.length > 2) {
            return sentences[0].trim() + ". " + sentences[1].trim() + ".";
        }
        return response;
    }

    private String getFallbackResponse(boolean isHindi, int round) {
        if (isHindi) {
            switch (round) {
                case 0: return "Kya sab theek hai? Main yahan hoon sunne ke liye.";
                case 1: return "Sach batao, kya chal raha hai?";
                default: return "Main samajh sakta hoon. Aap bas baat karte raho.";
            }
        } else {
            switch (round) {
                case 0: return "Hey, everything okay? I'm here to listen.";
                case 1: return "What's going on? You can tell me.";
                default: return "I understand. Just keep talking.";
            }
        }
    }

    private String extractFinishReason(String respStr) {
        try {
            if (respStr == null || respStr.isEmpty()) return "";
            JSONObject root = new JSONObject(respStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                return candidate.optString("finishReason", "");
            }
        } catch (JSONException e) {
            safeLog(Log.ERROR, " Parse error while extracting finishReason: " + e.getMessage());
        }
        return "";
    }

    private String extractTextFromGeminiResponse(String respStr) {
        try {
            if (respStr == null || respStr.isEmpty()) return "";
            JSONObject root = new JSONObject(respStr);

            if (root.has("error")) {
                safeLog(Log.ERROR, " Gemini API error: " + root.getJSONObject("error").toString());
                return "";
            }

            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                Object contentObj = candidate.opt("content");

                if (contentObj instanceof JSONObject) {
                    JSONObject content = (JSONObject) contentObj;
                    String text = content.optString("text", "");
                    if (!text.isEmpty()) return text.trim();

                    JSONArray partsArr = content.optJSONArray("parts");
                    if (partsArr != null && partsArr.length() > 0) {
                        String t = partsArr.getJSONObject(0).optString("text", "");
                        if (!t.isEmpty()) return t.trim();
                    }
                }
            }
        } catch (JSONException e) {
            safeLog(Log.ERROR, "❌ Parse error: " + e.getMessage());
        }
        return "";
    }

    private synchronized void queueResponse(String text, boolean isHindi) {
        responseQueue.offer((isHindi ? "hi" : "en") + "::" + text);
        safeLog(Log.INFO, "📥 Queued response: " + (isHindi ? "Hindi" : "English"));
        if (!isSpeaking) {
            playNextResponse();
        }
    }

    private synchronized void playNextResponse() {
        if (isSpeaking) return;

        String text = responseQueue.poll();
        if (text == null) {
            isSpeaking = false;
            if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
                waitingForUser = true;
                handler.postDelayed(this::startListeningToUser, 300); // Reduced delay
            }
            return;
        }
        isSpeaking = true;

        executorService.execute(() -> {
            try {
                String[] parts = text.split("::", 2);
                boolean isHindi = parts[0].equals("hi");
                String actualText = parts[1];

                useMurfTTSWithFallback(actualText, isHindi);

            } catch (Exception e) {
                safeLog(Log.ERROR, "❌ TTS error: " + e.getMessage());
                isSpeaking = false;
                playNextResponse();
            }
        });
    }

    private void useMurfTTSWithFallback(String text, boolean isHindi) {
        useMurfTTS(text, isHindi, new TTSFallbackCallback() {
            @Override
            public void onMurfSuccess() {}

            @Override
            public void onMurfFailed() {
                safeLog(Log.WARN, "🔄 Murf TTS failed, falling back to Android TTS");
                useAndroidTTS(text, isHindi);
            }
        });
    }

    private void useMurfTTS(String text, boolean isHindi, TTSFallbackCallback callback) {
        try {
            String voiceId = isHindi ? "hi-IN-kabir" : "en-IN-aarav";

            JSONObject json = new JSONObject();
            json.put("text", text);
            json.put("voice_id", voiceId);
            json.put("style", "Conversational");
            json.put("speed", 1.1); // Slightly faster speed
            json.put("pitch", 1.0);
            json.put("sample_rate", 24000);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(MURF_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader("api-key", murfApiKey)
                    .post(body)
                    .build();

            safeLog(Log.INFO, "🔊 Calling Murf TTS...");

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
                        safeLog(Log.INFO, "🔊 Audio file downloaded, playing...");
                        handler.post(() -> playAudio(cacheFile));
                        callback.onMurfSuccess();
                        return;
                    }
                }
            }
        } catch (Exception e) {
            safeLog(Log.ERROR, "❌ Murf TTS error: " + e.getMessage());
        }
        callback.onMurfFailed();
    }

    private interface TTSFallbackCallback {
        void onMurfSuccess();
        void onMurfFailed();
    }

    private void useAndroidTTS(String text, boolean isHindi) {
        if (!ttsInitialized) {
            safeLog(Log.ERROR, "❌ Android TTS not initialized");
            isSpeaking = false;
            playNextResponse();
            return;
        }

        handler.post(() -> {
            try {
                Locale locale = isHindi ? new Locale("hi", "IN") : Locale.US;
                int result = textToSpeech.setLanguage(locale);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    safeLog(Log.ERROR, "❌ TTS language not supported: " + locale);
                    isSpeaking = false;
                    playNextResponse();
                    return;
                }

                HashMap<String, String> params = new HashMap<>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis-tts");

                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params);
                safeLog(Log.INFO, "🔊 Using Android TTS for response");

            } catch (Exception e) {
                safeLog(Log.ERROR, "❌ Android TTS error: " + e.getMessage());
                isSpeaking = false;
                playNextResponse();
            }
        });
    }

    private void playAudio(File file) {
        try {
            if (mediaPlayer != null) {
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                safeLog(Log.INFO, "🔊 Playing AI response...");
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                safeLog(Log.INFO, "🔊 AI finished speaking");
                try { mp.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
                isSpeaking = false;

                if (userInterrupted) {
                    safeLog(Log.INFO, "🔄 User interrupted - processing their speech now");
                    userInterrupted = false;
                } else {
                    handler.post(() -> {
                        if (!responseQueue.isEmpty()) {
                            playNextResponse();
                        } else if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
                            waitingForUser = true;
                            handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 300);
                        }
                    });
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                safeLog(Log.ERROR, "❌ MediaPlayer error: " + what + ", " + extra);
                try { mp.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
                isSpeaking = false;
                playNextResponse();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            safeLog(Log.ERROR,"❌ MediaPlayer setup error: "+e.getMessage());
            isSpeaking = false;
            playNextResponse();
        }
    }

    private void onUserSpeechDetected(String userText) {
        if (!jarvisActive) return;

        safeLog(Log.INFO,"🎤 User said: " + userText);

        String detectedLanguage = detectLanguageFromSpeech(userText);
        currentConversationLanguage = detectedLanguage;
        boolean isHindi = detectedLanguage.equals("hi");

        safeLog(Log.INFO, "🌐 Language dynamically set to: " + (isHindi ? "Hindi" : "English"));

        conversationHistory += "User: " + userText + "\n";

        if (userInterrupted) {
            conversationRound++;
            userInterrupted = false;
        }

        safeLog(Log.INFO, "🤖 Conversation Round: " + conversationRound + "/" + MAX_CONVERSATION_ROUNDS);

        // Check exit commands
        if (userText.toLowerCase().contains("exit") || userText.toLowerCase().contains("close") ||
                userText.toLowerCase().contains("stop") || userText.toLowerCase().contains("bye") ||
                userText.toLowerCase().contains("band") || userText.toLowerCase().contains("ruk") ||
                userText.toLowerCase().contains("bas") || userText.toLowerCase().contains("enough")) {

            safeLog(Log.INFO, "🚪 User requested to exit - closing Instagram");

            String farewellMessage = isHindi ?
                    "Theek hai! Instagram band karta hoon. Take care!" :
                    "Okay! Closing Instagram. Take care!";

            queueResponse(farewellMessage, isHindi);

            handler.postDelayed(() -> {
                closeInstagram();
                endConversation();
            }, 2000); // Reduced delay
            return;
        }

        // Continue conversation with faster prompt
        if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
            double percentage = calculateNegativePercentage();
            String prompt = buildQuickJarvisPrompt(userText, isHindi, percentage);
            callGeminiWithRetry(prompt, isHindi, 512, 0);
        } else {
            endConversationWithMessage(isHindi);
        }
    }

    private void closeInstagram() {
        try {
            safeLog(Log.INFO, "🚪 Starting Instagram close process...");

            Intent exitIntent = new Intent("CLOSE_INSTAGRAM");
            LocalBroadcastManager.getInstance(this).sendBroadcast(exitIntent);
            safeLog(Log.INFO, "📡 Sent CLOSE_INSTAGRAM broadcast to MainActivity");

            handler.postDelayed(() -> {
                try {
                    Intent fallbackIntent = new Intent("CLOSE_INSTAGRAM_FALLBACK");
                    LocalBroadcastManager.getInstance(BackgroundTaskService.this).sendBroadcast(fallbackIntent);
                    safeLog(Log.INFO, "🔄 Sent fallback broadcast");
                } catch (Exception e) {
                    safeLog(Log.ERROR, "❌ Fallback broadcast failed: " + e.getMessage());
                }
            }, 800); // Reduced delay

            handler.postDelayed(() -> {
                try {
                    if (jarvisActive) {
                        safeLog(Log.WARN, "🔄 Emergency: Forcing service stop");
                        stopDetection = true;
                        jarvisActive = false;
                        negativeCount = 0;
                        conversationRound = 0;
                    }
                } catch (Exception e) {
                    safeLog(Log.ERROR, "❌ Emergency stop failed: " + e.getMessage());
                }
            }, 2000); // Reduced delay

            safeLog(Log.INFO, "✅ Instagram close process initiated");

        } catch (Exception e) {
            safeLog(Log.ERROR, "❌ Error in closeInstagram: " + e.getMessage());
        }
    }

    private void endConversation() {
        jarvisActive = false;
        stopDetection = false;
        negativeCount = 0;
        conversationRound = 0;
        conversationHistory = "";
        waitingForUser = false;

        handler.postDelayed(() -> {
            safeLog(Log.INFO, "🔄 Returning to normal content detection");
        }, 1500); // Reduced delay
    }

    private void endConversationWithMessage(boolean isHindi) {
        jarvisActive = false;
        stopDetection = false;
        negativeCount = 0;

        String closingMessage = isHindi ?
                "Main yahan hoon agar baat karni ho. Khayal rakhna!" :
                "I'm here if you need to talk. Take care!";

        queueResponse(closingMessage, isHindi);

        handler.postDelayed(() -> {
            jarvisActive = false;
            stopDetection = false;
            conversationRound = 0;
            conversationHistory = "";
            safeLog(Log.INFO, "🔄 Returning to normal content detection");
        }, 3000); // Reduced delay
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