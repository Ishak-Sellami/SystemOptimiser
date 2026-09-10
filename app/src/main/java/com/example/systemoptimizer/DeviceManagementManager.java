package com.example.systemoptimizer;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * طبقة قدرات إدارة الجهاز — واجهة واحدة فوق DevicePolicyManager ونظام Android.
 *
 * تمييز صريح بين المستويات (ليست مترادفة، ولا يُدَّعى أحدها دون تأكيد النظام):
 *   A  تطبيق عادي   — لا سلطة إدارة.
 *   B  Device Administrator — Admin مفعّل لكنه ليس جهازاً مُداراً.
 *   C  Device Owner / Profile Owner — جهاز مُدار (منظَّم عبر adb / وضع DPC).
 *   D  Lock Task مسموح للحزمة (DevicePolicyManager.isLockTaskPermitted).
 *   E  Lock Task فعال الآن (ActivityManager.isInLockTaskMode يؤكد ذلك).
 *
 * الصرامة (Strict managed lock) غير متاحة إلا إذا كانت Lock Task مضمونة:
 * Device Owner يستطيع إدراج حزمة Lock Task بنفسه؛ والإدارية لوحدها لا تكفي
 * (setLockTaskPackages تتطلب Device/Profile Owner). إن لم يتحقق الشرط، تُعاد
 * رسالة تشخيص صادقة ولا يُزوَّر نجاح أبداً.
 */
public final class DeviceManagementManager {

    public static final String CAP_DEVICE_OWNER = "DEVICE_OWNER";
    public static final String CAP_PROFILE_OWNER = "PROFILE_OWNER";
    public static final String CAP_DEVICE_ADMIN = "DEVICE_ADMIN";
    public static final String CAP_NONE = "NONE";

    private static final String TAG = "DeviceMgmt";

    private final Context app;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;

    public DeviceManagementManager(Context context) {
        this.app = context.getApplicationContext();
        this.dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.admin = new ComponentName(app, DeviceAdminReceiver.class);
    }

    private void log(String what, Exception e) {
        Log.w(TAG, what + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** B — Device Administrator مفعّل فعلاً. */
    public boolean isDeviceAdminActive() {
        if (dpm == null) {
            return false;
        }
        try {
            return dpm.isAdminActive(admin);
        } catch (SecurityException e) {
            log("isAdminActive", e);
            return false;
        }
    }

    /** C — جهاز مُدار بالكامل (Device Owner). */
    public boolean isDeviceOwner() {
        if (dpm == null) {
            return false;
        }
        try {
            return dpm.isDeviceOwnerApp(app.getPackageName());
        } catch (SecurityException e) {
            log("isDeviceOwnerApp", e);
            return false;
        }
    }

    /** C (بديل) — Profile Owner (جهاز مُدار على مستوى ملف تعريف العمل). */
    public boolean isProfileOwner() {
        if (dpm == null) {
            return false;
        }
        try {
            return dpm.isProfileOwnerApp(app.getPackageName());
        } catch (SecurityException e) {
            log("isProfileOwnerApp", e);
            return false;
        }
    }

    /** D — هل Lock Task مسموح لهذه الحزمة الآن؟ */
    public boolean isLockTaskPermitted() {
        if (dpm == null) {
            return false;
        }
        try {
            return dpm.isLockTaskPermitted(app.getPackageName());
        } catch (SecurityException e) {
            log("isLockTaskPermitted", e);
            return false;
        }
    }

    /** E — هل النظام يؤكد أن Lock Task نشطة الآن؟ */
    public boolean isLockTaskActive() {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isInLockTaskMode();
        } catch (Exception e) {
            log("isLockTaskActive", e);
            return false;
        }
    }

    /** إدراج الحزمة ضمن حزمة Lock Task — فقط Device Owner/Profile Owner قانونياً. */
    public boolean configureLockTask() {
        if (dpm == null) {
            return false;
        }
        try {
            dpm.setLockTaskPackages(admin, new String[]{app.getPackageName()});
            return true;
        } catch (Exception e) {
            log("setLockTaskPackages", e);
            return false;
        }
    }

    /** إزالة الحزمة من Lock Task (عند انتهاء الجلسة). */
    public boolean clearLockTaskConfiguration() {
        if (dpm == null) {
            return false;
        }
        try {
            dpm.setLockTaskPackages(admin, new String[]{});
            return true;
        } catch (Exception e) {
            log("clearLockTaskPackages", e);
            return false;
        }
    }

    /**
     * هل الإدارة الصارمة متاحة؟ Lock Task المضمونة فقط.
     * لا يُدَّعى Strict إلا إذا أكدت واجهات النظام القدرة.
     */
    public boolean strictCapabilityAvailable() {
        return isDeviceOwner() || (isDeviceAdminActive() && isLockTaskPermitted());
    }

    /** قفل Keyguard بالنظام — يحتاج Device Owner أو Admin مفعّلاً. */
    public boolean lockNow() {
        if (dpm == null || !(isDeviceOwner() || isDeviceAdminActive())) {
            return false;
        }
        try {
            dpm.lockNow();
            return true;
        } catch (SecurityException e) {
            log("lockNow", e);
            return false;
        }
    }

    /**
     * حماية الإدارة من الإزالة عبر واجهات النظام:
     * Device Owner لا يمكن إلغاء تثبيته من إعدادات الجهاز حتى تُزال صفة
     * الإدارة (dpm remove-active-admin). Admin لوحده قابل للإزالة عادة.
     */
    public boolean managementProtectionActive() {
        return isDeviceOwner() || isProfileOwner();
    }

    /** تسمية مستوى القيادة للتقارير. */
    public String capabilityLabel() {
        if (isDeviceOwner()) {
            return CAP_DEVICE_OWNER;
        }
        if (isProfileOwner()) {
            return CAP_PROFILE_OWNER;
        }
        if (isDeviceAdminActive()) {
            return CAP_DEVICE_ADMIN;
        }
        return CAP_NONE;
    }

    /** تشخيص كامل (بدون حالة الجلسة). */
    public Map<String, String> diagnostics() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Device Management", capabilityLabel());
        map.put("Device Owner", isDeviceOwner() ? "YES" : "NO");
        map.put("Profile Owner", isProfileOwner() ? "YES" : "NO");
        map.put("Device Admin", isDeviceAdminActive() ? "ACTIVE" : "INACTIVE");
        map.put("Lock Task Permitted", isLockTaskPermitted() ? "YES" : "NO");
        map.put("Lock Task Active", isLockTaskActive() ? "YES" : "NO");
        map.put("Management Protection", managementProtectionActive() ? "ACTIVE" : "INACTIVE");
        return map;
    }
}