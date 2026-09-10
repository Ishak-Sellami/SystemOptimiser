package com.example.systemoptimizer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * شاشة تشخيص إدارة الجهاز وجلسة الحجب الصارمة.
 *
 * تعرض حقائق النظام فقط (لا أوصاف خيالية):
 *  - حالة الإدارة: DEVICE_OWNER / PROFILE_OWNER / DEVICE_ADMIN / NONE.
 *  - الصلاحيات الخاصة المتاحة فعلاً: Lock Task، lockNow، إلخ.
 *  - حالة الجلسة الحالية (المدة المتبقية، المعرف، المصدر، السجل).
 *
 * مدخلاها: مركز الصلاحيات. تعمل حتى في نسخة الإصدار (المشفرة من MainActivity).
 */
public class ManagementDiagnosticsActivity extends Activity {

    private TextView contentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_management_diagnostics);

        contentView = findViewById(R.id.md_content);
        Button refreshButton = findViewById(R.id.md_refresh);
        Button closeButton = findViewById(R.id.md_close);

        refreshButton.setOnClickListener(v -> render());
        closeButton.setOnClickListener(v -> finish());

        render();
    }

    private void render() {
        DeviceManagementManager dmm = new DeviceManagementManager(this);
        BlockSessionManager mgr = BlockSessionManager.getInstance(this);

        StringBuilder sb = new StringBuilder();

        sb.append("== إدارة الجهاز ==\n");
        Map<String, String> diag = dmm.diagnostics();
        for (Map.Entry<String, String> e : diag.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }

        sb.append("\n== جلسة الحجب الصارمة ==\n");
        Map<String, String> session = mgr.sessionInfo();
        for (Map.Entry<String, String> e : session.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        sb.append("Strict capability: ")
                .append(dmm.strictCapabilityAvailable() ? "AVAILABLE" : "NOT AVAILABLE")
                .append("\n");
        sb.append("Block screen launched by system (ActivityManager): ")
                .append(dmm.isLockTaskActive() ? "YES (lock task)" : "NO")
                .append("\n");

        sb.append("\n== سجل التدقيق (الأحدث أولاً) ==\n");
        List<String> audit = mgr.auditLog();
        if (audit.isEmpty()) {
            sb.append("(فارغ)\n");
        } else {
            for (String line : audit) {
                sb.append(line).append("\n");
            }
        }

        contentView.setText(sb.toString());
    }
}