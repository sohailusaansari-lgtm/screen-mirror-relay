package com.remotemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "com.remotemonitor.START"
        const val ACTION_STOP = "com.remotemonitor.STOP"
        const val ACTION_MIC_START = "com.remotemonitor.MIC_START"
        const val ACTION_MIC_STOP = "com.remotemonitor.MIC_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_mirror"

        const val AUDIO_SAMPLE_RATE = 44100

        // Relay tunnel config
        var RELAY_HOST = "screen-mirror-relay-quod.onrender.com"
        var RELAY_PORT = 443
        var RELAY_ENABLED = true

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var micEnabled = false
            private set

        private val frameLock = Any()
        private var lastFrame: ByteArray? = null
        private var frameWidth = 0
        private var frameHeight = 0

        fun getLastFrame(): Triple<ByteArray?, Int, Int> {
            synchronized(frameLock) {
                return Triple(lastFrame, frameWidth, frameHeight)
            }
        }

        fun setFrame(data: ByteArray, w: Int, h: Int) {
            synchronized(frameLock) {
                lastFrame = data
                frameWidth = w
                frameHeight = h
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)
    var webServer: WebServer? = null
    private var displayWidth = 0
    private var displayHeight = 0

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()

        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val stack = android.util.Log.getStackTraceString(ex)
                android.util.Log.e("RemoteMonitor", "CRASH: $stack")
                val crashFile = getCrashLogFile()
                crashFile.parentFile?.mkdirs()
                crashFile.appendText("${System.currentTimeMillis()}: $stack\n")
            } catch (_: Exception) {}
            previousCrashHandler?.uncaughtException(thread, ex)
        }

        createNotificationChannel()
        webServer = WebServer(this)

        // Apply relay config from companion vars
        if (RELAY_ENABLED) {
            webServer?.setRelayConfig(RELAY_HOST, RELAY_PORT)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        if (intent.action == ACTION_START) {
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }

            val resultCode = intent.getIntExtra("resultCode", android.app.Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }

            if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            acquireWakeLock()
            startCapture(resultCode, data)
            webServer?.start()
            isRunning = true

            // Periodic notification update for tunnel/relay URL
            Thread {
                while (isRunning) {
                    try {
                        Thread.sleep(5000)
                        val tunnelUrl = webServer?.getTunnelUrl()
                        val relayUrl = webServer?.getRelayUrl()
                        val url = relayUrl ?: tunnelUrl
                        updateNotification(micEnabled, url)
                    } catch (_: Exception) { break }
                }
            }.apply { isDaemon = true; name = "notif-updater"; start() }

            return START_STICKY
        }

        if (intent.action == ACTION_STOP) {
            stopAudioCapture()
            stopCapture()
            webServer?.stop()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_MIC_START && !micEnabled && isRunning) {
            startAudioCapture()
        }

        if (intent.action == ACTION_MIC_STOP) {
            stopAudioCapture()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopAudioCapture()
        stopCapture()
        webServer?.stop()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RemoteMonitor:ServiceLock"
            )
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val metrics = getScreenMetrics()

        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, null)
        } catch (e: Exception) {
            stopSelf()
            return
        }

        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenMirrorDisplay",
            displayWidth, displayHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        if (virtualDisplay == null) {
            stopSelf()
            return
        }

        running.set(true)
        captureThread = Thread {
            var lastFrameTime = 0L

            while (running.get()) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        try {
                            val bitmap = imageToBitmap(image)
                            val now = System.currentTimeMillis()
                            val ws = webServer

                            // Read dynamic settings from WebServer
                            val quality = (ws?.streamQuality ?: 40).coerceIn(10, 90)
                            val fps = (ws?.streamFps ?: 8).coerceIn(1, 30)
                            val maxW = ws?.streamMaxWidth ?: 960

                            val minFrameInterval = (1000 / fps).toLong()

                            // Scale down if maxW > 0
                            val scale = if (maxW > 0 && bitmap.width > maxW) {
                                minOf(maxW.toFloat() / bitmap.width, (maxW * 0.75f) / bitmap.height, 1f)
                            } else 1f
                            val scaledBitmap = if (scale < 1f) {
                                Bitmap.createScaledBitmap(bitmap,
                                    (bitmap.width * scale).toInt(),
                                    (bitmap.height * scale).toInt(), true)
                            } else bitmap

                            val stream = ByteArrayOutputStream()
                            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            val jpeg = stream.toByteArray()
                            setFrame(jpeg, scaledBitmap.width, scaledBitmap.height)
                            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                            bitmap.recycle()

                            // Throttle frame rate for relay
                            val elapsed = now - lastFrameTime
                            if (elapsed >= minFrameInterval) {
                                lastFrameTime = now
                                ws?.broadcastFrame()
                            }
                        } catch (_: Exception) {
                        } finally {
                            image.close()
                        }
                    }
                } catch (_: Exception) {}
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "capture-thread"
            start()
        }
    }

    private fun stopCapture() {
        running.set(false)
        captureThread?.interrupt()
        captureThread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        synchronized(frameLock) { lastFrame = null }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            if (display != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    metrics.widthPixels = bounds.width()
                    metrics.heightPixels = bounds.height()
                    metrics.densityDpi = resources.displayMetrics.densityDpi
                } else {
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                }
            } else {
                resources.displayMetrics.let {
                    metrics.widthPixels = it.widthPixels
                    metrics.heightPixels = it.heightPixels
                    metrics.densityDpi = it.densityDpi
                }
            }
        } catch (_: Exception) {
            resources.displayMetrics.let {
                metrics.widthPixels = it.widthPixels
                metrics.heightPixels = it.heightPixels
                metrics.densityDpi = it.densityDpi
            }
        }
        return metrics
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (rowStride == width * pixelStride) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val pixels = IntArray(width * height)
            val rowData = ByteArray(rowStride)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowData)
                for (col in 0 until width) {
                    val idx = col * pixelStride
                    val r = rowData[idx].toInt() and 0xFF
                    val g = rowData[idx + 1].toInt() and 0xFF
                    val b = rowData[idx + 2].toInt() and 0xFF
                    val a = if (idx + 3 < rowData.size) rowData[idx + 3].toInt() and 0xFF else 0xFF
                    pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        return bitmap
    }

    fun startAudioCapture() {
        if (audioRunning.get()) return

        val bufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize <= 0) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            audioRunning.set(true)
            micEnabled = true
            updateNotification(true)

            audioThread = Thread {
                val buffer = ByteArray(bufferSize)
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                    )
                } catch (_: Exception) {}

                while (audioRunning.get()) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            val audioData = buffer.copyOf(read)
                            webServer?.broadcastAudio(audioData)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }.apply {
                isDaemon = true
                name = "audio-capture"
                start()
            }
        } catch (_: Exception) {
            audioRecord = null
        }
    }

    fun stopAudioCapture() {
        audioRunning.set(false)
        micEnabled = false
        audioThread?.interrupt()
        audioThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        webServer?.broadcastAudioStop()
        updateNotification(false)
    }

    fun updateNotification(micActive: Boolean, tunnelUrl: String? = null) {
        val notification = createNotification(micActive, tunnelUrl)
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            } catch (_: Exception) {}
        }
    }

    private fun createNotification(): Notification {
        return createNotification(false)
    }

    private fun getCrashLogFile(): java.io.File {
        return try {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                    ?: filesDir
            } else {
                val pubDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                )
                java.io.File(pubDir, "RemoteMonitor").also { it.mkdirs() }
            }
            java.io.File(base, "crash.log")
        } catch (_: Exception) {
            java.io.File(filesDir, "crash.log")
        }
    }

    private fun createNotification(micActive: Boolean, tunnelUrl: String? = null): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val micText = if (micActive) "Mic ON | " else ""
        val urlText = if (tunnelUrl != null) tunnelUrl else "Public URL: connecting..."
        val text = "${micText}Port 8080"

        return builder
            .setContentTitle("Remote Monitor")
            .setContentText(text)
            .setSubText(urlText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setStyle(Notification.BigTextStyle().bigText("$text\n$urlText"))
            .build()
    }
}
