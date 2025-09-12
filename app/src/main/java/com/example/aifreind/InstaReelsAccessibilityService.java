package com.example.aifreind;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class InstaReelsAccessibilityService extends AccessibilityService {
    private static final String TAG = "InstaReelsService";
    private static final String INSTAGRAM_PACKAGE = "com.instagram.android";

    private String lastProcessedText = "";
    private long lastProcessedAtMs = 0;
    private static final long DEBOUNCE_MS = 3500; // 3.5s debounce

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;

        String packageName = String.valueOf(event.getPackageName());
        if (!INSTAGRAM_PACKAGE.equals(packageName)) return; // only when IG visible

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            StringBuilder sb = new StringBuilder();
            collectScreenText(root, sb);
            String raw = sb.toString().trim();
            if (raw.isEmpty()) return;

            // Clean up and normalize (removes ui noise and replaces emojis)
            String cleaned = cleanCaption(raw);

            if (cleaned.isEmpty()) return;

            long now = System.currentTimeMillis();
            if (!cleaned.equals(lastProcessedText) || (now - lastProcessedAtMs) > DEBOUNCE_MS) {
                lastProcessedText = cleaned;
                lastProcessedAtMs = now;

                // Send to background service for classification / immediate handling
                BackgroundTaskService.enqueueCaption(getApplicationContext(), cleaned);
            }
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility interrupted");
    }

    // traverse and collect visible text & contentDescription
    private void collectScreenText(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return;

        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            String s = t.toString().trim();
            if (isLikelyUsefulText(s)) out.append(s).append(" ");
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) {
            String s = d.toString().trim();
            if (isLikelyUsefulText(s)) out.append(s).append(" ");
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            collectScreenText(c, out);
            if (c != null) c.recycle();
        }
    }

    // heuristics to filter out obvious UI labels
    private boolean isLikelyUsefulText(String t) {
        if (t == null) return false;
        t = t.trim();
        if (t.length() < 2) return false;
        String lower = t.toLowerCase();
        if (lower.equals("reels") || lower.equals("like") || lower.equals("comment") ||
                lower.equals("share") || lower.startsWith("@") || lower.equals("friends") ||
                lower.equals("home") || lower.equals("search and explore") || lower.equals("audio"))
            return false;
        return true;
    }

    // remove noise and normalize emojis (keeps result small & classification-friendly)
    private String cleanCaption(String raw) {
        if (raw == null) return "";
        String s = raw;

        // Remove frequent UI noise patterns (Instagram label fragments)
        s = s.replaceAll("(?i)like number is\\d+.*?view likes", " ");
        s = s.replaceAll("(?i)comment number is\\d+.*?view comments", " ");
        s = s.replaceAll("(?i)reposted \\d+ times", " ");
        s = s.replaceAll("(?i)reshare number is\\d+", " ");
        s = s.replaceAll("(?i)follow", " ");
        s = s.replaceAll("(?i)profile", " ");
        s = s.replaceAll("(?i)reel by [^\\n]+", " ");
        s = s.replaceAll("\\n+", " ");
        s = s.replaceAll("\\s{2,}", " ").trim();

        // emoji and shorthand mapping (expanded)
        s = s.replace("💔", " broken heart ")
                .replace("❤️‍🩹", " healing heart ")
                .replace("😢", " crying ")
                .replace("😭", " very sad ")
                .replace("😞", " disappointed ")
                .replace("😔", " sad ")
                .replace("🥺", " pleading sad ")
                .replace("😩", " exhausted sad ")
                .replace("😥", " worried sad ")
                .replace("😿", " crying cat ")
                .replace("🙁", " slightly sad ")
                .replace("☹️", " very sad ")

                // anger
                .replace("😡", " angry ")
                .replace("🤬", " very angry ")

                // love & care
                .replace("❤️", " love ")
                .replace("💕", " love ")
                .replace("💖", " love ")
                .replace("💙", " love ")
                .replace("💛", " love ")
                .replace("💜", " love ")
                .replace("🫶", " care ")

                // laughter
                .replace("😂", " laughing ")
                .replace("🤣", " laughing hard ")

                // cleanup extras
                .replaceAll("\\s{2,}", " ");

        // length limit to keep payload small
        if (s.length() > 2000) s = s.substring(0, 2000);
        return s;
    }
}
