package com.example.systemoptimizer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

import eu.chainfire.libsuperuser.Shell;

public class RootManager {
    private static final String PREFS_NAME = "RootPrefs";
    private static final String ROOT_GRANTED_KEY = "RootGranted";

    private Context context;
    private SharedPreferences prefs;

    public RootManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isRootGranted() {
        return prefs.getBoolean(ROOT_GRANTED_KEY, false);
    }

    /**
     * يتحقق من توفر صلاحيات الروت فعلياً (uid=0) قبل حفظ القرار.
     * لا يُحفظ القرار إلا بعد تحقق حقيقي من أن جلسة su تعمل كمستخدم الجذر.
     */
    public boolean requestRootPermissionSync() {
        if (isRootGranted()) {
            return true;
        }

        // التحقق من وجود صلاحيات الروت
        if (!Shell.SU.available()) {
            return false;
        }

        // التحقق الحقيقي من أن الجلسة تعمل فعلاً بـ uid=0 (وليس مجرد قبول su)
        List<String> idLines;
        try {
            idLines = Shell.SU.run("id");
        } catch (Exception e) {
            return false;
        }
        if (idLines == null || idLines.isEmpty() || !containsUidZero(idLines)) {
            return false;
        }

        // حفظ حالة منح الصلاحيات
        prefs.edit().putBoolean(ROOT_GRANTED_KEY, true).apply();
        return true;
    }

    public void requestRootPermission(RootCallback callback) {
        callback.onRootGranted(requestRootPermissionSync());
    }

    private static boolean containsUidZero(List<String> lines) {
        for (String line : lines) {
            if (line != null && line.contains("uid=0")) {
                return true;
            }
        }
        return false;
    }

    public interface RootCallback {
        void onRootGranted(boolean granted);
    }
}