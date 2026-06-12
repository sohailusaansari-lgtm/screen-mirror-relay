package com.remotemonitor

import android.content.Context
import android.content.Intent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

class WebServer(private val context: Context) {

    companion object {
        private const val PORT = 8080
        private const val LOG_MAX = 500

        private val logBuffer = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun addLog(level: String, tag: String, msg: String) {
            val line = "[$level][${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $tag: $msg"
            logBuffer.add(line)
            if (logBuffer.size > LOG_MAX) logBuffer.removeAt(0)
            if (level == "E") android.util.Log.e(tag, msg)
            else android.util.Log.d(tag, msg)
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var htmlBytes: ByteArray? = null
    private var firstFrameBytes: ByteArray? = null
    private val tunnelManager = TunnelManager()
    val cameraStreamer = CameraStreamer()

    // Relay tunnel
    private var relayClient: RelayClient? = null
    private var streamReqId: String? = null
    private var cameraReqId: String? = null
    private var audioReqId: String? = null

    // Stream config (adjustable from dashboard)
    @Volatile var streamQuality: Int = 40
    @Volatile var streamFps: Int = 8
    @Volatile var streamMaxWidth: Int = 960

    private fun getStableDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val baseId = if (androidId != null) androidId else java.util.UUID.randomUUID().toString()
        return "device_" + baseId.take(12)
    }

    fun setRelayConfig(host: String, port: Int) {
        relayClient?.stop()
        val stableId = getStableDeviceId()
        val client = RelayClient(host, port, android.os.Build.MODEL, stableId)
        client.onIncomingRequest = { id, method, path, headers, bodyB64 ->
            Thread {
                handleRelayRequest(id, method, path, headers, bodyB64)
            }.apply { isDaemon = true; name = "relay-proxy"; start() }
        }
        client.onStreamClosed = { id ->
            if (id == streamReqId) streamReqId = null
            if (id == cameraReqId) cameraReqId = null
            if (id == audioReqId) audioReqId = null
        }
        relayClient = client
        client.start()
    }

    fun getRelayUrl(): String? = relayClient?.publicUrl?.get()
    fun getTunnelUrl(): String? = tunnelManager.publicUrl.get()

    private val mjpegClients = mutableListOf<Socket>()
    private val audioClients = mutableListOf<Socket>()

    fun start() {
        if (running.get()) return
        running.set(true)

        try {
            htmlBytes = loadHtml()
        } catch (_: Exception) {}

        tunnelManager.start()
        // relay client started via setRelayConfig()

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (running.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread {
                            handleClient(socket)
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    } catch (e: Exception) {
                        addLog("E", "RemoteMonitor", "accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                addLog("E", "RemoteMonitor", "server thread error: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "http-server"
            start()
        }
    }

    fun stop() {
        running.set(false)
        relayClient?.stop()
        relayClient = null
        streamReqId = null
        cameraReqId = null
        audioReqId = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        synchronized(mjpegClients) {
            for (s in mjpegClients) { try { s.close() } catch (_: Exception) {} }
            mjpegClients.clear()
        }
        synchronized(audioClients) {
            for (s in audioClients) { try { s.close() } catch (_: Exception) {} }
            audioClients.clear()
        }
    }

    fun broadcastFrame() {
        val frame = ScreenMirrorService.getLastFrame()
        val jpegData = frame.first ?: return
        if (firstFrameBytes == null) firstFrameBytes = jpegData

        val header = "\r\n--FRAME_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegData.size}\r\n\r\n"
        val headerBytes = header.toByteArray()

        synchronized(mjpegClients) {
            val iter = mjpegClients.iterator()
            while (iter.hasNext()) {
                val socket = iter.next()
                try {
                    val os = socket.getOutputStream()
                    os.write(headerBytes)
                    os.write(jpegData)
                    os.flush()
                } catch (e: Exception) {
                    iter.remove()
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }

        // Push to relay for /stream
        val rid = streamReqId
        if (rid != null) {
            val rc = relayClient
            if (rc != null && rc.isConnected) {
                val data = headerBytes + jpegData
                rc.sendResponseData(rid, data)
            } else {
                streamReqId = null
            }
        }
        // Push actual camera frames to relay for /frontcam
        val cid = cameraReqId
        if (cid != null) {
            val rc = relayClient
            if (rc != null && rc.isConnected) {
                val camFrame = cameraStreamer.getLastFrame()
                if (camFrame != null) {
                    val camHeader = "\r\n--CAM_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${camFrame.size}\r\n\r\n"
                    val data = camHeader.toByteArray() + camFrame
                    rc.sendResponseData(cid, data)
                }
            } else {
                cameraReqId = null
            }
        }
    }

    fun broadcastAudio(audioData: ByteArray) {
        val len = audioData.size
        val lenBytes = byteArrayOf(
            (len shr 24).toByte(),
            (len shr 16).toByte(),
            (len shr 8).toByte(),
            len.toByte()
        )

        synchronized(audioClients) {
            val iter = audioClients.iterator()
            while (iter.hasNext()) {
                val socket = iter.next()
                try {
                    val os = socket.getOutputStream()
                    os.write(lenBytes)
                    os.write(audioData)
                    os.flush()
                } catch (e: Exception) {
                    iter.remove()
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }

        // Push to relay for /audio
        val aid = audioReqId
        if (aid != null) {
            val rc = relayClient
            if (rc != null && rc.isConnected) {
                val data = lenBytes + audioData
                rc.sendResponseData(aid, data)
            } else {
                audioReqId = null
            }
        }
    }

    fun broadcastAudioStop() {
        val aid = audioReqId
        if (aid != null) {
            relayClient?.sendResponseDone(aid)
            audioReqId = null
        }
        synchronized(audioClients) {
            val iter = audioClients.iterator()
            while (iter.hasNext()) {
                val socket = iter.next()
                try { socket.close() } catch (_: Exception) {}
                iter.remove()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            socket.setTcpNoDelay(true)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val rawHeader = readHttpRequest(input) ?: return
            val lines = rawHeader.split("\r\n")
            if (lines.isEmpty()) return

            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return

            val method = requestLine[0]
            val rawPath = URLDecoder.decode(requestLine[1], "UTF-8")
            val path = rawPath.split("?").first()

            addLog("D", "RemoteMonitor", "HTTP $method $rawPath from ${socket.inetAddress}")

            when {
                method == "GET" && path == "/stream" -> handleMjpegStream(socket, output)
                method == "GET" && path == "/audio" -> handleAudioStream(socket, output)
                method == "GET" && path == "/frontcam" -> handleCameraStream(socket, output)
                method == "GET" -> {
                    handleHttpRequest(path, output)
                    try { socket.close() } catch (_: Exception) {}
                }
                method == "POST" && path == "/control" -> {
                    handleControlRequest(rawHeader, output)
                    try { socket.close() } catch (_: Exception) {}
                }
                else -> {
                    sendHttpResponse(output, 404, "Not Found", "text/plain", "Not Found")
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            addLog("E", "RemoteMonitor", "handleClient error: ${e.javaClass.simpleName}: ${e.message}")
            try { if (!socket.isClosed) socket.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpRequest(input: InputStream): String? {
        val headerBytes = ByteArrayOutputStream()
        val last4 = ByteArray(4) { -1 }

        try {
            while (true) {
                val b = input.read()
                if (b == -1) return null
                headerBytes.write(b)
                last4[0] = last4[1]
                last4[1] = last4[2]
                last4[2] = last4[3]
                last4[3] = b.toByte()
                if (last4[0] == '\r'.code.toByte() &&
                    last4[1] == '\n'.code.toByte() &&
                    last4[2] == '\r'.code.toByte() &&
                    last4[3] == '\n'.code.toByte()) {
                    break
                }
            }
        } catch (e: Exception) {
            addLog("E", "RemoteMonitor", "readHttpRequest error: ${e.message}")
            return null
        }

        val header = headerBytes.toString(Charsets.UTF_8.name())

        val contentLength = try {
            header.lines()
                .firstOrNull { it.trim().startsWith("Content-Length:", true) }
                ?.split(":")
                ?.getOrNull(1)
                ?.trim()
                ?.toIntOrNull()
        } catch (_: Exception) { null }

        if (contentLength != null && contentLength > 0) {
            val body = ByteArray(contentLength)
            var bodyRead = 0
            while (bodyRead < contentLength) {
                val r = try { input.read(body, bodyRead, contentLength - bodyRead) } catch (_: Exception) { -1 }
                if (r == -1) break
                bodyRead += r
            }
            return header + String(body, Charsets.UTF_8)
        }

        return header
    }

    private fun handleMjpegStream(socket: Socket, output: OutputStream) {
        try {
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=FRAME_BOUNDARY\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
            output.write(responseHeader.toByteArray())

            val initialFrame = firstFrameBytes ?: ScreenMirrorService.getLastFrame().first
            if (initialFrame != null) {
                val firstPart = "--FRAME_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${initialFrame.size}\r\n\r\n"
                output.write(firstPart.toByteArray())
                output.write(initialFrame)
            }
            output.flush()

            addLog("D", "RemoteMonitor", "MJPEG stream started for ${socket.inetAddress}")

            synchronized(mjpegClients) { mjpegClients.add(socket) }

            socket.soTimeout = 0
            val sInput = socket.getInputStream()
            val buf = ByteArray(4096)
            while (sInput.read(buf) != -1) {}

            addLog("D", "RemoteMonitor", "MJPEG client disconnected")
        } catch (e: Exception) {
            if (e.message != "Socket closed") {
                addLog("E", "RemoteMonitor", "handleMjpegStream: ${e.message}")
            }
        } finally {
            synchronized(mjpegClients) { mjpegClients.remove(socket) }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleAudioStream(socket: Socket, output: OutputStream) {
        try {
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
            output.write(responseHeader.toByteArray())
            output.flush()

            addLog("D", "RemoteMonitor", "Audio stream started for ${socket.inetAddress}")

            synchronized(audioClients) { audioClients.add(socket) }

            socket.soTimeout = 0
            val sInput = socket.getInputStream()
            val buf = ByteArray(4096)
            while (sInput.read(buf) != -1) {}

            addLog("D", "RemoteMonitor", "Audio client disconnected")
        } catch (e: Exception) {
            if (e.message != "Socket closed") {
                addLog("E", "RemoteMonitor", "handleAudioStream: ${e.message}")
            }
        } finally {
            synchronized(audioClients) { audioClients.remove(socket) }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleHttpRequest(path: String, output: OutputStream) {
        when {
            path == "/" || path == "/dashboard" || path == "/index.html" -> {
                val html = htmlBytes
                if (html != null) {
                    sendHttpResponse(output, 200, "OK", "text/html; charset=utf-8", html)
                } else {
                    sendHttpResponse(output, 500, "Internal Server Error", "text/plain",
                        "Failed to load UI")
                }
            }
            path == "/status" -> {
                val json = """{"running":true,"clients":${mjpegClients.size},"mic":${ScreenMirrorService.micEnabled}}"""
                sendHttpResponse(output, 200, "OK", "application/json", json)
            }
            path == "/debug" -> {
                val html = buildDebugHtml()
                sendHttpResponse(output, 200, "OK", "text/html; charset=utf-8", html)
            }
            path == "/ips" -> {
                val ips = getLocalIps()
                val json = """{"ips":${ips.joinToString(",") { "\"$it\"" }}}"""
                sendHttpResponse(output, 200, "OK", "application/json", json)
            }
            path == "/tunnel" -> {
                val url = tunnelManager.publicUrl.get()
                val err = tunnelManager.errorMessage.get()
                val connected = tunnelManager.isConnected
                val relayUrl = relayClient?.publicUrl?.get()
                val relayId = relayClient?.deviceId?.get()
                val json = """{"connected":$connected,"url":${if (url != null) "\"$url\"" else "null"},"error":${if (err != null) "\"$err\"" else "null"},"relay_url":${if (relayUrl != null) "\"$relayUrl\"" else "null"},"relay_id":${if (relayId != null) "\"$relayId\"" else "null"}}"""
                sendHttpResponse(output, 200, "OK", "application/json", json)
            }
            path == "/relay" -> {
                val rc = relayClient
                val connected = rc?.isConnected == true
                val url = rc?.publicUrl?.get()
                val id = rc?.deviceId?.get()
                val json = """{"connected":$connected,"device_id":${if (id != null) "\"$id\"" else "null"},"url":${if (url != null) "\"$url\"" else "null"}}"""
                sendHttpResponse(output, 200, "OK", "application/json", json)
            }
            else -> {
                sendHttpResponse(output, 404, "Not Found", "text/plain", "Not Found")
            }
        }
    }

    private fun buildDebugHtml(): String {
        val logs = synchronized(logBuffer) { logBuffer.toList() }
        val logLines = logs.joinToString("\n") { "<div>$it</div>" }
        val relayUrl = relayClient?.publicUrl?.get()
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Debug</title>
<meta http-equiv="refresh" content="3">
<style>
body{background:#111;color:#0f0;font:13px monospace;padding:10px}
div{white-space:pre-wrap;word-break:break-all}
h2{color:#fff}
</style></head><body>
<h2>Remote Monitor Debug</h2>
        <p>MJPEG: ${mjpegClients.size} | Audio: ${audioClients.size} | Mic: ${ScreenMirrorService.micEnabled} | Tunnel: ${tunnelManager.publicUrl.get() ?: tunnelManager.errorMessage.get() ?: "connecting..."} | Relay: ${relayUrl ?: "off"}</p>
<hr>$logLines<hr>
<p><em>Auto-refreshes every 3s</em></p>
</body></html>"""
    }

    private fun handleControlRequest(request: String, output: OutputStream) {
        val body = request.substringAfter("\r\n\r\n")
        try {
            val json = org.json.JSONObject(body)
            val type = json.optString("type")

            when (type) {
                "mic_start" -> {
                    context.startService(
                        Intent(context, ScreenMirrorService::class.java).apply {
                            action = ScreenMirrorService.ACTION_MIC_START
                        }
                    )
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "mic_stop" -> {
                    context.startService(
                        Intent(context, ScreenMirrorService::class.java).apply {
                            action = ScreenMirrorService.ACTION_MIC_STOP
                        }
                    )
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "camera_start" -> {
                    cameraStreamer.start()
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "camera_stop" -> {
                    cameraStreamer.stop()
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "set_quality" -> {
                    streamQuality = json.optInt("value", 40).coerceIn(10, 90)
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "set_fps" -> {
                    streamFps = json.optInt("value", 8).coerceIn(1, 30)
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                "set_resolution" -> {
                    streamMaxWidth = json.optInt("value", 960).coerceIn(0, 3840)
                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
                else -> {
                    val x = json.optInt("x", -1)
                    val y = json.optInt("y", -1)
                    val x2 = json.optInt("x2", -1)
                    val y2 = json.optInt("y2", -1)
                    val duration = json.optLong("duration", 0)
                    val key = json.optString("key", "")
                    val text = json.optString("text", "")

                    val controller = InputController.instance
                    if (controller == null) {
                        sendHttpResponse(output, 200, "OK", "application/json",
                            """{"status":"error","message":"Accessibility not enabled"}""")
                        return
                    }

                    when (type) {
                        "tap" -> controller.injectTap(x, y)
                        "swipe" -> controller.injectSwipe(x, y, x2, y2, duration)
                        "key" -> controller.injectKey(key)
                        "text" -> controller.injectText(text)
                    }

                    sendHttpResponse(output, 200, "OK", "application/json",
                        """{"status":"ok"}""")
                }
            }
        } catch (e: Exception) {
            addLog("E", "RemoteMonitor", "control request error: ${e.message}")
            sendHttpResponse(output, 200, "OK", "application/json",
                """{"status":"error","message":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
        }
    }

    private fun sendHttpResponse(
        output: OutputStream,
        code: Int,
        message: String,
        contentType: String,
        body: String
    ) {
        sendHttpResponse(output, code, message, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun sendHttpResponse(
        output: OutputStream,
        code: Int,
        message: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun handleCameraStream(socket: Socket, output: OutputStream) {
        try {
            val resp = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=CAM_BOUNDARY\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n"
            output.write(resp.toByteArray())
            output.flush()

            if (!cameraStreamer.isRunning()) {
                cameraStreamer.start()
            }
            cameraStreamer.addClient(socket)
            addLog("D", "RemoteMonitor", "Camera stream started")

            socket.soTimeout = 0
            val sInput = socket.getInputStream()
            val buf = ByteArray(4096)
            while (sInput.read(buf) != -1) {}
        } catch (_: Exception) {} finally {
            cameraStreamer.removeClient(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleRelayRequest(reqId: String, method: String, path: String, headers: Map<String, String>, bodyBase64: String) {
        val rc = relayClient ?: return
        addLog("D", "Relay", "Proxy $method $path (id=$reqId)")

        when {
            // Direct test path (no localhost proxy needed)
            path == "/relay-test" -> {
                rc.sendResponseHeaders(reqId, 200, mapOf("Content-Type" to "text/plain"), streaming = false)
                rc.sendResponseData(reqId, "relay OK from ${android.os.Build.MODEL}".toByteArray(Charsets.UTF_8), last = true)
            }
            path == "/favicon.ico" -> {
                rc.sendResponseHeaders(reqId, 404, mapOf("Content-Type" to "text/plain"), streaming = false)
                rc.sendResponseData(reqId, ByteArray(0), last = true)
            }
            // Streaming endpoints
            path == "/stream" || path.startsWith("/stream?") -> {
                val respHeaders = mapOf(
                    "Content-Type" to "multipart/x-mixed-replace; boundary=FRAME_BOUNDARY",
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Pragma" to "no-cache",
                    "Connection" to "keep-alive",
                    "Access-Control-Allow-Origin" to "*"
                )
                rc.sendResponseHeaders(reqId, 200, respHeaders, streaming = true)
                streamReqId = reqId

                // Send initial frame
                val f = firstFrameBytes ?: ScreenMirrorService.getLastFrame().first
                if (f != null) {
                    val initial = "--FRAME_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n"
                    rc.sendResponseData(reqId, initial.toByteArray() + f)
                }
            }
            path == "/frontcam" || path.startsWith("/frontcam?") -> {
                if (!cameraStreamer.isRunning()) cameraStreamer.start()
                val respHeaders = mapOf(
                    "Content-Type" to "multipart/x-mixed-replace; boundary=CAM_BOUNDARY",
                    "Cache-Control" to "no-cache, no-store",
                    "Pragma" to "no-cache",
                    "Connection" to "keep-alive",
                    "Access-Control-Allow-Origin" to "*"
                )
                rc.sendResponseHeaders(reqId, 200, respHeaders, streaming = true)
                cameraReqId = reqId
            }
            path == "/audio" -> {
                val respHeaders = mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Cache-Control" to "no-cache, no-store",
                    "Connection" to "keep-alive",
                    "Access-Control-Allow-Origin" to "*"
                )
                rc.sendResponseHeaders(reqId, 200, respHeaders, streaming = true)
                audioReqId = reqId
            }
            // Non-streaming: proxy via HttpURLConnection to localhost:8080
            else -> {
                try {
                    addLog("D", "Relay", "Proxying to 127.0.0.1:8080$path")
                    val url = URL("http://127.0.0.1:8080$path")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.doInput = true
                    if (method == "POST") {
                        conn.doOutput = true
                        val body = try {
                            java.util.Base64.getDecoder().decode(bodyBase64)
                        } catch (_: Exception) {
                            ByteArray(0)
                        }
                        conn.outputStream.write(body)
                        conn.outputStream.flush()
                    }

                    val status = conn.responseCode
                    addLog("D", "Relay", "Proxy got status $status for $path")
                    val respHeaders = mutableMapOf<String, String>()
                    for ((key, values) in conn.headerFields) {
                        if (key != null) {
                            val value = values?.firstOrNull()
                            if (value != null) respHeaders[key] = value
                        }
                    }
                    respHeaders["Access-Control-Allow-Origin"] = "*"

                    val body = try { conn.inputStream.readBytes() } catch (_: Exception) { conn.errorStream?.readBytes() ?: ByteArray(0) }
                    addLog("D", "Relay", "Proxy response body size: ${body.size}")

                    rc.sendResponseHeaders(reqId, status, respHeaders, streaming = false)
                    rc.sendResponseData(reqId, body, last = true)
                    conn.disconnect()
                } catch (e: Exception) {
                    addLog("E", "Relay", "Proxy error: ${e.message}")
                    val errBody = """{"error":"${e.message?.replace("\"","'") ?: "unknown"}""""
                    rc.sendResponseHeaders(reqId, 502, mapOf("Content-Type" to "application/json"), streaming = false)
                    rc.sendResponseData(reqId, errBody.toByteArray(Charsets.UTF_8), last = true)
                }
            }
        }
    }

    private fun loadHtml(): ByteArray? {
        return try {
            context.resources.openRawResource(R.raw.dashboard).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun getLocalIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }
}
