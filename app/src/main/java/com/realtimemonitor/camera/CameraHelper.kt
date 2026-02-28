package com.realtimemonitor.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class StreamResolution(val width: Int, val height: Int, val label: String) {
    SD(640, 480, "480p"),
    HD(1280, 720, "720p");

    companion object {
        fun fromLabel(label: String): StreamResolution? =
            entries.find { it.label == label }
    }
}

class CameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "CameraHelper"
        const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var storedLifecycleOwner: LifecycleOwner? = null
    private var storedPreviewView: PreviewView? = null

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val isRecordingAudio = AtomicBoolean(false)

    var currentResolution: StreamResolution = StreamResolution.HD
        private set

    var onFrameAvailable: ((ByteArray) -> Unit)? = null
    var onAudioAvailable: ((ByteArray) -> Unit)? = null
    var jpegQuality: Int = 70

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        storedLifecycleOwner = lifecycleOwner
        storedPreviewView = previewView
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(currentResolution.width, currentResolution.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    fun setResolution(resolution: StreamResolution) {
        currentResolution = resolution
        val lo = storedLifecycleOwner ?: return
        val pv = storedPreviewView ?: return
        bindCameraUseCases(lo, pv)
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val jpegData = imageProxyToJpeg(imageProxy)
            if (jpegData != null) {
                onFrameAvailable?.invoke(jpegData)
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21,
            imageProxy.width, imageProxy.height, null
        )
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            jpegQuality,
            outputStream
        )
        return outputStream.toByteArray()
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        val yBuffer = yPlane.buffer.duplicate()
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uvHeight = height / 2
        val uvWidth = width / 2
        var offset = width * height

        if (uvPixelStride == 2) {
            for (row in 0 until uvHeight) {
                vBuffer.position(row * uvRowStride)
                val len = minOf(width, vBuffer.remaining())
                vBuffer.get(nv21, offset, len)
                offset += width
            }
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val idx = row * uvRowStride + col * uvPixelStride
                    nv21[offset++] = vBuffer.get(idx)
                    nv21[offset++] = uBuffer.get(idx)
                }
            }
        }

        return nv21
    }

    fun setZoom(zoomRatio: Float) {
        val max = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f
        val min = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        camera?.cameraControl?.setZoomRatio(zoomRatio.coerceIn(min, max))
    }

    fun getMaxZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    fun getMinZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

    fun toggleFlash(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    @Suppress("MissingPermission")
    fun startAudioCapture() {
        if (isRecordingAudio.get()) return

        val bufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL, AUDIO_ENCODING)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio buffer size: $bufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL,
                AUDIO_ENCODING,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio permission not granted", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        isRecordingAudio.set(true)
        audioRecord?.startRecording()

        audioThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (isRecordingAudio.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    onAudioAvailable?.invoke(buffer.copyOf(read))
                }
            }
        }, "AudioCaptureThread").also { it.start() }
    }

    fun stopAudioCapture() {
        isRecordingAudio.set(false)
        audioThread?.join(1000)
        audioThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
    }

    fun release() {
        stopAudioCapture()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
