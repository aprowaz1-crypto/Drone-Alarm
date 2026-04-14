package com.dronealarm.ua

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dronealarm.ua.service.MonitorForegroundService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("drone_alarm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start_on_boot", false)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorForegroundService::class.java).setAction(MonitorForegroundService.ACTION_START)
            )
        }
    }
}
