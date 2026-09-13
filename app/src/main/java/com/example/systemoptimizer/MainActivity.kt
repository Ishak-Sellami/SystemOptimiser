package com.example.systemoptimizer

import android.content.Intent
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

        Log.i(TAG, "MainActivity onCreate started")

        // Check if permission setup is complete
        if (!PermissionCenterActivity.setupComplete(this)) {
            Log.i(TAG, "Permission setup incomplete - Switching to PermissionCenterActivity")
            startActivity(Intent(this, PermissionCenterActivity::class.java))
            finish()
            return
        }

        // If setup is complete, start the service
        Log.i(TAG, "Permission setup complete - Starting BackgroundService")
        startApp()
    }

    private fun startApp() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BackgroundService::class.java)
        )
        finish() // MainActivity is just a launcher
    }
}