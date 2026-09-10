package com.example.systemoptimizer;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * مستقبِل إدارة الأجهزة المطلوب لترقية التطبيق إلى Device Owner في بيئة المختبر
 * المعزولة (adb: dpm set-device-owner). يتيح للتطبيق فرض قفل keyguard (lockNow)
 * ووضع كشك (lock task) أثناء مدة حجب الشاشة فقط.
 */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Device admin enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Device admin disabled", Toast.LENGTH_SHORT).show();
    }
}