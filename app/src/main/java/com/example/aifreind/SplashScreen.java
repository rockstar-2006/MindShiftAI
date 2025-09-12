package com.example.aifreind;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.airbnb.lottie.LottieAnimationView;

public class SplashScreen extends AppCompatActivity {

    private LottieAnimationView lottieBot;
    private TextView appName;
    private TextView attribution;
    private TextView quote;
    private final String appNameFull = "MindShift AI";
    private final String inspiringQuote = "\"Peace begins with a smile.\"";
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash_screen);

        handler = new Handler();

        lottieBot = findViewById(R.id.lottieBot);
        appName = findViewById(R.id.appName);
        attribution = findViewById(R.id.attribution);
        quote = findViewById(R.id.quote);

        appName.setVisibility(View.INVISIBLE);
        attribution.setAlpha(0f);
        quote.setAlpha(0f);
        lottieBot.setTranslationY(-600f);
        lottieBot.setScaleX(0.8f);
        lottieBot.setScaleY(0.8f);
        lottieBot.setAlpha(0f);

        handler.postDelayed(() -> {
            lottieBot.setVisibility(View.VISIBLE);

            ObjectAnimator animatorY = ObjectAnimator.ofFloat(lottieBot, "translationY", -600f, 0f);
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(lottieBot, "scaleX", 0.8f, 1f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(lottieBot, "scaleY", 0.8f, 1f);
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(lottieBot, "alpha", 0f, 1f);

            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(animatorY, scaleX, scaleY, fadeIn);
            animatorSet.setDuration(1200);
            animatorSet.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            animatorSet.start();
            handler.postDelayed(() -> {
                Intent intent = new Intent(SplashScreen.this, MainActivity.class);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }, 5000);  // Or your desired delay matching splash animation duration


            handler.postDelayed(() -> lottieBot.playAnimation(), 1200);

            handler.postDelayed(() -> {
                appName.setVisibility(View.VISIBLE);
                // Increased delay here for slower and smoother animation
                typeWriterEffect(appNameFull, appName, 130);
            }, 1400);

            handler.postDelayed(() -> {
                quote.setText(inspiringQuote);
                quote.animate().alpha(1f).setDuration(800).start();
            }, 2800);

            handler.postDelayed(() -> {
                attribution.animate().alpha(1f).setDuration(800).start();
            }, 3500);

            handler.postDelayed(() -> {
                // TODO: startActivity(new Intent(SplashScreen.this, MainActivity.class));
                // overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                // finish();
            }, 5000);
        }, 300);
    }

    private void typeWriterEffect(final String text, final TextView textView, final int delay) {
        textView.setText("");
        for (int i = 1; i <= text.length(); i++) {
            final int finalI = i;
            handler.postDelayed(() -> {
                textView.setText(text.substring(0, finalI));
                if (finalI == text.length()) {
                    applyGradientToText(textView);
                }
            }, i * delay);
        }
    }

    private void applyGradientToText(TextView textView) {
        float width = textView.getPaint().measureText(textView.getText().toString());
        Shader shader = new LinearGradient(
                0, 0, width, textView.getTextSize(),
                new int[]{Color.parseColor("#91eac9"), Color.parseColor("#6CB3F4"), Color.parseColor("#B2A6FF")},
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP);
        textView.getPaint().setShader(shader);
        textView.invalidate();
    }
}
