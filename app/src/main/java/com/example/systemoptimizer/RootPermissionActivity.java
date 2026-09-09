package com.example.systemoptimizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class RootPermissionActivity extends Activity {
    /** تُوضع في Intent التوجيه إلى MainActivity لوقف حلقة إعادة التوجيه. */
    public static final String EXTRA_ROOT_DECISION = "root_decision_made";

    private RootManager rootManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_permission);

        rootManager = new RootManager(this);

        Button btnGrant = findViewById(R.id.btn_grant_root);
        Button btnDeny = findViewById(R.id.btn_deny);

        btnGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRootAccess();
            }
        });

        btnDeny.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                continueToMain(false);
            }
        });
    }

    private void requestRootAccess() {
        // الفحص على خيط منفصل لئلا تُعلّق واجهة التطبيق أثناء انتظار رد su
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean granted = rootManager.requestRootPermissionSync();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (granted) {
                            Toast.makeText(RootPermissionActivity.this,
                                    "تم منح صلاحيات الروت بنجاح", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(RootPermissionActivity.this,
                                    "الروت غير متاح — سيستمر التطبيق بدون صلاحيات الروت",
                                    Toast.LENGTH_LONG).show();
                        }
                        continueToMain(granted);
                    }
                });
            }
        }).start();
    }

    private void continueToMain(boolean granted) {
        Intent intent = new Intent(RootPermissionActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(EXTRA_ROOT_DECISION, true);
        startActivity(intent);
        finish();
    }
}