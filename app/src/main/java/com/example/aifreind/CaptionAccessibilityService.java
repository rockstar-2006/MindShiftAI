package com.example.aifreind;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;

public class CaptionAccessibilityService extends AccessibilityService {

    private static final String TAG = "CaptionAccessibility";

    // store already processed captions
    private static final HashSet<String> processedCaptions = new HashSet<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                String caption = extractText(rootNode);
                caption = normalize(caption);

                if (caption != null && !caption.isEmpty()) {

                    // avoid repeats for the same reel
                    if (processedCaptions.contains(caption)) {
                        return; // already handled
                    }

                    processedCaptions.add(caption); // mark as processed

                    Log.d(TAG, "✅ New Caption Detected: " + caption);

                    // Send immediately to BackgroundTaskService
                    BackgroundTaskService.enqueueCaption(getApplicationContext(), caption);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }

    private String extractText(AccessibilityNodeInfo node) {
        if (node == null) return "";

        StringBuilder sb = new StringBuilder();

        if (node.getText() != null) {
            sb.append(node.getText().toString()).append(" ");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                sb.append(extractText(child));
                child.recycle();
            }
        }
        return sb.toString().trim();
    }

    // normalize caption to prevent small UI changes being detected as new
    private String normalize(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\n+", " ");
        s = s.replaceAll("\\s{2,}", " ");
        s = s.trim();
        if (s.length() > 500) {
            s = s.substring(0, 500); // limit size
        }
        return s.toLowerCase(); // case-insensitive match
    }
}
