package com.example.pulsar.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.pulsar.vision.GestureService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // We only care about the Boot Completed action
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("PULSAR_BOOT", "Phone restarted. Starting Pulsar Voice Service...")

            val serviceIntent = Intent(context, VoiceService::class.java)
            val serviceIntent2 = Intent(context, GestureService::class.java)

            // Starting with Android 8.0 (Oreo), we must use startForegroundService
            context.startForegroundService(serviceIntent)
            context.startForegroundService(serviceIntent2)
        }
    }
}