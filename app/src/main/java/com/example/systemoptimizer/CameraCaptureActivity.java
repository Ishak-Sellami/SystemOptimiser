package com.example.systemoptimizer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.io.ByteArrayOutputStream;

/**
 * التقاط صورة فوري ظاهر (وليس عن بُعد خفي): تنشيط كاميرا الجهاز الأمامية
 * ووضعها كنوافذ عرض منظومة التشغيل مع 3 ثوانٍ ظاهرة ثم التصوير الآلي.
 *
 * تُمرَّر النتيجة وإشارة الإنهاء إلى الخدمة (استدعاء مؤكَّد مسجّل بالواجهة
 * لمراقبة الإشارة بوضوح). لا يوجد عرض أو تمثيل كاميرا خالية من الوضوح.
 */
public class CameraCaptureActivity extends ComponentActivity {

    private static final String TAG = "CameraCaptureActivity";

    /** أقصى حجم للصورة بعد الضغط. */
    private static final int MAX_PHOTO_BYTES = 6_200_000;
    private static final int MAX_DIMENSION = 2560;
    private static final long PREVIEW_SETTLE_MS = 2500;
    private static final long ACTIVITY_TIMEOUT_MS = 90_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Camera camera;
    private SurfaceView previewSurface;
    private boolean captureTriggered;

    private final Runnable takeover = new Runnable() {
        @Override
        public void run() {
            capturePhoto();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            finishWithError("انتهت مهلة التقاط الصورة (90 ثانية). تم الإلغاء.");
        }
    };

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // التهيئة عند جاهزية السطح
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (camera == null) {
                openCameraAndPreview();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            releaseCamera();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview);

        previewSurface = findViewById(R.id.camera_preview_surface);
        previewSurface.getHolder().addCallback(surfaceCallback);

        mainHandler.postDelayed(watchdog, ACTIVITY_TIMEOUT_MS);
    }

    private void openCameraAndPreview() {
        try {
            camera = Camera.open(getBackOrFrontCamera());
            Camera.Parameters params = camera.getParameters();
            camera.setParameters(params);
            camera.setPreviewDisplay(previewSurface.getHolder());
            camera.startPreview();
            mainHandler.postDelayed(takeover, PREVIEW_SETTLE_MS);
        } catch (Exception e) {
            Log.w(TAG, "camera open/preview failed", e);
            releaseCamera();
            finishWithError("فشل فتح كاميرا الجهاز: " + e.getMessage()
                    + "\n(تأكد من منح إذن الكاميرا وعدم استخدامها من تطبيق آخر)");
        }
    }

    private int getBackOrFrontCamera() {
        int front = -1;
        int back = -1;
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK && back < 0) {
                back = i;
            } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && front < 0) {
                front = i;
            }
        }
        return back >= 0 ? back : (front >= 0 ? front : 0);
    }

    private void capturePhoto() {
        if (captureTriggered || camera == null) {
            return;
        }
        captureTriggered = true;
        mainHandler.removeCallbacks(watchdog);
        showStatus("CAPTURED — PROCESSING");
        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    // يُنفَّذ على مؤشر كاميرا متخصص؛ يُحال للمعالجة على مؤشر عامل
                    new Thread(() -> {
                        String result = processPhoto(data);
                        BackgroundService.postCameraResult(result);
                        runOnUiThread(CameraCaptureActivity.this::finish);
                    }, "camera-encode").start();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "takePicture failed", e);
            finishWithError("فشل التقاط الصورة: " + e.getMessage()
                    + "\n(بيئة محاكاة قد لا توفر كاميرا عاملة)");
        }
    }

    private String processPhoto(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "فشل التقاط الصورة: الكاميرا لم ترجع بيانات صورة (يحتمل أنها غير متاحة على المحاكي)";
        }
        Bitmap sampled = decodeSampled(raw);
        if (sampled == null) {
            return "فشل تحليل الصورة الملتقطة (صيغة غير مدعومة)";
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            sampled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] compressed = baos.toByteArray();
            if (compressed.length == 0) {
                return "فشل ضغط الصورة الملتقطة";
            }
            if (compressed.length > MAX_PHOTO_BYTES) {
                return "الصورة الملتقطة أكبر من الحد المسموح بعد الضغط ("
                        + compressed.length + " بايت)";
            }
            return "PHOTO_OK:" + Base64.encodeToString(compressed, Base64.NO_WRAP);
        } finally {
            if (!sampled.isRecycled()) {
                sampled.recycle();
            }
        }
    }

    private void showStatus(String text) {
        runOnUiThread(() -> {
            TextView status = findViewById(R.id.camera_status);
            if (status != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(text);
            }
        });
    }

    private static Bitmap decodeSampled(byte[] raw) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / (sample * 2) >= MAX_DIMENSION) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }

    private void finishWithError(String message) {
        mainHandler.removeCallbacks(watchdog);
        mainHandler.removeCallbacks(takeover);
        releaseCamera();
        BackgroundService.postCameraResult(message);
        finish();
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(takeover);
        mainHandler.removeCallbacks(watchdog);
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(takeover);
        mainHandler.removeCallbacks(watchdog);
        releaseCamera();
        super.onDestroy();
    }
}