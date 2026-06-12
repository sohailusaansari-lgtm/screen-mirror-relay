package com.remotemonitor

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RelayClient(private val relayHost: String, private val relayPort: Int, private val deviceName: String, private val stableDeviceId: String = "") {

    // Dedicated lock for wsOutput writes (multiple threads call sendTextMessage / sendPongFrame)
    private val writeLock = Any()

    companion object {
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
        fun bytesToHex(bytes: ByteArray): String {
            val hex = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                hex[i * 2] = HEX_CHARS[v ushr 4]
                hex[i * 2 + 1] = HEX_CHARS[v and 0xF]
            }
            return String(hex)
        }
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var thread: Thread? = null

    val deviceId = AtomicReference<String?>(null)
    val publicUrl = AtomicReference<String?>(null)
    val isConnected: Boolean get() = socket?.isConnected == true && running.get()

    // Callbacks set from WebServer
    var onIncomingRequest: ((requestId: String, method: String, path: String, headers: Map<String, String>, bodyBase64: String) -> Unit)? = null
    var onStreamClosed: ((requestId: String) -> Unit)? = null

    // Active streaming requests (to know when frames/audio should be sent)
    val activeStreams = ConcurrentHashMap.newKeySet<String>() // request IDs that are streaming

    private var wsOutput: OutputStream? = null
    private var wsInput: InputStream? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread {
            while (running.get()) {
                try {
                    connect()
                } catch (e: Exception) {
                    WebServer.addLog("E", "Relay", "Connection error: ${e.message}")
                }
                if (running.get()) {
                    try { Thread.sleep(10000) } catch (_: InterruptedException) { break }
                }
            }
        }.apply {
            isDaemon = true
            name = "relay-client"
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        disconnect()
        deviceId.set(null)
        publicUrl.set(null)
        activeStreams.clear()
    }

    private fun connect() {
        WebServer.addLog("D", "Relay", "Connecting to $relayHost:$relayPort...")

        val sock = if (relayPort == 443) {
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            sslContext.socketFactory.createSocket(relayHost, relayPort) as java.net.Socket
        } else {
            Socket(relayHost, relayPort)
        }
        socket = sock
        sock.keepAlive = true
        sock.tcpNoDelay = true
        wsOutput = sock.getOutputStream()
        wsInput = sock.getInputStream()

        // WebSocket handshake
        val random = SecureRandom()
        val keyBytes = ByteArray(16)
        random.nextBytes(keyBytes)
        val wsKey = Base64.getEncoder().encodeToString(keyBytes)

        val handshake = "GET /agent HTTP/1.1\r\n" +
            "Host: $relayHost\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $wsKey\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
        wsOutput?.write(handshake.toByteArray())
        wsOutput?.flush()

        // Read 101 response
        val response = readHttpResponse(wsInput!!)
        if (!response.contains("101 Switching Protocols")) {
            WebServer.addLog("E", "Relay", "WebSocket handshake failed: ${response.take(100)}")
            sock.close()
            return
        }

        WebServer.addLog("D", "Relay", "WebSocket connected to $relayHost:$relayPort")

        // Send register message
        val regJson = if (stableDeviceId.isNotEmpty()) {
            """{"type":"register","name":"$deviceName","device_id":"$stableDeviceId"}"""
        } else {
            """{"type":"register","name":"$deviceName"}"""
        }
        sendTextMessage(regJson)

        // Read incoming messages
        while (running.get()) {
            try {
                val message = readWebSocketFrame(wsInput!!)
                // null = close frame or connection error → stop
                if (message == null) break
                handleMessage(message)
            } catch (e: Exception) {
                if (running.get()) {
                    WebServer.addLog("E", "Relay", "Read error: ${e.message}")
                }
                break
            }
        }

        try { sock.close() } catch (_: Exception) {}
        socket = null
        wsOutput = null
        wsInput = null
    }

    private fun handleMessage(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val type = obj.optString("type")

            when (type) {
                "assigned" -> {
                    deviceId.set(obj.optString("device_id"))
                    publicUrl.set(obj.optString("url"))
                    WebServer.addLog("D", "Relay", "Registered: id=${deviceId.get()} url=${publicUrl.get()}")
                }
                "request" -> {
                    val id = obj.getString("id")
                    val method = obj.optString("method", "GET")
                    val path = obj.optString("path", "/")
                    val bodyB64 = obj.optString("body", "")

                    // Parse headers
                    val headers = mutableMapOf<String, String>()
                    val rawHeaders = obj.optJSONObject("headers")
                    if (rawHeaders != null) {
                        for (key in rawHeaders.keys()) {
                            headers[key.lowercase()] = rawHeaders.optString(key)
                        }
                    }

                    onIncomingRequest?.invoke(id, method, path, headers, bodyB64)
                }
                "close" -> {
                    val id = obj.optString("id", "")
                    activeStreams.remove(id)
                    onStreamClosed?.invoke(id)
                }
            }
        } catch (e: Exception) {
            WebServer.addLog("E", "Relay", "handleMessage: ${e.message}")
        }
    }

    fun sendResponseHeaders(requestId: String, status: Int, headers: Map<String, String>, streaming: Boolean) {
        val h = org.json.JSONObject(headers)
        val msg = """{"type":"response_headers","id":"$requestId","status":$status,"headers":$h,"streaming":$streaming}"""
        sendTextMessage(msg)
        if (streaming) {
            activeStreams.add(requestId)
        }
    }

    fun sendResponseData(requestId: String, data: ByteArray, last: Boolean = false) {
        val b64 = Base64.getEncoder().encodeToString(data)
        val msg = """{"type":"response_data","id":"$requestId","data":"$b64","last":$last}"""
        sendTextMessage(msg)
        if (last) {
            activeStreams.remove(requestId)
        }
    }

    fun sendResponseDone(requestId: String) {
        val msg = """{"type":"response_done","id":"$requestId"}"""
        sendTextMessage(msg)
        activeStreams.remove(requestId)
    }

    private fun sendTextMessage(text: String) {
        synchronized(writeLock) {
            try {
                val data = text.toByteArray(Charsets.UTF_8)
                val os = wsOutput ?: return@synchronized

                // FIN=1, opcode=0x1 (text)
                os.write(0x81)

                // Payload length (masked)
                if (data.size < 126) {
                    os.write((0x80 or data.size).toByte().toInt())
                } else if (data.size < 65536) {
                    os.write((0x80 or 126).toByte().toInt())
                    os.write((data.size ushr 8).toByte().toInt())
                    os.write(data.size.toByte().toInt())
                } else {
                    os.write((0x80 or 127).toByte().toInt())
                    for (i in 7 downTo 0) {
                        os.write((data.size.toLong() ushr (i * 8)).toByte().toInt())
                    }
                }

                // Masking key
                val mask = ByteArray(4)
                SecureRandom().nextBytes(mask)
                os.write(mask[0].toInt())
                os.write(mask[1].toInt())
                os.write(mask[2].toInt())
                os.write(mask[3].toInt())

                // Masked payload
                for (i in data.indices) {
                    os.write((data[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
                }
                os.flush()
            } catch (e: Exception) {
                WebServer.addLog("E", "Relay", "sendTextMessage: ${e.message}")
            }
        }
    }

    private fun readWebSocketFrame(input: InputStream): String? {
        while (true) {
            val firstByte = try { input.read() } catch (_: Exception) { -1 }
            if (firstByte == -1) return null

            val opcode = firstByte and 0x0F
            if (opcode == 0x8) return null // Close frame → disconnect

            if (opcode == 0x9) { // Ping → respond with Pong
                val pkt = readFramePayload(input) ?: return null
                sendPongFrame(pkt)
                continue
            }

            if (opcode == 0xA) { // Pong → ignore
                if (readFramePayload(input) == null) return null
                continue
            }

            // Text (0x1) or Binary (0x2) — read the rest of the frame
            val secondByte = try { input.read() } catch (_: Exception) { -1 }
            if (secondByte == -1) return null

            val masked = (secondByte and 0x80) != 0
            var payloadLen = (secondByte and 0x7F).toLong()

            if (payloadLen == 126L) {
                val b1 = input.read(); val b2 = input.read()
                if (b1 == -1 || b2 == -1) return null
                payloadLen = ((b1 shl 8) or b2).toLong()
            } else if (payloadLen == 127L) {
                payloadLen = 0
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return null
                    payloadLen = (payloadLen shl 8) or b.toLong()
                }
            }

            val maskKey = if (masked) {
                val key = ByteArray(4)
                for (i in 0 until 4) { val b = input.read(); if (b == -1) return null; key[i] = b.toByte() }
                key
            } else null

            val payload = ByteArray(payloadLen.toInt())
            var read = 0
            while (read < payloadLen.toInt()) {
                val r = try { input.read(payload, read, payloadLen.toInt() - read) } catch (_: Exception) { -1 }
                if (r == -1) return null
                read += r
            }

            if (masked && maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            if (opcode == 0x2) continue // Binary → skip

            return String(payload, Charsets.UTF_8) // Text
        }
    }

    private fun readFramePayload(input: InputStream): ByteArray? {
        val secondByte = try { input.read() } catch (_: Exception) { -1 }
        if (secondByte == -1) return null
        val masked = (secondByte and 0x80) != 0
        var len = (secondByte and 0x7F).toLong()
        if (len == 126L) {
            val b1 = input.read(); val b2 = input.read()
            if (b1 == -1 || b2 == -1) return null
            len = ((b1 shl 8) or b2).toLong()
        } else if (len == 127L) {
            len = 0
            for (i in 0 until 8) {
                val b = input.read()
                if (b == -1) return null
                len = (len shl 8) or b.toLong()
            }
        }
        val maskKey = if (masked) {
            val key = ByteArray(4)
            for (i in 0 until 4) { val b = input.read(); if (b == -1) return null; key[i] = b.toByte() }
            key
        } else null
        val data = ByteArray(len.toInt())
        var read = 0
        while (read < len.toInt()) {
            val r = try { input.read(data, read, len.toInt() - read) } catch (_: Exception) { -1 }
            if (r == -1) return null
            read += r
        }
        if (masked && maskKey != null) {
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return data
    }

    private fun sendPongFrame(payload: ByteArray) {
        synchronized(writeLock) {
            try {
                val os = wsOutput ?: return@synchronized
                os.write(0x8A) // FIN=1, opcode=0xA (pong)
                if (payload.size < 126) {
                    os.write(0x80 or payload.size)
                } else {
                    os.write(0x80 or 126)
                    os.write((payload.size ushr 8).toByte().toInt())
                    os.write(payload.size.toByte().toInt())
                }
                val mask = ByteArray(4)
                SecureRandom().nextBytes(mask)
                os.write(mask[0].toInt())
                os.write(mask[1].toInt())
                os.write(mask[2].toInt())
                os.write(mask[3].toInt())
                for (i in payload.indices) {
                    os.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
                }
                os.flush()
            } catch (e: Exception) {
                WebServer.addLog("E", "Relay", "sendPongFrame: ${e.message}")
            }
        }
    }

    private fun skipFrame(input: InputStream) {
        try {
            val second = input.read()
            if (second == -1) return
            var len = (second and 0x7F).toLong()
            if (len == 126L) { len = ((input.read() shl 8) or input.read()).toLong() }
            else if (len == 127L) { len = 0; for (i in 0 until 8) len = (len shl 8) or input.read().toLong() }
            val masked = (second and 0x80) != 0
            if (masked) { for (i in 0 until 4) input.read() }
            var remaining = len.toInt()
            while (remaining > 0) { val s = input.skip(remaining.toLong()).toInt(); if (s <= 0) break; remaining -= s }
        } catch (_: Exception) {}
    }

    private fun readHttpResponse(input: InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        var bytesInBuf = 0
        var last4 = ByteArray(4) { -1 }

        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b.toByte()
            if (last4[0] == '\r'.code.toByte() && last4[1] == '\n'.code.toByte() &&
                last4[2] == '\r'.code.toByte() && last4[3] == '\n'.code.toByte()) {
                break
            }
        }
        return sb.toString()
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        wsOutput = null
        wsInput = null
    }
}
