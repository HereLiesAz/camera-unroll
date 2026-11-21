package com.hereliesaz.photounroll.imaging

/** No depth runtime on this platform yet: pillar detection uses color edges alone. */
actual object DepthModel {
    actual suspend fun estimate(photo: RgbaImage): RgbaImage? = null
}
