package com.aegisf6.app

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface

class WebServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    private var isRunning = false

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is InetAddress && address.hostAddress?.indexOf(':') == -1) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebServer", "Failed to get IP address", e)
        }
        return null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d("WebServer", "Request: $uri")

        return when (uri) {
            "/" -> {
                val html = """
                    <!DOCTYPE html>
                    <html lang="uk">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>AEGIS-F6 iPad Control</title>
                        <style>
                            body { font-family: Arial, sans-serif; margin: 20px; background-color: #f0f0f0; }
                            h1 { color: #333; }
                            .status { padding: 10px; margin: 10px 0; border-radius: 5px; }
                            .active { background-color: #d4edda; color: #155724; }
                            .idle { background-color: #fff3cd; color: #856404; }
                            .alert { background-color: #f8d7da; color: #721c24; }
                        </style>
                    </head>
                    <body>
                        <h1>AEGIS-F6 Троєщина</h1>
                        <p>Система раннього виявлення повітряних цілей</p>
                        <div id="status" class="status idle">Підключення...</div>
                        <script>
                            function updateStatus() {
                                fetch('/status')
                                    .then(response => response.json())
                                    .then(data => {
                                        const statusDiv = document.getElementById('status');
                                        statusDiv.className = 'status ' + data.status;
                                        statusDiv.textContent = data.message;
                                    })
                                    .catch(err => console.error('Error:', err));
                            }
                            setInterval(updateStatus, 1000);
                            updateStatus();
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(html).apply {
                    setMimeType("text/html; charset=utf-8")
                }
            }
            "/status" -> {
                // This would need to be updated with actual state, but for now placeholder
                val json = """{"status": "idle", "message": "Моніторинг на паузі"}"""
                newFixedLengthResponse(json).apply {
                    setMimeType("application/json")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    fun startServer(): Boolean {
        return try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.d("WebServer", "Server started on port $listeningPort")
            true
        } catch (e: IOException) {
            Log.e("WebServer", "Failed to start server", e)
            false
        }
    }

    fun stopServer() {
        stop()
        isRunning = false
        Log.d("WebServer", "Server stopped")
    }

    fun isServerRunning(): Boolean = isRunning

    fun getServerUrl(): String {
        val ip = getLocalIpAddress() ?: "localhost"
        return "http://$ip:$listeningPort"
    }
}package com.aegisf6.app

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WebServer(private val port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/" -> {
                val html = """
                    <!DOCTYPE html>
                    <html lang="uk">
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>AEGIS-F6 iPad Relay</title>
                        <style>
                            body { font-family: Arial, sans-serif; text-align: center; padding: 20px; }
                            h1 { color: #333; }
                            p { font-size: 18px; }
                        </style>
                    </head>
                    <body>
                        <h1>🛡️ AEGIS-F6 iPad Relay</h1>
                        <p>Ця сторінка дозволяє підключити iPad до системи AEGIS-F6 для віддаленого моніторингу.</p>
                        <p>Система працює. Очікуємо підключення WebSocket...</p>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    fun startServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopServer() {
        stop()
    }
}