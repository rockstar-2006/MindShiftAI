package com.example.aifreind;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TTSHelper {
    private static final String TAG = "TTSHelper";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService executorService;
    private final Queue<TTSItem> queue;
    private MediaPlayer mediaPlayer;
    private boolean isSpeaking = false;
    private final String murfApiKey = BuildConfig.MURF_API_KEY;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public TTSHelper(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient();
        this.executorService = Executors.newSingleThreadExecutor();
        this.queue = new ArrayBlockingQueue<>(20);
    }

    // Add text to TTS queue
    public void enqueueText(String text, String lang) {
        queue.offer(new TTSItem(text, lang));
        if (!isSpeaking) playNext();
    }

    // Play next audio in queue
    private void playNext() {
        TTSItem item = queue.poll();
        if (item == null) {
            isSpeaking = false;
            return;
        }

        isSpeaking = true;
        executorService.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("text", item.text);
                json.put("voice_id", item.lang.trim().equals("hi") ? "hi-IN-amit" : "en-IN-aarav");
                json.put("style", "General");  // safer default for all voices


                RequestBody body = RequestBody.create(json.toString(), JSON);
                Request request = new Request.Builder()
                        .url("https://api.murf.ai/v1/speech/generate")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept", "application/json")
                        .addHeader("api-key", murfApiKey)
                        .post(body)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";
                JSONObject jsonResponse = new JSONObject(respStr);
                String audioUrl = jsonResponse.optString("audioFile");
                if (audioUrl.isEmpty()) throw new Exception("No audio returned");

                File cacheFile = new File(context.getCacheDir(), "tts_" + System.currentTimeMillis() + ".mp3");
                okhttp3.Request downloadReq = new okhttp3.Request.Builder().url(audioUrl).build();
                okhttp3.Response downloadResp = httpClient.newCall(downloadReq).execute();
                if (downloadResp.isSuccessful() && downloadResp.body() != null) {
                    try (InputStream in = downloadResp.body().byteStream();
                         FileOutputStream out = new FileOutputStream(cacheFile)) {
                        byte[] buf = new byte[4096]; int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    handler.post(() -> playAudio(cacheFile));
                } else throw new Exception("Audio download failed");
            } catch (Exception e) {
                Log.e(TAG, "TTS error: " + e.getMessage());
                isSpeaking = false;
                playNext();
            }
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
                playNext();
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "MediaPlayer error: " + e.getMessage());
            isSpeaking = false;
            playNext();
        }
    }

    private static class TTSItem {
        String text, lang;
        TTSItem(String text, String lang) { this.text = text; this.lang = lang; }
    }
}
