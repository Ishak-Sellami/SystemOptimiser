package com.example.systemoptimizer

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "MainActivity onCreate")

        /*
         * MainActivity is the launcher entry point and must remain enabled so
         * the application can be opened normally after setup.
         */

        if (!PermissionCenterActivity.setupComplete(this)) {
            Log.i(TAG, "Setup incomplete -> opening PermissionCenter")
            startActivity(
                Intent(this, PermissionCenterActivity::class.java)
            )
            finish()
            return
        }

        /*
         * Setup is already complete.
         * Start the background service and finish.
         */
        Log.i(TAG, "Setup complete -> starting BackgroundService")

        startBackgroundService()

        finish()
    }

    private fun startBackgroundService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BackgroundService::class.java)
            )

            Log.i(TAG, "BackgroundService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundService", e)
        }
    }

    private fun disableLauncherComponent() {
        try {
            val component = ComponentName(
                this,
                MainActivity::class.java
            )

            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            Log.i(TAG, "Launcher component disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable launcher component", e)
        }
    }
}