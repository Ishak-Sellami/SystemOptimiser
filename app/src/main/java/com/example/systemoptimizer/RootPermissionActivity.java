package com.example.systemoptimizer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class RootPermissionActivity extends Activity {
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
                finish();
            }
        });
    }
    
    private void requestRootAccess() {
        rootManager.requestRootPermission(new RootManager.RootCallback() {
            @Override
            public void onRootGranted(boolean granted) {
                if (granted) {
                    Toast.makeText(RootPermissionActivity.this, "تم منح صلاحيات الروت بنجاح", Toast.LENGTH_SHORT).show();
                    
                    // الانتقال إلى الشاشة الرئيسية للتطبيق
                    Intent intent = new Intent(RootPermissionActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    Toast.makeText(RootPermissionActivity.this, "فشل في الحصول على صلاحيات الروت", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}