package com.example.aifreind;
import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

public class TTSFriend {
    private TextToSpeech tts;

    public TTSFriend(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });
    }

    public void say(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}

