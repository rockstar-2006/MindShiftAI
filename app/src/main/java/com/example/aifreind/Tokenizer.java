package com.example.aifreind;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;

public class Tokenizer {

    private final HashMap<String, Integer> wordIndex;
    private final int maxLen;

    public Tokenizer(Context context, String fileName, int maxLen) {
        // Use context.getAssets()


    // Use context.getAssets()


        this.maxLen = maxLen;
        wordIndex = new HashMap<>();
        try {
            InputStream is = context.getAssets().open(fileName);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();

            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(json);
            JSONObject wIndex = obj.getJSONObject("config").getJSONObject("word_index");

            Iterator<String> keys = wIndex.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                wordIndex.put(key, wIndex.getInt(key));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public int[] textToSequence(String text) {
        text = text.toLowerCase().replaceAll("[^a-zA-Z0-9 ]", " ");
        String[] words = text.split("\\s+");

        int[] seq = new int[maxLen];
        for (int i = 0; i < maxLen; i++) {
            if (i < words.length) {
                seq[i] = wordIndex.getOrDefault(words[i], 1); // 1 = OOV token
            } else {
                seq[i] = 0; // padding
            }
        }
        return seq;
    }
}
