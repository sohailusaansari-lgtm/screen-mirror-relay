package com.remotemonitor

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TunnelManager {

    companion object {
        private val SERVICES = arrayOf(
            TunnelService("serveo.net", "tunnel", "tunnel"),
            TunnelService("localhost.run", "nokey", null)
        )
    }

    data class TunnelService(val host: String, val user: String, val password: String?)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var session: Session? = null
    val publicUrl = AtomicReference<String?>(null)
    val errorMessage = AtomicReference<String?>(null)
    val isConnected: Boolean get() = session?.isConnected == true

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread {
            var serviceIndex = 0
            while (running.get()) {
                val svc = SERVICES[serviceIndex % SERVICES.size]
                try {
                    connect(svc)
                    session?.let { s ->
                        while (running.get() && s.isConnected) {
                            Thread.sleep(1000)
                        }
                    }
                } catch (e: Exception) {
                    val msg = "${svc.host}: ${e.message}"
                    errorMessage.set(msg)
                    WebServer.addLog("E", "Tunnel", msg)
                }
                serviceIndex++
                if (running.get()) {
                    Thread.sleep(15000)
                }
            }
        }.apply {
            isDaemon = true
            name = "tunnel-thread"
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
        disconnect()
    }

    private fun connect(svc: TunnelService) {
        val jsch = JSch()
        val sshSession = jsch.getSession(svc.user, svc.host, 22)
        sshSession.setConfig("StrictHostKeyChecking", "no")
        sshSession.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        if (svc.password != null) {
            sshSession.setPassword(svc.password)
        }

        // Use UserInfo for interactive auth
        sshSession.setUserInfo(object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = svc.password
            override fun promptPassword(message: String?): Boolean = true
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) {
                WebServer.addLog("D", "Tunnel", "SSH: $message")
            }
        })

        sshSession.connect(30000)
        WebServer.addLog("D", "Tunnel", "SSH connected to ${svc.host}")
        errorMessage.set(null)

        try {
            sshSession.setPortForwardingR(80, "localhost", 8080)
            WebServer.addLog("D", "Tunnel", "Port forwarding 80 -> localhost:8080")
        } catch (e: Exception) {
            // Some services might reject privileged port
            try {
                val port = sshSession.setPortForwardingR(0, "localhost", 8080)
                WebServer.addLog("D", "Tunnel", "Port forwarding $port -> localhost:8080")
            } catch (e2: Exception) {
                WebServer.addLog("E", "Tunnel", "Port forwarding failed: ${e2.message}")
                sshSession.disconnect()
                return
            }
        }

        // Open shell to capture URL output
        val channel = sshSession.openChannel("shell") as ChannelShell
        channel.setPtyType("dumb")
        val input = channel.getInputStream()
        val output = channel.getOutputStream()
        channel.connect()

        val reader = BufferedReader(InputStreamReader(input))
        output.write("echo READY\n".toByteArray())
        output.flush()

        val startTime = System.currentTimeMillis()
        while (running.get() && System.currentTimeMillis() - startTime < 10000) {
            if (reader.ready()) {
                val line = reader.readLine()
                if (line != null) {
                    WebServer.addLog("D", "Tunnel", "${svc.host}: $line")
                    val urlMatch = Regex("https?://[a-zA-Z0-9._-]+(?:\\.serveo\\.net|\\.localhost\\.run)").find(line)
                    if (urlMatch != null) {
                        publicUrl.set(urlMatch.value)
                        errorMessage.set(null)
                        WebServer.addLog("D", "Tunnel", "Public URL: ${urlMatch.value}")
                        break
                    }
                }
            } else {
                Thread.sleep(100)
            }
        }

        if (publicUrl.get() == null) {
            // Try fetching URL from known format
            val known = publicUrl.get()
            if (known == null) {
                errorMessage.set("Connected but no URL yet (try reopening sidebar)")
            }
        }

        session = sshSession

        // Keep connection alive
        while (running.get() && sshSession.isConnected) {
            Thread.sleep(1000)
            try {
                while (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) WebServer.addLog("D", "Tunnel", "SSH: $line")
                }
            } catch (_: Exception) { break }
        }
    }

    private fun disconnect() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }
}
