package com.remotemonitor

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "boot_reminder",
                    "Boot Reminder",
                    NotificationManager.IMPORTANCE_LOW
                )
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)

                val notification = android.app.Notification.Builder(context, "boot_reminder")
                    .setContentTitle("Remote Monitor")
                    .setContentText("Open app to start screen mirroring")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build()

                nm.notify(1002, notification)
            }
        }
    }
}
