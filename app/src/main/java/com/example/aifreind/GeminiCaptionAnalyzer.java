package com.example.aifreind;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiCaptionAnalyzer {

    private static final String TAG = "GeminiCaptionAnalyzer";
    private static final String MODEL = "gemini-1.5-flash";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();

    public interface AnalysisCallback {
        void onResult(Result result);
    }

    public static class Result {
        public boolean isNegative;
        public String reason;
    }

    public static void analyzeCaption(String caption, AnalysisCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String prompt = "Classify this Instagram reel caption as 'NEGATIVE' if it expresses " +
                        "sadness, heartbreak, depression, stress, loneliness, or anything harmful to mental health. " +
                        "Otherwise respond 'SAFE'. Caption: \"" + caption + "\"";

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
                        .addHeader("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String respStr = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Gemini response: " + respStr);

                String text = parseResponse(respStr);

                Result result = new Result();
                result.isNegative = text.contains("NEGATIVE");
                result.reason = text;

                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));

            } catch (Exception e) {
                Log.e(TAG, "Gemini analysis failed", e);
                Result result = new Result();
                result.isNegative = false;
                result.reason = "ERROR";
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
            }
        });
    }

    private static String parseResponse(String respStr) {
        try {
            JSONObject root = new JSONObject(respStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        return parts.getJSONObject(0).optString("text", "SAFE").toUpperCase();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return "SAFE";
    }
}
