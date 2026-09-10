package com.example.systemoptimizer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * مدير جلسة حجب الشاشة — حالة الجلسة هي السلطة، وليس الـ Activity.
 *
 * تصميم أساسي:
 * - التخزين في مساحة محمية من الجهاز (device-protected storage) لتُقرأ حتى قبل
 *   فك قفل المستخدم بعد الإقلاع (LOCKED_BOOT_COMPLETED / USER_UNLOCKED).
 * - الساعة: System.currentTimeMillis() (زمن حائطي RTC) للانتهاء — ثابت ولا
 *   يعتمد على عمر النشاط. لا يتم إعادة ضبط العداد عند إعادة إنشاء النشاط أو
 *   إعادة تشغيل الجهاز: تُحفظ start/expire وتُحسب المدة المتبقية منها.
 * - مؤشرات AlarmManager (مرتين): واحدة للتشغيل المجدول، وواحدة للانتهاء.
 *   مؤشر واحد لكل نوع — لا يمكن لساعتين أن تتصارعا (استبدالها دائماً).
 * - لا يعتمد التنفيذ على اتصال TCP: بمجرد قبول الجلسة تصبح سياسة محلية.
 * - سجل تدقيق (audit) دائري مقتطع بحد أقصى للأسطر.
 */
public final class BlockSessionManager {

    public static final String STATE_NONE = "NONE";
    public static final String STATE_SCHEDULED = "SCHEDULED";
    public static final String STATE_ACTIVE = "ACTIVE";

    public static final String TRIGGER_ACTIVATE = "activate";
    public static final String TRIGGER_EXPIRE = "expire";

    private static final String TAG = "BlockSession";
    private static final String PREFS = "block_session_prefs";

    private static final String K_ID = "bs_id";
    private static final String K_STATE = "bs_state";
    private static final String K_START = "bs_start_epoch_ms";
    private static final String K_EXPIRE = "bs_expire_epoch_ms";
    private static final String K_SCHEDULED_START = "bs_scheduled_start_epoch_ms";
    private static final String K_DURATION = "bs_duration_sec";
    private static final String K_DELAY = "bs_start_delay_sec";
    private static final String K_RESTORED = "bs_restored_after_boot";
    private static final String K_AUDIT = "bs_audit";

    private static final long MAX_DURATION_SEC = 86400L;
    private static final long MAX_DELAY_SEC = 86400L;
    private static final int AUDIT_MAX_LINES = 25;

    private static final String ACTION_ACTIVATE =
            "com.example.systemoptimizer.action.BLOCK_ACTIVATE";
    private static final String ACTION_EXPIRE =
            "com.example.systemoptimizer.action.BLOCK_EXPIRE";

    private static volatile BlockSessionManager instance;

    private final Context app;
    private final AlarmManager alarmManager;

    private BlockSessionManager(Context context) {
        this.app = context.getApplicationContext();
        this.alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
    }

