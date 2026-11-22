package com.hereliesaz.cameraunroll.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hereliesaz.cameraunroll.video.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * The bundled network through ONNX Runtime for Android, on the CPU (which every phone has and
 * which runs it in well under a second per photo on recent ones). The model is read from the
 * app's assets on first use. Any failure, from a missing native library to running out of
 * memory, yields null and detection falls back to color edges.
 */
actual object DepthModel {
    private val session: OrtSession? by lazy {
        runCatching {
            val bytes = AppContext.app.assets.open(DepthIo.MODEL_FILE).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                // Big cores only: more threads than that fight the little cores and run slower.
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            OrtEnvironment.getEnvironment().createSession(bytes, options)
        }.getOrNull()
    }

    actual suspend fun estimate(photo: RgbaImage): RgbaImage? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext null
        try {
            val input = DepthIo.input(photo)
            val shape = longArrayOf(1, 3, input.height.toLong(), input.width.toLong())
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input.chw), shape).use { tensor ->
                synchronized(s) { s.run(mapOf(DepthIo.INPUT_NAME to tensor)) }.use { result ->
                    val buffer = (result[0] as OnnxTensor).floatBuffer
                    val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    DepthIo.image(values, input.width, input.height)
                }
            }
        } catch (t: Throwable) { // includes OutOfMemoryError: a photo without depth beats a crash
            null
        }
    }
}
