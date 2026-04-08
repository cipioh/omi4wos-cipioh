package com.omi4wos.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.omi4wos.mobile.service.WatchReceiverService
import com.omi4wos.mobile.ui.MobileApp

class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "MainActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the persistent foreground service that receives watch messages
        ContextCompat.startForegroundService(
            this, Intent(this, WatchReceiverService::class.java)
        )
        requestBatteryOptimizationExemption()
        setContent {
            MobileApp()
        }
    }

    /**
     * Request battery optimization exemption so Samsung's FreecessHandler
     * doesn't freeze this process and block GMS from delivering watch messages.
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption")
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not show battery optimization dialog, opening settings", e)
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not open battery settings", e2)
                }
            }
        } else {
            Log.i(TAG, "Already exempt from battery optimization")
        }
    }
}