    public static synchronized BlockSessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new BlockSessionManager(context);
        }
        return instance;
    }

    /** مساحة محمية من الجهاز: متاحة حتى قبل فك قفل المستخدم (FBE). */
    private SharedPreferences prefs() {
        return app.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // عمليات الجلسة
    // ------------------------------------------------------------------

    /**
     * جدولة جلسة جديدة (أو استبدال الجلسة الحالية صراحةً — نموذج جلسة واحدة).
     * تُسجَّل SCHEDULED ثم يُضبط مؤشر التشغيل المجدول.
     *
     * @return خرائط بالمعلومات المحسوبة؛ null إذا كانت المعطيات غير صالحة.
     */
    public synchronized Map<String, String> schedule(int delaySec, int durationSec, String sessionId) {
        if (durationSec < 1 || durationSec > MAX_DURATION_SEC) {
            return null;
        }
        if (delaySec < 0 || delaySec > MAX_DELAY_SEC) {
            return null;
        }
        long now = System.currentTimeMillis();
        long scheduledStart = now + delaySec * 1000L;
        long expire = scheduledStart + durationSec * 1000L;
        String id = (sessionId == null || sessionId.trim().isEmpty())
                ? "BS-" + Long.toHexString(System.nanoTime()).toUpperCase(Locale.US)
                : sessionId.trim();

        SharedPreferences p = prefs();
        p.edit()
                .putString(K_ID, id)
                .putString(K_STATE, STATE_SCHEDULED)
                .putLong(K_START, scheduledStart)
                .putLong(K_SCHEDULED_START, scheduledStart)
                .putLong(K_EXPIRE, expire)
                .putLong(K_DURATION, durationSec)
                .putLong(K_DELAY, delaySec)
                .putBoolean(K_RESTORED, false)
                .apply();
        scheduleActivateAlarm(scheduledStart);
        cancelExpireAlarm();
        audit("session scheduled id=" + id + " dur=" + durationSec + "s delay=" + delaySec + "s");

        Map<String, String> info = new LinkedHashMap<>();
        info.put("Session ID", id);
        info.put("State", STATE_SCHEDULED);
        info.put("Requested duration", String.valueOf(durationSec));
        info.put("Start delay", String.valueOf(delaySec));
        info.put("Scheduled start (epoch ms)", String.valueOf(scheduledStart));
        info.put("Expiration epoch ms", String.valueOf(expire));
        return info;
    }

    /**
     * تنشيط الجلسة المجدولة إذا حان وقتها (يُستدعى فورياً عند delay=0 أو من
     * مؤشر التنشيط). يحوّل الحالة إلى ACTIVE ويضبط مؤشر الانتهاء.
     *
     * @return true إذا أصبحت الجلسة نشطة فعلاً.
     */
    public synchronized boolean activateIfDue() {
        SharedPreferences p = prefs();
        String state = p.getString(K_STATE, STATE_NONE);
        if (!STATE_SCHEDULED.equals(state)) {
            return isActive();
        }
        long scheduledStart = p.getLong(K_SCHEDULED_START, 0);
        if (System.currentTimeMillis() < scheduledStart) {
            return false; // لم يحن الموعد بعد (مؤشر التنشيط المبكر)
        }
        p.edit().putString(K_STATE, STATE_ACTIVE).putBoolean(K_RESTORED, false).apply();
        scheduleExpireAlarm();
        audit("session activated id=" + p.getString(K_ID, "?"));
        return true;
    }

    /** بدء جلسة فورية مع الحفاظ على مدة الجلسة المطلوبة. */
    public synchronized boolean startNow(int durationSec, String sessionId) {
        Map<String, String> info = schedule(0, durationSec, sessionId);
        if (info == null) {
            return false;
        }
        return activateIfDue();
    }

    /** جلسة صالحة نشطة الآن؟ */
    public synchronized boolean isActive() {
        SharedPreferences p = prefs();
        if (!STATE_ACTIVE.equals(p.getString(K_STATE, STATE_NONE))) {
            return false;
        }
        long expire = p.getLong(K_EXPIRE, 0);
        if (System.currentTimeMillis() >= expire) {
            return false;
        }
        return true;
    }

    public synchronized boolean isScheduled() {
        return STATE_SCHEDULED.equals(prefs().getString(K_STATE, STATE_NONE));
    }

    public long getRemainingMs() {
        long expire = prefs().getLong(K_EXPIRE, 0);
        return Math.max(0L, expire - System.currentTimeMillis());
    }

    public synchronized boolean hasActiveOrScheduled() {
        String state = prefs().getString(K_STATE, STATE_NONE);
        return STATE_ACTIVE.equals(state) || STATE_SCHEDULED.equals(state);
    }

    /**
     * تعويض تأجيل مؤشرات Android (while-idle قد يتأخر في Doze): يُدعى من كل
     * مسار استعلام/تنفيذ. يحوّل الجلسات المنتهية فعلاً (now >= expire) إلى
     * NONE فوراً، وينشّط المجدول الذي حان وقته. لا يُقدّم الإفراج أبداً.
     *
     * @return true إذا كانت هناك جلسة نشطة بعد التسوية.
     */
    public synchronized boolean settleDue() {
        SharedPreferences p = prefs();
        String state = p.getString(K_STATE, STATE_NONE);
        if (!STATE_ACTIVE.equals(state) && !STATE_SCHEDULED.equals(state)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expire = p.getLong(K_EXPIRE, 0);
        if (now >= expire) {
            terminate("expired (settled on status)");
            return false;
        }
        if (STATE_SCHEDULED.equals(state)
                && now >= p.getLong(K_SCHEDULED_START, 0)) {
            activateIfDue();
        }
        return isActive();
    }

    public String getSessionId() {
        return prefs().getString(K_ID, "NONE");
    }

    public String getState() {
        return prefs().getString(K_STATE, STATE_NONE);
    }

    /**
     * إنهاء الجلسة (انتهاء طبيعي أو إيقاف من المشغّل). يمسح الحالة ويلغي
     * المؤشرات ويعيد الجهاز لحالته. الإفراج الفعلي عن Lock Task يقوم به
     * النشاط عند رؤية الحالة NONE خلال ±1 ثانية.
     */
    public synchronized void terminate(String reason) {
        SharedPreferences p = prefs();
        String prev = p.getString(K_STATE, STATE_NONE);
        if (STATE_NONE.equals(prev)) {
            return;
        }
        p.edit().putString(K_STATE, STATE_NONE).putBoolean(K_RESTORED, false).apply();
        cancelActivateAlarm();
        cancelExpireAlarm();
        clearLockTaskConfigurationQuietly();
        audit("session terminated reason=" + reason + " id=" + p.getString(K_ID, "?"));
    }

    /**
     * يُستدعى عند بدء الخدمة (إقلاع/إعادة إنشاء عملية/مؤشر). يعدّل الحالة
     * حسب الحقيقة:
     *  - منتهي منذ زمن -> إفراغ صامت (لا قفل بعد الإعادة).
     *  - مجدول وحان وقته -> تنشيط.
     *  - نشط -> ضمان مؤشر الانتهاء (لا إعادة ضبط للعداد).
     *
     * @return true إذا كان هناك جلسة نشطة/قابلة للاستئناف الآن.
     */
    public synchronized boolean recoverAfterServiceStart() {
        SharedPreferences p = prefs();
        String state = p.getString(K_STATE, STATE_NONE);
        long now = System.currentTimeMillis();
        if (STATE_SCHEDULED.equals(state) || STATE_ACTIVE.equals(state)) {
            long scheduledStart = p.getLong(K_SCHEDULED_START, 0);
            long expire = p.getLong(K_EXPIRE, 0);
            if (now >= expire) {
                p.edit().putString(K_STATE, STATE_NONE).apply();
                cancelActivateAlarm();
                cancelExpireAlarm();
                clearLockTaskConfigurationQuietly();
                audit("session expired while service was down - cleared (no re-lock)");
                return false;
            }
            if (STATE_SCHEDULED.equals(state) && now >= scheduledStart) {
                p.edit().putString(K_STATE, STATE_ACTIVE).apply();
                audit("scheduled session activated after service start");
            }
            if (!p.getBoolean(K_RESTORED, false)) {
                p.edit().putBoolean(K_RESTORED, true).apply();
                audit("session restored after service start id=" + p.getString(K_ID, "?")
                        + " remaining=" + remainingSeconds());
            }
            scheduleExpireAlarm();
            return true;
        }
        return false;
    }

    public long remainingSeconds() {
        return getRemainingMs() / 1000L;
    }

    /** معلومات الجلسة الكاملة للاستعلام. */
    public synchronized Map<String, String> sessionInfo() {
        SharedPreferences p = prefs();
        Map<String, String> info = new LinkedHashMap<>();
        info.put("Session ID", p.getString(K_ID, "NONE"));
        info.put("State", p.getString(K_STATE, STATE_NONE));
        info.put("Requested duration", p.getLong(K_DURATION, 0) + "s");
        info.put("Start delay", p.getLong(K_DELAY, 0) + "s");
        info.put("Start epoch ms", p.getLong(K_START, 0) + "");
        info.put("Expiration epoch ms", p.getLong(K_EXPIRE, 0) + "");
        info.put("Remaining", remainingSeconds() + "s");
        return info;
    }

    // ------------------------------------------------------------------
    // المؤشرات (مؤشر واحد للنوع؛ استبدال آمن)
    // ------------------------------------------------------------------

    private void scheduleActivateAlarm(long atEpochMs) {
        if (alarmManager == null) {
            return;
        }
        PendingIntent pi = pending(ACTION_ACTIVATE, 1001);
        try {
            alarmManager.cancel(pi);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi);
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleActivateAlarm blocked", e);
        } catch (Exception e) {
            Log.w(TAG, "scheduleActivateAlarm failed", e);
        }
    }

    private void scheduleExpireAlarm() {
        if (alarmManager == null) {
            return;
        }
        PendingIntent pi = pending(ACTION_EXPIRE, 1002);
        try {
            alarmManager.cancel(pi);
            long expire = prefs().getLong(K_EXPIRE, 0);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expire, pi);
        } catch (SecurityException e) {
            Log.w(TAG, "scheduleExpireAlarm blocked", e);
        } catch (Exception e) {
            Log.w(TAG, "scheduleExpireAlarm failed", e);
        }
    }

    private void cancelActivateAlarm() {
        if (alarmManager != null) {
            alarmManager.cancel(pending(ACTION_ACTIVATE, 1001));
        }
    }

    private void cancelExpireAlarm() {
        if (alarmManager != null) {
            alarmManager.cancel(pending(ACTION_EXPIRE, 1002));
        }
    }

    private PendingIntent pending(String action, int requestCode) {
        Intent intent = new Intent(action);
        intent.setClass(app, BlockSessionAlarmReceiver.class);
        intent.putExtra("trigger", ACTION_ACTIVATE.equals(action) ? TRIGGER_ACTIVATE : TRIGGER_EXPIRE);
        intent.setPackage(app.getPackageName());
        return PendingIntent.getBroadcast(app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void clearLockTaskConfigurationQuietly() {
        try {
            new DeviceManagementManager(app).clearLockTaskConfiguration();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // سجل التدقيق
    // ------------------------------------------------------------------

    public synchronized void audit(String event) {
        SharedPreferences p = prefs();
        String stamp = DateFormat.format("MM-dd HH:mm:ss", new Date()).toString();
        String line = "[" + stamp + "] " + event;
        String current = p.getString(K_AUDIT, "");
        List<String> lines = new ArrayList<>();
        if (!current.isEmpty()) {
            for (String s : current.split("\n")) {
                if (!s.trim().isEmpty()) {
                    lines.add(s);
                }
            }
        }
        lines.add(0, line);
        while (lines.size() > AUDIT_MAX_LINES) {
            lines.remove(lines.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        p.edit().putString(K_AUDIT, sb.toString()).apply();
        Log.i(TAG, event);
    }

    public synchronized List<String> auditLog() {
        String current = prefs().getString(K_AUDIT, "");
        List<String> out = new ArrayList<>();
        if (current != null) {
            for (String s : current.split("\n")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    public void setRestoredAfterBoot() {
        prefs().edit().putBoolean(K_RESTORED, true).apply();
    }
}