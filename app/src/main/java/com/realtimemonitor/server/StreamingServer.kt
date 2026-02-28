package com.realtimemonitor.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.SequenceInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class StreamingServer(
    port: Int = DEFAULT_PORT,
    private val assetLoader: (String) -> InputStream?
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 4747
        private const val MJPEG_BOUNDARY = "--frame"
    }

    private val mjpegClients = ConcurrentLinkedQueue<MjpegClient>()
    private val audioClients = ConcurrentLinkedQueue<AudioClient>()

    private var currentZoom = 1.0f
    private var rotationDegrees = 0
    private var flashOn = false

    var onZoomChanged: ((Float) -> Unit)? = null
    var onFlashToggled: ((Boolean) -> Unit)? = null
    var onSwitchCamera: (() -> Unit)? = null

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
            "audio/wav",
            client.inputStream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleZoom(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val zoomStr = session.parameters["level"]?.firstOrNull()
        val zoom = zoomStr?.toFloatOrNull()
            ?: return jsonResponse("""{"error":"Invalid zoom level"}""")
        currentZoom = zoom
        onZoomChanged?.invoke(zoom)
        return jsonResponse("""{"zoom":$zoom}""")
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

    private fun handleStatus(): Response {
        return jsonResponse(
            """{"zoom":$currentZoom,"rotation":$rotationDegrees,"flash":$flashOn,"clients":${getConnectedClientCount()}}"""
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
        val inputStream: InputStream
        private val closed = AtomicBoolean(false)

        init {
            val pipedIn = PipedInputStream(pipedOut, 64 * 1024)
            val wavHeader = WavHeader.create(
                sampleRate = 16000,
                channels = 1,
                bitsPerSample = 16
            )
            inputStream = SequenceInputStream(ByteArrayInputStream(wavHeader), pipedIn)
        }

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
