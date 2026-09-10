package com.example.systemoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * مستقبل مؤشرات جلسة الحجب (تشغيل مجدول / انتهاء).
 *
 * يوقظ الخدمة فقط؛ الخدمة هي التي تعدّل الحالة (نموذج جلسة واحدة في
 * BlockSessionManager). بذلك تبقى الجلسة سياسة محلية تعمل حتى لو أُعيدت
 * العملية أو انفصل المشغّل أو أُعيد تشغيل الجهاز.
 */
public class BlockSessionAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "BlockAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        String trigger = intent != null
                ? intent.getStringExtra("trigger")
                : BlockSessionManager.TRIGGER_EXPIRE;
        Log.i(TAG, "alarm received: " + trigger);
        Intent service = new Intent(context, BackgroundService.class);
        service.putExtra(BackgroundService.EXTRA_BS_TRIGGER, trigger);
        try {
            ContextCompat.startForegroundService(context, service);
        } catch (Exception e) {
            try {
                context.startService(service);
            } catch (Exception ignored) {
                Log.w(TAG, "unable to start service from alarm: " + e.getMessage());
            }
        }
    }
}