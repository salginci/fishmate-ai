package com.fishmate.ai.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.fishmate.ai.core.Log2
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX-backed [CameraSource].
 *
 * Uses `ImageAnalysis` rather than `Preview` + capture, because we want raw
 * frames we can downscale and compress ourselves. `STRATEGY_KEEP_ONLY_LATEST`
 * means a slow network never backs up frames: we always send something current
 * rather than a queue of stale images.
 */
class PhoneCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraSource {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _facing = MutableStateFlow(CameraFacing.BACK)
    override val facing: StateFlow<CameraFacing> = _facing.asStateFlow()

    override val supportsSwitching: Boolean = true

    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var latestFrame: CameraFrame? = null

    override fun frames(config: FrameConfig): Flow<CameraFrame> = callbackFlow {
        _state.value = CameraState.Starting
        val cameraProvider = try {
            awaitCameraProvider(context)
        } catch (e: Exception) {
            _state.value = CameraState.Error("The camera could not be started.")
            close(e)
            return@callbackFlow
        }
        provider = cameraProvider

        val analysis = ImageAnalysis.Builder()
            // Always hand us the newest frame; never queue.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        var lastEmitted = 0L
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val now = System.currentTimeMillis()
                // Throttle here, before any conversion work: compressing a
                // frame we are about to discard would waste CPU and battery.
                if (now - lastEmitted < config.intervalMillis) return@setAnalyzer
                lastEmitted = now

                val frame = proxy.toJpegFrame(config)
                if (frame != null) {
                    latestFrame = frame
                    trySend(frame)
                }
            } catch (e: Exception) {
                Log2.w(TAG, "frame conversion failed", e)
            } finally {
                proxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selectorFor(_facing.value), analysis)
            _state.value = CameraState.Streaming
            Log2.i(
                TAG,
                "camera streaming facing=${_facing.value} fps=${config.framesPerSecond} " +
                    "max=${config.maxDimensionPx}px q=${config.jpegQuality}",
            )
        } catch (e: Exception) {
            _state.value = CameraState.Error("The camera is unavailable right now.")
            close(e)
            return@callbackFlow
        }

        awaitClose {
            analysis.clearAnalyzer()
            runCatching { cameraProvider.unbindAll() }
            _state.value = CameraState.Idle
            Log2.i(TAG, "camera stopped")
        }
    }

    /**
     * Return the most recent streamed frame.
     *
     * Reusing the last analysed frame keeps "show me that" instant and avoids
     * binding a second use case mid-session.
     */
    override suspend fun captureStill(config: FrameConfig): CameraFrame? = latestFrame

    override suspend fun switchCamera() {
        _facing.value =
            if (_facing.value == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        Log2.i(TAG, "camera switched to ${_facing.value}")
        // Rebinding happens when frames() is re-collected by the controller.
    }

    override fun release() {
        runCatching { provider?.unbindAll() }
        provider = null
        latestFrame = null
        analysisExecutor.shutdown()
        _state.value = CameraState.Idle
    }

    private fun selectorFor(facing: CameraFacing): CameraSelector = when (facing) {
        CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    companion object {
        private const val TAG = "PhoneCamera"
    }
}

/**
 * YUV_420_888 -> downscaled, rotation-corrected JPEG.
 *
 * Rotation matters more than it looks: an uncorrected frame arrives sideways,
 * and a model asked to identify a fish from a 90°-rotated image gives
 * noticeably worse answers.
 */
internal fun ImageProxy.toJpegFrame(config: FrameConfig): CameraFrame? {
    val nv21 = yuv420ToNv21(this) ?: return null
    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)

    val baseline = ByteArrayOutputStream()
    if (!yuv.compressToJpeg(Rect(0, 0, width, height), 90, baseline)) return null
    var bitmap = BitmapFactory.decodeByteArray(
        baseline.toByteArray(), 0, baseline.size(),
    ) ?: return null

    val rotation = imageInfo.rotationDegrees
    val longest = maxOf(bitmap.width, bitmap.height)
    val scale = if (longest > config.maxDimensionPx) {
        config.maxDimensionPx.toFloat() / longest
    } else {
        1f
    }

    if (rotation != 0 || scale < 1f) {
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val transformed = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
        )
        if (transformed !== bitmap) {
            bitmap.recycle()
            bitmap = transformed
        }
    }

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, out)
    val frame = CameraFrame(out.toByteArray(), bitmap.width, bitmap.height)
    bitmap.recycle()
    return frame
}

/** Interleave the U/V planes, honouring row and pixel strides. */
internal fun yuv420ToNv21(image: ImageProxy): ByteArray? {
    if (image.planes.size < 3) return null
    val width = image.width
    val height = image.height
    val output = ByteArray(width * height * 3 / 2)

    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    var outputOffset = 0
    if (yPlane.rowStride == width) {
        yBuffer.get(output, 0, width * height)
        outputOffset = width * height
    } else {
        val row = ByteArray(yPlane.rowStride)
        for (y in 0 until height) {
            yBuffer.position(y * yPlane.rowStride)
            yBuffer.get(row, 0, minOf(yPlane.rowStride, yBuffer.remaining() + 0))
            System.arraycopy(row, 0, output, outputOffset, width)
            outputOffset += width
        }
    }

    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val chromaHeight = height / 2
    val chromaWidth = width / 2

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            if (uIndex >= uBuffer.limit() || vIndex >= vBuffer.limit()) continue
            // NV21 expects V before U.
            output[outputOffset++] = vBuffer.get(vIndex)
            output[outputOffset++] = uBuffer.get(uIndex)
        }
    }
    return output
}


/**
 * Await CameraX's provider without pulling in kotlinx-coroutines-guava.
 *
 * The ListenableFuture is already resolved on most calls after the first, so
 * this adds no latency; it just avoids an extra dependency for one call site.
 */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            // Main executor: CameraX expects provider work on the main thread.
            androidx.core.content.ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }
