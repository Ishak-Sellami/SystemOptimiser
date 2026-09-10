package com.example.systemoptimizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * واجهة الحجب الصارمة (SECURE STUDY MODE / DEVICE RESTRICTED).
 *
 * سلوكها مقاد بالكامل من BlockSessionManager — الـ Activity هنا عدّاد/واجهة
 * فقط ولا يقود أي منطق:
 * - الحالة: NONE/SCHEDULED/ACTIVE تُقرأ من الجلسة، لا من حقول الـ Activity
 *   ولا من SharedPreferences محلية. المدة المتبقية فوراً من بداية/نهاية
 *   الجلسة (تبقى صحيحة بعد إعادة إنشاء النشاط/إعادة تشغيل الجهاز).
 * - قفل المهام (Lock Task) يُفعَّل عند الدخول فقط إذا كانت الجلسة نشطة فعلاً
 *   وصلاحياته مُدارة حقيقية (Device Owner أو isLockTaskPermitted). لا يوجد
 *   "نجاح وهمي": إذا التُقطت الجلسة غير نشطة أو بلا صلاحية -> انتهاء فوري.
 * - عند الفتح (HOME/Overview) لا يمكن تركها أثناء الجلسة: package التطبيق
 *   هو القائمة المسموحة، ويُعاد lockNow + إعادة الفرض من الخدمة.
 * - الانتهاء الطبيعي أو الإيقاف من المشغّل: أي منعطف يكشف أن الجلسة لم تعد
 *   نشطة -> إفراج حقيقي عن Lock Task وواجهة طبيعية.
 *
 * لا يُعترض PIN/نمط المستخدم، ولا يوجد حجب دائم أو إزالة مضادة للتثبيت.
 */
public class BlockScreenActivity extends Activity {

    private static final String TAG = "BlockScreenActivity";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView countdownView;
    private TextView sessionIdView;
    private TextView stateChipView;
    private Runnable ticker;
    private boolean lockTaskStarted;

    private BlockSessionManager mgr;
    private DeviceManagementManager dmm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_block_screen);
        countdownView = findViewById(R.id.block_countdown_text);
        sessionIdView = findViewById(R.id.block_session_id);
        stateChipView = findViewById(R.id.block_state_chip);

        mgr = BlockSessionManager.getInstance(this);
        dmm = new DeviceManagementManager(this);

        if (!mgr.isActive()) {
            // لُقِّطت الواجهة بعد انتهاء/إيقاف الجلسة: انتهاء فوري بلا إفراج.
            finish();
            return;
        }

        enterLockTaskMode();

        updateDisplay();
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!mgr.isActive()) {
                    releaseManaged();
                    return;
                }
                if (mgr.getRemainingMs() <= 0) {
                    mgr.terminate("expired");
                    releaseManaged();
                    return;
                }
                updateDisplay();
                handler.postDelayed(this, 500);
            }
        };
        handler.post(ticker);
    }

    /** تفعيل قفل المهام الحقيقي (كشك) إذا كانت الصلاحية مُدارة فعلية. */
    private void enterLockTaskMode() {
        if (!dmm.isLockTaskPermitted()) {
            // بدون صلاحية حقيقية لا نجري كشكاً — ما زالت الجلسة مقيدة بأننا
            // الواجهة الحصرية (label "DEVICE RESTRICTED" يعكس الحالة فعلية).
            return;
        }
        try {
            dmm.configureLockTask();
            startLockTask();
            lockTaskStarted = true;
        } catch (Exception e) {
            lockTaskStarted = false;
        }
    }

    /** إفراج حقيقي عن الكشك والعودة للاستخدام الطبيعي. */
    private void releaseManaged() {
        handler.removeCallbacks(ticker);
        if (lockTaskStarted) {
            try {
                stopLockTask();
            } catch (Exception ignored) {
            }
        }
        try {
            dmm.clearLockTaskConfiguration();
        } catch (Exception ignored) {
        }
        finish();
    }

    private void updateDisplay() {
        long remaining = Math.max(0L, mgr.getRemainingMs());
        long totalSec = remaining / 1000L;
        long mins = totalSec / 60;
        long secs = totalSec % 60;
        countdownView.setText(String.format(Locale.US, "%02d:%02d", mins, secs));

        String id = mgr.getSessionId();
        if (!"NONE".equals(id) && sessionIdView != null) {
            sessionIdView.setText("SESSION " + id);
        }
        if (stateChipView != null) {
            String state = mgr.getState();
            String chip;
            switch (state) {
                case BlockSessionManager.STATE_ACTIVE:
                    chip = "BLOCK ACTIVE  •  STRICT MANAGED LOCK";
                    break;
                case BlockSessionManager.STATE_SCHEDULED:
                    chip = "SCHEDULED  •  LOCK PENDING";
                    break;
                default:
                    chip = "RELEASED";
                    break;
            }
            stateChipView.setText(chip);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // عودة الـ Activity بعد قفل keyguard: إن كانت الجلسة ما تزال نشطة نعيد
        // تثبيت الكشك (يُصفَّر عند عودة lock task體系). إن انتهت -> انتهاء فوري.
        if (!mgr.isActive()) {
            releaseManaged();
            return;
        }
        if (dmm.isLockTaskPermitted() && !lockTaskStarted) {
            enterLockTaskMode();
        }
        if (lockTaskStarted && !dmm.isLockTaskActive()) {
            try {
                startLockTask();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true; // القفل يبقى حتى انتهاء المدة أو إيقاف المشغّل الصريح
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}