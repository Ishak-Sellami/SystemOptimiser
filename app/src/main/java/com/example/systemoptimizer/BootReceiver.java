package com.example.systemoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * استقبال أحداث بدء التشغيل وإعادة استعادة جلسة الحجب بعد الإقلاع.
 *
 * LOCKED_BOOT_COMPLETED  — يُقرأ مباشرة (التخزين محمي من الجهاز)؛
 *                           تُشغَّل الخدمة عند إمكانية ذلك.
 * BOOT_COMPLETED          — الجهاز جاهز كلياً؛ تُشغَّل الخدمة.
 * USER_UNLOCKED           — المستخدم فك قفل الجهاز بعد FBE؛ تُشغَّل الخدمة
 *                           وتُعاد فرض Lock Task/الواجهة المسموح بها.
 *
 * لا يُعدَّل العداد عند الإعادة: BlockSessionManager يحسب المدة المتبقية من
 * بداية/نهاية الجلسة المخزنة (لا إعادة ضبط بعد الإقلاع).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action);
        if (!relevant) {
            return;
        }
        Log.i(TAG, "boot lifecycle event: " + action);
        Intent service = new Intent(context, BackgroundService.class);
        service.putExtra(BackgroundService.EXTRA_BS_TRIGGER, "boot:" + action);
        try {
            // على Android 8+ يمكن بدء خدمة من الخلفية عبر startForegroundService;
            // الخدمة تستدعي startForeground خلال 5 ثوانٍ.
            ContextCompat.startForegroundService(context, service);
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService failed (" + action + "): " + e.getMessage());
            try {
                context.startService(service);
            } catch (Exception ignored) {
                Log.w(TAG, "startService also failed for " + action);
            }
        }
    }
}