package com.remotemonitor

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        WebServer.addLog("D", "Admin", "Device admin enabled")
    }
    override fun onDisabled(context: Context, intent: Intent) {
        WebServer.addLog("D", "Admin", "Device admin disabled")
    }
}
