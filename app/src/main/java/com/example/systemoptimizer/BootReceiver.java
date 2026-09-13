package com.example.systemoptimizer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        // Check if this is a relevant boot event
        boolean isBoot = intent != null && (
                Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_USER_UNLOCKED.equals(action)
        );

        if (!isBoot) {
            return;
        }

        Log.i(TAG, "Received boot event: " + action);

        Intent serviceIntent = new Intent(context, BackgroundService.class);
        serviceIntent.putExtra(BackgroundService.EXTRA_BS_TRIGGER, "boot:" + action);

        try {
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Foreground Service on boot", e);
            try {
                context.startService(serviceIntent);
            } catch (Exception ignored) {
                Log.e(TAG, "Failed to start Service as fallback", e);
            }
        }
    }
}