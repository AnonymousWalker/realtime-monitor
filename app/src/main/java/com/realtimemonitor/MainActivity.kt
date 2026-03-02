package com.realtimemonitor

import android.Manifest
import android.content.ClipData
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.realtimemonitor.camera.CameraHelper
import com.realtimemonitor.camera.StreamResolution
import com.realtimemonitor.server.StreamingServer
import com.realtimemonitor.wifi.WifiDirectHelper
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private fun getRequiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    private lateinit var cameraHelper: CameraHelper
    private var streamingServer: StreamingServer? = null
    private val wifiDirectHelper = WifiDirectHelper(this)

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnShowLink: ImageButton

    private var isStreaming = false
    private var usedWifiDirect = false
    private var wifiDirectPassphrase: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnShowLink = findViewById(R.id.btnShowLink)

        cameraHelper = CameraHelper(this)

        btnStartStop.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        btnShowLink.setOnClickListener {
            val url = tvUrl.text.toString()
            if (url.isNotEmpty()) {
                showStreamUrlDialog(url, wifiDirectPassphrase)
            } else {
                Toast.makeText(this, R.string.start_streaming_first, Toast.LENGTH_SHORT).show()
            }
        }

        if (allPermissionsGranted()) {
            initializeCamera()
        } else {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun allPermissionsGranted() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                initializeCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initializeCamera() {
        cameraHelper.startCamera(this, previewView)
        tvStatus.text = getString(R.string.status_ready)
    }

    private fun startStreaming() {
        val port = StreamingServer.DEFAULT_PORT
        val wifiIp = getWifiIpAddress()
        val hasWifi = wifiIp.isNotEmpty() && wifiIp != "0.0.0.0"

        if (hasWifi) {
            startStreamingWithHost(wifiIp, port, useWifiDirect = false)
            return
        }

        tvStatus.text = getString(R.string.status_wifi_direct_creating)
        btnStartStop.isEnabled = false
        wifiDirectHelper.createGroup { success, goIp, passphrase ->
            runOnUiThread {
                btnStartStop.isEnabled = true
                if (success && goIp != null) {
                    usedWifiDirect = true
                    wifiDirectPassphrase = passphrase
                    startStreamingWithHost(goIp, port, useWifiDirect = true)
                } else {
                    tvStatus.text = getString(R.string.status_ready)
                    Toast.makeText(this, R.string.wifi_direct_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startStreamingWithHost(hostIp: String, port: Int, useWifiDirect: Boolean) {
        streamingServer = StreamingServer(port) { filename ->
            try {
                assets.open(filename)
            } catch (_: Exception) {
                null
            }
        }

        streamingServer?.onZoomChanged = { zoom ->
            cameraHelper.setZoom(zoom)
        }

        streamingServer?.onFlashToggled = { enabled ->
            cameraHelper.toggleFlash(enabled)
        }

        streamingServer?.onSwitchCamera = {
            runOnUiThread {
                cameraHelper.switchCamera(this, previewView)
            }
        }

        streamingServer?.onResolutionChanged = { label ->
            StreamResolution.fromLabel(label)?.let { resolution ->
                runOnUiThread {
                    cameraHelper.setResolution(resolution)
                }
            }
        }

        streamingServer?.setMaxZoom(cameraHelper.getMaxZoom())

        cameraHelper.onFrameAvailable = { jpegData ->
            streamingServer?.pushVideoFrame(jpegData)
        }

        cameraHelper.onAudioAvailable = { audioData ->
            streamingServer?.pushAudioData(audioData)
        }

        try {
            streamingServer?.start()
            cameraHelper.startAudioCapture()
            isStreaming = true

            tvUrl.text = String.format("http://%s:%d/", hostIp, port)
            tvStatus.text = if (useWifiDirect) getString(R.string.status_streaming_p2p) else getString(R.string.status_streaming)
            btnStartStop.text = getString(R.string.btn_stop)
        } catch (e: Exception) {
            if (useWifiDirect) {
                usedWifiDirect = false
                wifiDirectHelper.removeGroup()
            }
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopStreaming() {
        cameraHelper.onFrameAvailable = null
        cameraHelper.onAudioAvailable = null
        cameraHelper.stopAudioCapture()
        streamingServer?.stop()
        streamingServer = null
        isStreaming = false
        if (usedWifiDirect) {
            wifiDirectHelper.removeGroup()
            usedWifiDirect = false
            wifiDirectPassphrase = null
        }

        tvUrl.text = ""
        tvStatus.text = getString(R.string.status_stopped)
        btnStartStop.text = getString(R.string.btn_start)
    }

    private fun showStreamUrlDialog(url: String, p2pPassphrase: String? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_stream_url, null)
        val tvStreamUrl = view.findViewById<TextView>(R.id.tvStreamUrl)
        val tvP2pHint = view.findViewById<TextView>(R.id.tvP2pHint)
        val tvP2pPassword = view.findViewById<TextView>(R.id.tvP2pPassword)
        val ivQrCode = view.findViewById<ImageView>(R.id.ivQrCode)
        val btnCopyUrl = view.findViewById<Button>(R.id.btnCopyUrl)

        tvStreamUrl.text = url
        val isP2p = url.contains("192.168.49")
        tvP2pHint.visibility = if (isP2p) View.VISIBLE else View.GONE
        if (tvP2pPassword != null) {
            tvP2pPassword.visibility = if (isP2p && !p2pPassphrase.isNullOrEmpty()) View.VISIBLE else View.GONE
            tvP2pPassword.text = getString(R.string.dialog_p2p_password, p2pPassphrase ?: "")
        }
        generateQrCodeBitmap(url)?.let { ivQrCode.setImageBitmap(it) }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCopyUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
            Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun generateQrCodeBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.MARGIN, 1)
            }
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            (ip shr 8) and 0xff,
            (ip shr 16) and 0xff,
            (ip shr 24) and 0xff
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) {
            stopStreaming()
        }
        cameraHelper.release()
        wifiDirectHelper.release()
    }
}
