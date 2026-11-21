package com.hereliesaz.photounroll.capture

import com.hereliesaz.photounroll.imaging.RgbaImage

/** Guided capture is not built for this platform yet; photos come in through the picker. */
actual object CameraSource {
    actual val available: Boolean = false
    actual suspend fun start(): Boolean = false
    actual fun stop() {}
    actual suspend fun preview(maxDim: Int): RgbaImage? = null
    actual suspend fun capture(): ByteArray? = null
}
