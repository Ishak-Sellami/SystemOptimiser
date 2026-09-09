package com.example.systemoptimizer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * حجب مؤقت للشاشة: يعرض شاشة سوداء بعدّاد تنازلي وينهي نفسه تلقائياً بعد انتهاء
 * المدة. حجب زمني فقط (إشراف أبوي/مختبرات) — لا يُلتف على مفاتيح النظام.
 */
public class BlockScreenActivity extends Activity {

    public static final String EXTRA_DURATION_SECONDS = "duration_seconds";
    private static final long MAX_DURATION_SECONDS = 86400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long remainingSeconds;
    private TextView countdownView;
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        remainingSeconds = getIntent().getIntExtra(EXTRA_DURATION_SECONDS, 10);
        if (remainingSeconds < 1) {
            remainingSeconds = 1;
        } else if (remainingSeconds > MAX_DURATION_SECONDS) {
            remainingSeconds = MAX_DURATION_SECONDS;
        }

        setContentView(R.layout.activity_block_screen);
        countdownView = findViewById(R.id.block_countdown_text);
        updateDisplay();

        ticker = new Runnable() {
            @Override
            public void run() {
                if (remainingSeconds > 0) {
                    remainingSeconds -= 1;
                    updateDisplay();
                }
                if (remainingSeconds <= 0) {
                    finish();
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(ticker, 1000);
    }

    private void updateDisplay() {
        long mins = remainingSeconds / 60;
        long secs = remainingSeconds % 60;
        countdownView.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // الحجب يستمر حتى تنتهي المدة المحددة
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}