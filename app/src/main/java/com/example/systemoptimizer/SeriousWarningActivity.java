package com.example.systemoptimizer;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * رسالة تحذير جادة بملء الشاشة (خلفية سوداء). اختيارية الإغلاق: المستخدم يغلق
 * الشاشة بالزر أو زر الرجوع. مجرد تنبيه إعلامي، وليس شاشة مقفلة.
 */
public class SeriousWarningActivity extends Activity {

    public static final String EXTRA_WARNING_TEXT = "warning_text";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_serious_warning);

        String text = getIntent().getStringExtra(EXTRA_WARNING_TEXT);
        TextView textView = findViewById(R.id.warning_text);
        if (text == null || text.trim().isEmpty()) {
            text = "تحذير جدي";
        }
        textView.setText(text);

        Button closeButton = findViewById(R.id.warning_close_button);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
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