package com.aegisf6.app

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface

class WebServer(port: Int = 8080) : NanoHTTPD(port) {

    private var isRunning = false
    private val clients = mutableListOf<String>()

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
            Log.e("WebServer", "Failed to get IP", e)
        }
        return null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d("WebServer", "Request: $uri")

        return when (uri) {
            "/" -> serveHTML()
            else -> newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveHTML(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="uk">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>AEGIS-F6 iPad Control</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: -apple-system, BlinkMacSystemFont, Arial; background: #000; color: #fff; }
                    .container { display: flex; flex-direction: column; height: 100vh; }
                    .header { padding: 12px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); text-align: center; }
                    .header h1 { font-size: 18px; margin-bottom: 4px; }
                    .header p { font-size: 12px; opacity: 0.9; }
                    .video-container { flex: 1; position: relative; overflow: hidden; background: #1a1a1a; }
                    #thermalCanvas { width: 100%; height: 100%; display: block; }
                    .video-hidden { display: none; }
                    .overlay { position: absolute; top: 12px; left: 12px; background: rgba(0,0,0,0.7); padding: 12px; border-radius: 8px; font-size: 14px; font-family: monospace; }
                    .filter-controls { padding: 12px; background: #1a1a1a; border-top: 1px solid #333; display: flex; gap: 8px; }
                    .filter-btn { flex: 1; padding: 10px; background: #667eea; border: none; color: white; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: bold; }
                    .filter-btn.active { background: #764ba2; }
                    .target-info { position: absolute; bottom: 12px; right: 12px; background: rgba(255,100,100,0.8); padding: 8px 12px; border-radius: 4px; font-size: 12px; font-family: monospace; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>AEGIS-F6 iPad Control</h1>
                        <p>Система виявлення повітряних цілей</p>
                    </div>
                    <div class="video-container">
                        <video id="videoElement" class="video-hidden" autoplay playsinline></video>
                        <canvas id="thermalCanvas"></canvas>
                        <div class="overlay">
                            <div>Режим: <span id="modeLabel">Денний</span></div>
                            <div>Камера: <span id="cameraStatus">Ініціалізація...</span></div>
                            <div>FPS: <span id="fpsLabel">0</span></div>
                        </div>
                        <div class="target-info" id="targetInfo" style="display:none;">
                            Азимут: <span id="azimuth">-</span>°<br>
                            Дистанція: <span id="distance">-</span> км<br>
                            Висота: <span id="altitude">-</span> м
                        </div>
                    </div>
                    <div class="filter-controls">
                        <button class="filter-btn active" id="dayBtn">☀ Денний</button>
                        <button class="filter-btn" id="nightBtn">🌙 Нічний</button>
                        <button class="filter-btn" id="thermalBtn">🔥 Тепловізор</button>
                    </div>
                </div>

                <script>
                    let mode = 'day';
                    let stream = null;
                    let frameCount = 0;
                    let lastFpsTime = Date.now();

                    const video = document.getElementById('videoElement');
                    const canvas = document.getElementById('thermalCanvas');
                    const ctx = canvas.getContext('2d');

                    canvas.width = window.innerWidth;
                    canvas.height = window.innerHeight - 60;

                    document.getElementById('dayBtn').onclick = () => { mode = 'day'; updateButtons(); };
                    document.getElementById('nightBtn').onclick = () => { mode = 'night'; updateButtons(); };
                    document.getElementById('thermalBtn').onclick = () => { mode = 'thermal'; updateButtons(); };

                    function updateButtons() {
                        document.querySelectorAll('.filter-btn').forEach(btn => btn.classList.remove('active'));
                        if (mode === 'day') document.getElementById('dayBtn').classList.add('active');
                        else if (mode === 'night') document.getElementById('nightBtn').classList.add('active');
                        else document.getElementById('thermalBtn').classList.add('active');
                        document.getElementById('modeLabel').textContent = 
                            mode === 'day' ? 'Денний' : mode === 'night' ? 'Нічний' : 'Тепловізор';
                    }

                    async function initCamera() {
                        try {
                            stream = await navigator.mediaDevices.getUserMedia({
                                video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }
                            });
                            video.srcObject = stream;
                            video.play();
                            document.getElementById('cameraStatus').textContent = '✓ Активна';
                            processFrame();
                        } catch (err) {
                            console.error('Camera error:', err);
                            document.getElementById('cameraStatus').textContent = '✗ Помилка';
                            ctx.fillStyle = '#666';
                            ctx.fillRect(0, 0, canvas.width, canvas.height);
                            ctx.fillStyle = '#fff';
                            ctx.font = '16px Arial';
                            ctx.textAlign = 'center';
                            ctx.fillText('Дозвіл на камеру не дано', canvas.width/2, canvas.height/2);
                        }
                    }

                    function processFrame() {
                        if (!video.srcObject) return;
                        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

                        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                        const data = imageData.data;

                        if (mode === 'night') {
                            for (let i = 0; i < data.length; i += 4) {
                                const r = data[i], g = data[i+1], b = data[i+2];
                                const gray = 0.299*r + 0.587*g + 0.114*b;
                                data[i] = 255 - gray;
                                data[i+1] = 255 - gray;
                                data[i+2] = 255 - gray;
                            }
                        } else if (mode === 'thermal') {
                            for (let i = 0; i < data.length; i += 4) {
                                const r = data[i], g = data[i+1], b = data[i+2];
                                const intensity = (r + g + b) / 3;
                                data[i] = Math.min(255, intensity * 1.5);
                                data[i+1] = Math.max(0, intensity * 0.5);
                                data[i+2] = Math.max(0, intensity * 0.2);
                            }
                        }
                        
                        ctx.putImageData(imageData, 0, 0);

                        frameCount++;
                        const now = Date.now();
                        if (now - lastFpsTime >= 1000) {
                            document.getElementById('fpsLabel').textContent = frameCount;
                            frameCount = 0;
                            lastFpsTime = now;
                        }

                        requestAnimationFrame(processFrame);
                    }

                    // WebSocket для отримання координат від телефону
                    let ws = null;
                    function connectWebSocket() {
                        const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
                        ws = new WebSocket(protocol + '://' + window.location.host + '/ws');
                        ws.onopen = () => console.log('WebSocket connected');
                        ws.onmessage = (event) => {
                            try {
                                const data = JSON.parse(event.data);
                                document.getElementById('azimuth').textContent = data.azimuth || '-';
                                document.getElementById('distance').textContent = (data.distance || 0).toFixed(2);
                                document.getElementById('altitude').textContent = data.altitude || '-';
                                document.getElementById('targetInfo').style.display = 'block';
                            } catch (e) { console.error('WebSocket parse error:', e); }
                        };
                        ws.onerror = (err) => console.error('WebSocket error:', err);
                        ws.onclose = () => {
                            console.log('WebSocket closed, reconnecting in 2s');
                            setTimeout(connectWebSocket, 2000);
                        };
                    }

                    initCamera();
                    connectWebSocket();
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
    }

    fun startServer(): Boolean {
        return try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.d("WebServer", "Server started on port $listeningPort IP: ${getLocalIpAddress()}")
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

    fun broadcastTargetData(azimuth: Int, distance: Double, altitude: Int) {
        val json = """{"azimuth":$azimuth,"distance":$distance,"altitude":$altitude}"""
        Log.d("WebServer", "Broadcasting: $json")
        // Це відправляється через WebSocket клієнтам (потребує додаткової обробки)
    }
}
