package com.example.aifreind;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class OcrProcessor {

    private static final String TAG = "OcrProcessor";

    public static void processImage(Context context, Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener(visionText -> {
                        String result = visionText.getText();
                        if (!result.isEmpty()) {
                            Log.d(TAG, "OCR Detected: " + result);
                            BackgroundTaskService.enqueueCaption(context, result);
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "OCR Failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error processing image: " + e.getMessage());
        }
    }
}
