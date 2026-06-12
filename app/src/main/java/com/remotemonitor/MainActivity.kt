package com.remotemonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_PERMISSIONS = 1002
        private const val REQUEST_BATTERY = 1003
        private const val PORT = 8080
    }

    private lateinit var statusText: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var startButton: Button
    private lateinit var infoText: TextView

    private var pendingResultCode = -1
    private var pendingData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        connectionInfo = findViewById(R.id.connectionInfo)
        startButton = findViewById(R.id.startButton)
        infoText = findViewById(R.id.infoText)

        startButton.setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        if (ScreenMirrorService.isRunning) {
            showRunningState()
        } else {
            showIdleState()
        }
    }

    private fun onStartClicked() {
        if (ScreenMirrorService.isRunning) {
            stopService()
            return
        }
        checkPermissions()
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), REQUEST_PERMISSIONS
            )
            return
        }

        checkBatteryOptimization()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.filterIndexed { i, _ ->
                grantResults[i] != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isNotEmpty()) {
                infoText.text = "Missing permissions: ${denied.joinToString(", ")}"
                Toast.makeText(this, "All permissions required for full functionality",
                    Toast.LENGTH_LONG).show()
                return
            }
            checkBatteryOptimization()
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_BATTERY)
            } catch (_: Exception) {
                startMediaProjection()
            }
            return
        }
        startMediaProjection()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    pendingResultCode = resultCode
                    pendingData = data
                    doStartService()
                } else {
                    infoText.text = "Screen capture permission denied"
                }
            }
            REQUEST_BATTERY -> {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    infoText.text = "Battery optimization not disabled - service may be killed"
                }
                startMediaProjection()
            }
        }
    }

    private fun startMediaProjection() {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            infoText.text = "Failed to start screen capture: ${e.message}"
        }
    }

    private fun doStartService() {
        val intent = Intent(this, ScreenMirrorService::class.java).apply {
            action = ScreenMirrorService.ACTION_START
            putExtra("resultCode", pendingResultCode)
            putExtra("data", pendingData)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            showRunningState()
        } catch (e: Exception) {
            infoText.text = "Failed to start: ${e.message}"
            Toast.makeText(this, "Service start failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRunningState() {
        statusText.text = "Status: Running"
        statusText.setTextColor(0xFF4CAF50.toInt())
        startButton.text = "Stop Service"

        val localIp = getLocalIpAddress()
        val urls = mutableListOf("http://localhost:$PORT")
        if (localIp != null) urls.add("http://$localIp:$PORT")
        connectionInfo.text = urls.joinToString("\n")
        infoText.text = "Enable Accessibility in Settings to control from browser"
    }

    private fun showIdleState() {
        statusText.text = "Status: Not Running"
        statusText.setTextColor(0xFFF44336.toInt())
        startButton.text = "Start Service"
        connectionInfo.text = ""
        infoText.text = "Tap Start to begin"
    }

    private fun stopService() {
        try {
            startService(Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_STOP
            })
        } catch (_: Exception) {}
        showIdleState()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
