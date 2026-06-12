package com.remotemonitor

import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.Camera.Parameters
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamer {

    private var camera: Camera? = null
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var jpegData: ByteArray? = null
    private val clients = mutableListOf<Socket>()

    fun start(cameraId: Int = CameraInfo.CAMERA_FACING_FRONT) {
        if (running.getAndSet(true)) return
        try {
            camera = Camera.open(cameraId)
            val params = camera?.parameters ?: run { stop(); return }
            params.setPreviewFormat(android.graphics.ImageFormat.NV21)
            val sizes = params.supportedPreviewSizes
            val size = sizes?.minByOrNull {
                Math.abs(it.width - 480) + Math.abs(it.height - 360)
            } ?: return
            params.setPreviewSize(size.width, size.height)
            params.setJpegQuality(50)
            camera?.parameters = params
            camera?.startPreview()

            thread = HandlerThread("camera-capture").apply { start() }
            handler = Handler(thread!!.looper)

            camera?.setPreviewCallback(object : Camera.PreviewCallback {
                override fun onPreviewFrame(data: ByteArray?, cam: Camera?) {
                    if (!running.get() || data == null) return
                    try {
                        val p = cam?.parameters ?: return
                        val ps = p.previewSize
                        val yuv = YuvImage(data, p.previewFormat, ps.width, ps.height, null)
                        val out = ByteArrayOutputStream()
                        yuv.compressToJpeg(Rect(0, 0, ps.width, ps.height), 50, out)
                        val jpeg = out.toByteArray()
                        synchronized(this@CameraStreamer) { jpegData = jpeg }
                        broadcastFrame(jpeg)
                    } catch (_: Exception) {}
                }
            })

            WebServer.addLog("D", "Camera", "Started (facing=$cameraId) ${size.width}x${size.height}")
        } catch (e: Exception) {
            WebServer.addLog("E", "Camera", "Start failed: ${e.message}")
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {}
        camera = null
        thread?.quitSafely()
        thread = null
        handler = null
        synchronized(clients) {
            for (s in clients) { try { s.close() } catch (_: Exception) {} }
            clients.clear()
        }
        jpegData = null
    }

    fun addClient(socket: Socket) {
        synchronized(clients) { clients.add(socket) }
    }

    fun removeClient(socket: Socket) {
        synchronized(clients) { clients.remove(socket) }
    }

    fun isRunning(): Boolean = running.get()

    fun getLastFrame(): ByteArray? = synchronized(this@CameraStreamer) { jpegData }

    private fun broadcastFrame(data: ByteArray) {
        val header = "\r\n--CAM_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${data.size}\r\n\r\n"
        synchronized(clients) {
            val iter = clients.iterator()
            while (iter.hasNext()) {
                val s = iter.next()
                try {
                    val os = s.getOutputStream()
                    os.write(header.toByteArray())
                    os.write(data)
                    os.flush()
                } catch (_: Exception) {
                    iter.remove()
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }
    }
}
