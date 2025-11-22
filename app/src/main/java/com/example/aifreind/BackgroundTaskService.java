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

    private static final String MODEL = "models/gemini-2.5-flash";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/" + MODEL + ":generateContent";

    private static final String MURF_URL = "https://api.murf.ai/v1/speech/generate";

    private Handler handler;
    private boolean isSpeaking = false;
    private boolean stopDetection = false;

    private boolean jarvisActive = false;
    private boolean waitingForUser = false;
    private boolean userInterrupted = false;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final String geminiApiKey = BuildConfig.GEMINI_API_KEY;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;

    private final Queue<String> responseQueue = new ArrayDeque<>();
    private MediaPlayer mediaPlayer;

    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;

    private static int negativeCount = 0;
    private static int positiveCount = 0;

    private static String lastCaption = "";
    private static long lastCaptionAt = 0;
    private static final long COOLDOWN_MS = 8000;

    private String currentConversationLanguage = "en";

    private static final String[] NEGATIVE_KEYWORDS = new String[]{
            "sad","alone","lonely","cry","crying","hurt","broken","breakup","heartbreak",
            "depressed","hate","angry","pain","tears","lost","dark","suicidal","kill","die",
            "empty","fear","stress","anxiety","overthinking","broken heart","very sad","heavy"
    };

    // NEW: high-level themes for better context
    private static final String[] NEGATIVE_RELATIONSHIP_KEYWORDS = new String[]{
            "breakup", "broken", "cheated", "cheating", "ex", "heartbreak", "left me",
            "toxic", "abuse", "ignored", "rejected"
    };

    private static final String[] NEGATIVE_SELF_WORTH_KEYWORDS = new String[]{
            "worthless", "failure", "loser", "not enough", "ugly", "hate myself",
            "nobody cares", "alone forever"
    };

    private static final String[] NEGATIVE_FUTURE_KEYWORDS = new String[]{
            "no future", "nothing will change", "always like this", "give up",
            "tired of life"
    };

    private SpeechRecognizer speechRecognizer;

    private int conversationRound = 0;
    private static final int MAX_CONVERSATION_ROUNDS = 8;
    private String currentContext = "";
    private String conversationHistory = "";

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
                        handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 800);
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
                        handler.postDelayed(this::startListeningToUser, 800);
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

    // NEW: detect theme of negative content
    private String detectNegativeTheme(String text) {
        if (text == null) return "general emotional pain";

        String lower = text.toLowerCase();

        for (String k : NEGATIVE_RELATIONSHIP_KEYWORDS) {
            if (lower.contains(k)) {
                return "relationship pain or heartbreak";
            }
        }
        for (String k : NEGATIVE_SELF_WORTH_KEYWORDS) {
            if (lower.contains(k)) {
                return "low self-worth, self-criticism, or feeling not enough";
            }
        }
        for (String k : NEGATIVE_FUTURE_KEYWORDS) {
            if (lower.contains(k)) {
                return "hopelessness about the future or feeling like nothing will change";
            }
        }
        return "general emotional pain and heavy negative content";
    }

    private void handleCaption(String caption) {
        String normalized = normalizeCaption(caption);
        long now = System.currentTimeMillis();

        if (normalized.equalsIgnoreCase(lastCaption) && (now - lastCaptionAt) < COOLDOWN_MS) return;
        lastCaption = normalized;
        lastCaptionAt = now;

        String sentiment = containsLocalNegative(normalized) ? "negative" : "positive";
        currentConversationLanguage = detectLanguage(normalized);

        if (sentiment.equals("negative")) {
            negativeCount++;
            safeLog(Log.WARN, " Negative reel #" + negativeCount);
            broadcastCounts();

            if (negativeCount >= 3 && !jarvisActive && !isSpeaking) {
                jarvisActive = true;
                stopDetection = true;
                conversationRound = 0;

                String negativeTheme = detectNegativeTheme(normalized);
                currentContext = "User is watching negative reels about " + negativeTheme;
                conversationHistory = "";

                boolean isHindi = currentConversationLanguage.equals("hi");

                safeLog(Log.INFO, "🤖 Starting Jarvis in " + (isHindi ? "Hindi" : "English") + " mode - Round: " + conversationRound);

                String prompt = buildJarvisPrompt(
                        "Caption / content looks like: \"" + normalized + "\". User has been repeatedly watching this type of negative reel.",
                        sentiment,
                        isHindi,
                        conversationHistory
                );
                callGeminiWithRetry(prompt, isHindi, 1024, 0);
            }
        } else {
            positiveCount++;
            safeLog(Log.INFO, " Positive/safe content #" + positiveCount);
            broadcastCounts();
        }
    }

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
                "kyun", "kahan", "kab", "theek", "thik", "accha", "acha",
                "bhai", "yaar", "dekh", "dekho", "suno", "bolo", "baat", "kar",
                "samajh", "pata", "malum", "chahiye", "chahta", "chahti",
                "ho", "raha", "rahe", "rahi", "gaya", "gayi", "karo", "karna",
                "dil", "pyaar", "mohabbat", "dukhi", "udaas", "tension", "problem",
                "sahi", "galat", "achha", "bura", "khushi", "gum", "dard", "takleef"
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

    // NEW: much more motivational + story-based prompt
    private String buildJarvisPrompt(String userText,
                                     String sentiment,
                                     boolean isHindi,
                                     String history) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are Jarvis, a warm, empathetic AI friend who genuinely cares about ")
                .append("the user's mental health and emotional safety.\n")
                .append("Your goal is to:\n")
                .append("- Gently validate their feelings.\n")
                .append("- Calm their nervous system with reassuring language.\n")
                .append("- Give them HOPE that things can slowly get better.\n")
                .append("- Motivate them to take tiny, realistic positive steps.\n")
                .append("- Never judge, never shame, never pressure.\n\n");

        sb.append("Tone and style:\n")
                .append("- Speak like a real, caring friend, not like a doctor or robot.\n")
                .append("- Use simple, natural, conversational language.\n")
                .append("- Keep responses short: 2–4 sentences max.\n")
                .append("- Be emotionally safe: do NOT give medical diagnosis or guarantees.\n")
                .append("- If you sense very dark thoughts (e.g. self-harm, suicide), ")
                .append("gently encourage them to reach out to a trusted person or professional.\n\n");

        sb.append("Current context:\n")
                .append("- User has been watching negative content about relationships, self-worth, or emotional pain on Instagram.\n");
        if (currentContext != null && !currentContext.isEmpty()) {
            sb.append("- Extra contextual note: ").append(currentContext).append("\n");
        }
        sb.append("- Detected sentiment: ").append(sentiment).append("\n");
        if (history != null && !history.isEmpty()) {
            sb.append("- Short conversation history so far (do NOT repeat it, just use it for context):\n")
                    .append(history).append("\n");
        }

        sb.append("\nConversation rules:\n");
        if (conversationRound == 0) {
            sb.append("- This is your FIRST message. Start very gently.\n")
                    .append("- Acknowledge that the content they are seeing is heavy or painful.\n")
                    .append("- Normalize that feeling low or overwhelmed is a human experience.\n")
                    .append("- Ask 1 simple, open question about how they are feeling right now.\n");
        } else {
            sb.append("- Respond naturally to what the user just said.\n")
                    .append("- Ask at most ONE short follow-up question.\n")
                    .append("- Focus on understanding and emotional support, not fixing everything.\n");
        }

        sb.append("- Always avoid arguments or debating their pain.\n")
                .append("- Gently challenge very harsh self-talk by offering a kinder reframe.\n")
                .append("- Use tiny motivational nudges like: small self-care ideas, reminding them they matter, or that phases pass.\n");

        sb.append("- Conversation round: ").append(conversationRound)
                .append(" of ").append(MAX_CONVERSATION_ROUNDS).append(".\n");
        if (conversationRound == 6) {
            sb.append("- After this response, gently ask if they want to keep talking or say 'exit' to stop.\n");
        }
        if (conversationRound >= MAX_CONVERSATION_ROUNDS - 2) {
            sb.append("- This is near the end. Start to wrap up with positive reinforcement and remind them you are there if they return.\n");
        }

        if ("negative".equalsIgnoreCase(sentiment)) {
            sb.append("\nVery important when content is negative:\n")
                    .append("- Briefly (in 1–2 sentences) share a SIMPLE, short, realistic story or example of someone who went through similar pain and slowly felt better.\n")
                    .append("- Make the story feel human and relatable, not dramatic or cinematic.\n")
                    .append("- The story should comfort them and show that change is possible, without promising miracles.\n");
        }

        sb.append("\nLanguage instruction:\n");
        if (isHindi) {
            sb.append("- Language: HINDI in Roman script (Hinglish).\n")
                    .append("- Respond ONLY in friendly Hinglish (Hindi written in English letters).\n")
                    .append("- Use warm, casual words like: yaar, dost, bhai when appropriate.\n")
                    .append("- Avoid formal words like 'beta', avoid sounding like a lecture.\n")
                    .append("- Examples of vibe only (do NOT copy): 'hey yaar, kya chal raha hai?', 'main samajh sakta hoon, yeh easy nahi hota'.\n");
        } else {
            sb.append("- Language: ENGLISH.\n")
                    .append("- Respond ONLY in natural, conversational English.\n")
                    .append("- Sound like a caring friend: simple, kind, non-clinical.\n")
                    .append("- Examples of vibe only (do NOT copy): 'hey, that sounds really heavy', 'you don't have to carry all of this alone'.\n");
        }

        sb.append("\nUser's latest input (use this as the main focus; do NOT repeat it verbatim):\n");
        if (userText != null && !userText.isEmpty()) {
            sb.append(userText).append("\n");
        } else {
            sb.append("(User has not spoken yet; just watched negative reels.)\n");
        }

        sb.append("Now generate your reply in the SAME language specified above. Keep it short (2–4 sentences), kind, and motivating.\n");

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
                generationConfig.put("temperature", 0.7);
                generationConfig.put("topK", 40);
                generationConfig.put("topP", 0.95);
                generationConfig.put("maxOutputTokens", maxOutputTokens);
                root.put("generationConfig", generationConfig);

                RequestBody body = RequestBody.create(root.toString(), JSON);
                String url = GEMINI_URL + "?key=" + geminiApiKey;

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .build();

                safeLog(Log.INFO, " Calling Gemini API (Attempt " + (retryCount + 1) + ")...");

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";

                safeLog(Log.INFO, " Gemini Response Code: " + response.code());

                if (response.isSuccessful()) {
                    String reply = extractTextFromGeminiResponse(respStr);
                    String finishReason = extractFinishReason(respStr);

                    if ((reply == null || reply.trim().isEmpty()) && "MAX_TOKENS".equalsIgnoreCase(finishReason) && retryCount < 2) {
                        safeLog(Log.WARN, "⚠️ Gemini returned MAX_TOKENS - retrying with larger maxOutputTokens...");
                        callGeminiWithRetry(prompt, isHindi, Math.max(maxOutputTokens * 4, 4096), retryCount + 1);
                        return;
                    }

                    if (reply != null && !reply.trim().isEmpty()) {
                        conversationHistory += "Jarvis: " + reply.trim() + "\n";
                        safeLog(Log.INFO, " Gemini Success: " + reply.trim());
                        queueResponse(reply.trim(), isHindi);
                        return;
                    } else {
                        safeLog(Log.ERROR, " No text content found in response");
                        if (retryCount < 1) {
                            safeLog(Log.WARN, " Retrying due to empty response...");
                            handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), 1000);
                            return;
                        }
                    }
                } else {
                    if (response.code() == 503 || response.code() == 429) {
                        if (retryCount < 3) {
                            int delay = (retryCount + 1) * 2000;
                            safeLog(Log.WARN, "⚠️ Gemini overloaded (" + response.code() + ") - retrying in " + delay + "ms...");
                            handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), delay);
                            return;
                        }
                    }
                    safeLog(Log.ERROR, " Gemini API error: " + response.code() + " - " + respStr);
                    if (retryCount < 1) {
                        safeLog(Log.WARN, " Final retry after API error...");
                        handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), 3000);
                        return;
                    }
                }

                safeLog(Log.ERROR, " All Gemini attempts failed. Waiting for user input...");
                handler.post(() -> {
                    if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
                        waitingForUser = true;
                        handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 1000);
                    }
                });

            } catch (Exception e) {
                safeLog(Log.ERROR, " Gemini error: " + e.getMessage());
                if (retryCount < 2) {
                    int delay = (retryCount + 1) * 2000;
                    safeLog(Log.WARN, "🔄 Retrying after exception in " + delay + "ms...");
                    handler.postDelayed(() -> callGeminiWithRetry(prompt, isHindi, maxOutputTokens, retryCount + 1), delay);
                } else {
                    safeLog(Log.ERROR, " All retries failed due to exceptions. Continuing conversation...");
                    handler.post(() -> {
                        if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
                            waitingForUser = true;
                            handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 1000);
                        }
                    });
                }
            }
        });
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
                handler.postDelayed(this::startListeningToUser, 500);
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
            String voiceId;
            if (isHindi) {
                voiceId = "hi-IN-kabir";
                safeLog(Log.INFO, "🔊 Using Kabir voice for Hindi response");
            } else {
                voiceId = "en-IN-aarav";
                safeLog(Log.INFO, "🔊 Using Aarav voice for English response");
            }

            JSONObject json = new JSONObject();
            json.put("text", text);
            json.put("voice_id", voiceId);
            json.put("style", "Conversational");
            json.put("speed", 1.0);
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

            safeLog(Log.INFO, "🔊 Calling Murf TTS with " + (isHindi ? "Kabir" : "Aarav") + " voice for " + (isHindi ? "Hindi" : "English") + "...");

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
            } else {
                safeLog(Log.ERROR, "❌ Murf TTS failed: " + respStr);
                if (respStr.contains("402") || respStr.contains("characters have been consumed")) {
                    safeLog(Log.ERROR, "❌ Murf TTS character limit reached");
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
                            handler.postDelayed(BackgroundTaskService.this::startListeningToUser, 500);
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

        // NEW: keep a generic context if not set
        if (currentContext == null || currentContext.isEmpty()) {
            currentContext = "User previously watched negative reels and is now talking about their feelings.";
        }

        if (userInterrupted) {
            conversationRound++;
            userInterrupted = false;
        }

        safeLog(Log.INFO, "🤖 Conversation Round: " + conversationRound + "/" + MAX_CONVERSATION_ROUNDS);

        String lower = userText.toLowerCase();
        if (lower.contains("exit") || lower.contains("close") ||
                lower.contains("stop") || lower.contains("bye") ||
                lower.contains("band") || lower.contains("ruk") ||
                lower.contains("bas") || lower.contains("enough")) {

            safeLog(Log.INFO, "🚪 User requested to exit - closing Instagram");

            String farewellMessage = isHindi ?
                    "Theek hai dost! Main Instagram band kar raha hoon. Aap kabhi bhi baat kar sakte hain. Take care!" :
                    "Okay friend! I'm closing Instagram. You can always talk to me anytime. Take care!";

            queueResponse(farewellMessage, isHindi);

            handler.postDelayed(() -> {
                closeInstagram();
                endConversation();
            }, 3000);
            return;
        }

        if (conversationRound >= 6 && conversationRound < MAX_CONVERSATION_ROUNDS) {
            String continuePrompt = isHindi ?
                    "Kya aap baat karna jaari rakhna chahenge? Agar nahi to 'exit' bol sakte hain." :
                    "Would you like to continue talking? If not, you can say 'exit'.";

            queueResponse(continuePrompt, isHindi);

            conversationRound++;
            waitingForUser = true;
            handler.postDelayed(this::startListeningToUser, 500);
            return;
        }

        conversationRound++;

        if (jarvisActive && conversationRound < MAX_CONVERSATION_ROUNDS) {
            String prompt = buildJarvisPrompt(userText, "neutral", isHindi, conversationHistory);
            callGeminiWithRetry(prompt, isHindi, 1024, 0);
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
            }, 1000);

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
            }, 3000);

            safeLog(Log.INFO, "✅ Instagram close process initiated with multiple strategies");

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
        }, 3000);
    }

    private void endConversationWithMessage(boolean isHindi) {
        jarvisActive = false;
        stopDetection = false;
        negativeCount = 0;

        String closingPrompt = isHindi ?
                "The conversation is ending. Provide gentle closure in Hindi. Summarize positively and remind them you're there for them. Keep it friendly and encouraging in Roman Hindi." :
                "The conversation is ending. Provide gentle closure. Summarize positively and remind them you're there for them. Keep it friendly and encouraging.";

        callGeminiWithRetry(closingPrompt, isHindi, 1024, 0);

        handler.postDelayed(() -> {
            jarvisActive = false;
            stopDetection = false;
            conversationRound = 0;
            conversationHistory = "";
            safeLog(Log.INFO, "🔄 Returning to normal content detection");
        }, 5000);
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