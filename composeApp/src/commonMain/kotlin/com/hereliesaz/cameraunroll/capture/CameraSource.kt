package com.hereliesaz.cameraunroll.capture

import com.hereliesaz.cameraunroll.imaging.RgbaImage

/**
 * The device's rear camera, for guided capture. One camera, opened and closed as a whole;
 * small frames for the live view and the guide's measurements, full frames for keeps.
 */
expect object CameraSource {
    /** Whether this platform has a camera the app can drive at all (it may still be refused). */
    val available: Boolean

    /** Opens the camera; false if there is none or permission was refused. */
    suspend fun start(): Boolean

    fun stop()

    /** The current frame, no larger than [maxDim] on its longest side; null if not running. */
    suspend fun preview(maxDim: Int): RgbaImage?

    /** The current frame at full resolution, as JPEG bytes; null if not running. */
    suspend fun capture(): ByteArray?
}
