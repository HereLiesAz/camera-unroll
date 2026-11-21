package com.hereliesaz.photounroll.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

actual object DepthModel {
    /** Built on first use; null if the model or its native runtime cannot be loaded here. */
    private val session: OrtSession? by lazy {
        runCatching {
            val bytes = DepthModel::class.java.getResourceAsStream("/${DepthIo.MODEL_FILE}")!!.use { it.readBytes() }
            OrtEnvironment.getEnvironment().createSession(bytes, OrtSession.SessionOptions())
        }.getOrNull()
    }

    actual suspend fun estimate(photo: RgbaImage): RgbaImage? = withContext(Dispatchers.Default) {
        val s = session ?: return@withContext null
        runCatching {
            val input = DepthIo.input(photo)
            val shape = longArrayOf(1, 3, input.height.toLong(), input.width.toLong())
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input.chw), shape).use { tensor ->
                synchronized(s) { s.run(mapOf(DepthIo.INPUT_NAME to tensor)) }.use { result ->
                    val buffer = (result[0] as OnnxTensor).floatBuffer
                    val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    DepthIo.image(values, input.width, input.height)
                }
            }
        }.getOrNull()
    }
}
