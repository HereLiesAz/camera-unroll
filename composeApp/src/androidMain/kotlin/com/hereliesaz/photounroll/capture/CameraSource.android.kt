package com.hereliesaz.photounroll.capture

import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.hereliesaz.photounroll.ActivityHolder
import com.hereliesaz.photounroll.imaging.RgbaImage
import com.hereliesaz.photounroll.video.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The rear camera through CameraX, bound to the activity's lifecycle (so it is released when
 * the app is backgrounded and comes back with it). Two streams: a small analysis stream that
 * always holds the latest frame for the live view and the guide, and a still stream that
 * takes real full-resolution photos, EXIF (orientation, focal length) included.
 */
actual object CameraSource {
    private var provider: ProcessCameraProvider? = null
    private var still: ImageCapture? = null
    private var worker: ExecutorService? = null

    /** Latest analysis frame, upright. Written on the worker thread, read from coroutines. */
    @Volatile private var latest: RgbaImage? = null

    actual val available: Boolean
        get() = runCatching { AppContext.app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }.getOrDefault(false)

    actual suspend fun start(): Boolean {
        if (provider != null) return true
        if (!ActivityHolder.cameraPermission()) return false
        val activity = ActivityHolder.activity ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val p = suspendCancellableCoroutine<ProcessCameraProvider?> { done ->
                    val future = ProcessCameraProvider.getInstance(activity)
                    future.addListener({ done.resume(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(activity))
                } ?: return@runCatching false
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> return@runCatching false
                }
                val exec = Executors.newSingleThreadExecutor()
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .build()
                analysis.setAnalyzer(exec) { frame -> frame.use { latest = runCatching { it.upright() }.getOrNull() ?: latest } }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                p.unbindAll()
                p.bindToLifecycle(activity, selector, analysis, capture)
                provider = p
                still = capture
                worker = exec
                true
            }.getOrDefault(false)
        }
    }

    actual fun stop() {
        val p = provider
        provider = null
        still = null
        latest = null
        worker?.shutdown()
        worker = null
        if (p != null) ContextCompat.getMainExecutor(AppContext.app).execute { runCatching { p.unbindAll() } }
    }

    actual suspend fun preview(maxDim: Int): RgbaImage? = withContext(Dispatchers.Default) { latest?.scaledToMax(maxDim) }

    actual suspend fun capture(): ByteArray? {
        val capture = still ?: return null
        val exec = worker ?: return null
        return suspendCancellableCoroutine { done ->
            runCatching {
                capture.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // In-memory captures arrive as one JPEG plane, EXIF and all.
                        val bytes = image.use { runCatching { it.planes[0].buffer.let { b -> ByteArray(b.remaining()).also(b::get) } }.getOrNull() }
                        done.resume(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) = done.resume(null)
                })
            }.onFailure { done.resume(null) }
        }
    }

    /** RGBA plane -> ARGB pixels, turned the way the photographer sees the scene. */
    private fun ImageProxy.upright(): RgbaImage {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            for (x in 0 until width) {
                val i = x * pixelStride
                out[y * width + x] = (0xFF shl 24) or ((row[i].toInt() and 0xFF) shl 16) or
                    ((row[i + 1].toInt() and 0xFF) shl 8) or (row[i + 2].toInt() and 0xFF)
            }
        }
        val img = RgbaImage(width, height, out)
        return when (imageInfo.rotationDegrees) {
            90 -> img.rotated90()
            180 -> img.rotated180()
            270 -> img.rotated270()
            else -> img
        }
    }
}
