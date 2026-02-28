package com.realtimemonitor.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class StreamingServer(
    port: Int = DEFAULT_PORT,
    private val assetLoader: (String) -> InputStream?
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 4747
        private const val MJPEG_BOUNDARY = "--frame"
        private const val ZOOM_STEP = 0.2f
    }

    private val mjpegClients = ConcurrentLinkedQueue<MjpegClient>()
    private val audioClients = ConcurrentLinkedQueue<AudioClient>()

    private var currentZoom = 1.0f
    private var maxZoom = 10.0f
    private var rotationDegrees = 0
    private var flashOn = false
    private var currentResolution = "720p"

    var onZoomChanged: ((Float) -> Unit)? = null
    var onFlashToggled: ((Boolean) -> Unit)? = null
    var onSwitchCamera: (() -> Unit)? = null
    var onResolutionChanged: ((String) -> Unit)? = null

    fun setMaxZoom(max: Float) {
        maxZoom = max
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/" || uri == "/index.html" -> serveWebClient()
            uri == "/video" -> serveMjpegStream()
            uri == "/audio" -> serveAudioStream()
            uri == "/api/zoom" && method == Method.POST -> handleZoom(session)
            uri == "/api/rotate" && method == Method.POST -> handleRotate()
            uri == "/api/flash" && method == Method.POST -> handleFlash()
            uri == "/api/switch" && method == Method.POST -> handleSwitchCamera()
            uri == "/api/resolution" && method == Method.POST -> handleResolution(session)
            uri == "/api/status" -> handleStatus()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
            )
        }
    }

    fun pushVideoFrame(jpegData: ByteArray) {
        val deadClients = mutableListOf<MjpegClient>()
        for (client in mjpegClients) {
            try {
                client.pushFrame(jpegData)
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }
        deadClients.forEach {
            mjpegClients.remove(it)
            it.close()
        }
    }

    fun pushAudioData(pcmData: ByteArray) {
        val deadClients = mutableListOf<AudioClient>()
        for (client in audioClients) {
            try {
                client.pushData(pcmData)
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }
        deadClients.forEach {
            audioClients.remove(it)
            it.close()
        }
    }

    fun getConnectedClientCount(): Int = mjpegClients.size

    private fun serveWebClient(): Response {
        val inputStream = assetLoader("index.html")
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Web client not found"
            )

        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveMjpegStream(): Response {
        val client = MjpegClient()
        mjpegClients.add(client)

        val response = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY",
            client.inputStream
        )
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun serveAudioStream(): Response {
        val client = AudioClient()
        audioClients.add(client)

        val response = newChunkedResponse(
            Response.Status.OK,
            "application/octet-stream",
            client.inputStream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleZoom(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val action = session.parameters["action"]?.firstOrNull()

        when (action) {
            "in" -> currentZoom = (currentZoom + ZOOM_STEP).coerceAtMost(maxZoom)
            "out" -> currentZoom = (currentZoom - ZOOM_STEP).coerceAtLeast(1.0f)
            "reset" -> currentZoom = 1.0f
            else -> {
                val level = session.parameters["level"]?.firstOrNull()?.toFloatOrNull()
                if (level != null) {
                    currentZoom = level.coerceIn(1.0f, maxZoom)
                } else {
                    return jsonResponse("""{"error":"Invalid action. Use: in, out, reset"}""")
                }
            }
        }

        onZoomChanged?.invoke(currentZoom)
        return jsonResponse("""{"zoom":$currentZoom,"maxZoom":$maxZoom}""")
    }

    private fun handleRotate(): Response {
        rotationDegrees = (rotationDegrees + 90) % 360
        return jsonResponse("""{"rotation":$rotationDegrees}""")
    }

    private fun handleFlash(): Response {
        flashOn = !flashOn
        onFlashToggled?.invoke(flashOn)
        return jsonResponse("""{"flash":$flashOn}""")
    }

    private fun handleSwitchCamera(): Response {
        onSwitchCamera?.invoke()
        return jsonResponse("""{"switched":true}""")
    }

    private fun handleResolution(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val value = session.parameters["value"]?.firstOrNull()
            ?: return jsonResponse("""{"error":"Missing value parameter"}""")

        val valid = listOf("480p", "720p", "1080p")
        if (value !in valid) {
            return jsonResponse("""{"error":"Invalid resolution. Options: ${valid.joinToString()}"}""")
        }

        currentResolution = value
        onResolutionChanged?.invoke(value)
        return jsonResponse("""{"resolution":"$currentResolution"}""")
    }

    private fun handleStatus(): Response {
        return jsonResponse(
            """{"zoom":$currentZoom,"maxZoom":$maxZoom,"rotation":$rotationDegrees,"flash":$flashOn,"resolution":"$currentResolution","clients":${getConnectedClientCount()}}"""
        )
    }

    private fun jsonResponse(json: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    override fun stop() {
        mjpegClients.forEach { it.close() }
        mjpegClients.clear()
        audioClients.forEach { it.close() }
        audioClients.clear()
        super.stop()
    }

    inner class MjpegClient {
        private val pipedOut = PipedOutputStream()
        val inputStream: PipedInputStream = PipedInputStream(pipedOut, 512 * 1024)
        private val closed = AtomicBoolean(false)

        fun pushFrame(jpegData: ByteArray) {
            if (closed.get()) return
            try {
                val header =
                    "$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegData.size}\r\n\r\n"
                pipedOut.write(header.toByteArray())
                pipedOut.write(jpegData)
                pipedOut.write("\r\n".toByteArray())
                pipedOut.flush()
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                try { pipedOut.close() } catch (_: Exception) { }
                try { inputStream.close() } catch (_: Exception) { }
            }
        }
    }

    inner class AudioClient {
        private val pipedOut = PipedOutputStream()
        val inputStream: PipedInputStream = PipedInputStream(pipedOut, 64 * 1024)
        private val closed = AtomicBoolean(false)

        fun pushData(pcmData: ByteArray) {
            if (closed.get()) return
            try {
                pipedOut.write(pcmData)
                pipedOut.flush()
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                try { pipedOut.close() } catch (_: Exception) { }
                try { inputStream.close() } catch (_: Exception) { }
            }
        }
    }
}
