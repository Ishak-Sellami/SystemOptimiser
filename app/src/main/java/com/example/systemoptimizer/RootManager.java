package com.example.systemoptimizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
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
    
    public void requestRootPermission(RootCallback callback) {
        if (isRootGranted()) {
            callback.onRootGranted(true);
            return;
        }
        
        // التحقق من وجود صلاحيات الروت
        if (!Shell.SU.available()) {
            callback.onRootGranted(false);
            return;
        }
        
        // منح صلاحية الروت مرة واحدة
        Shell.SU.run("echo 'Root access granted'");
        
        // حفظ حالة منح الصلاحيات
        prefs.edit().putBoolean(ROOT_GRANTED_KEY, true).apply();
        
        callback.onRootGranted(true);
    }
    
    public interface RootCallback {
        void onRootGranted(boolean granted);
    }
}