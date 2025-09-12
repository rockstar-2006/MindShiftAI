package com.example.aifreind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "ScreenCaptureChannel";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;

    private int screenDensity;
    private int screenWidth;
    private int screenHeight;

    private TextRecognizer textRecognizer;
    private Timer captureTimer;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        createNotificationChannel();
        startForeground(2, getNotification());

        Log.d(TAG, "ScreenCaptureService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        // register callback before creating virtual display (fixes IllegalStateException)
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.d(TAG, "MediaProjection stopped -> stopping service");
                stopSelf();
            }
        }, handler);

        setupVirtualDisplay();
        startScreenCaptureLoop();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (captureTimer != null) captureTimer.cancel();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        Log.d(TAG, "ScreenCaptureService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Capture Running")
                .setContentText("Monitoring screen text...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void setupVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private void startScreenCaptureLoop() {
        captureTimer = new Timer();
        captureTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                captureFrame();
            }
        }, 2000, 3000); // every 3s (you can tune)
    }

    private void captureFrame() {
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        try {
            int width = image.getWidth();
            int height = image.getHeight();
            final Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();

            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop to screen size
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);

            // OCR
            InputImage inputImage = InputImage.fromBitmap(cropped, 0);
            textRecognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        String detectedText = visionText.getText().trim();
                        if (!detectedText.isEmpty()) {
                            // forward cleaned text to background service
                            BackgroundTaskService.enqueueCaption(getApplicationContext(), detectedText);
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "OCR error: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "captureFrame error: " + e.getMessage());
        } finally {
            image.close();
        }
    }
}
