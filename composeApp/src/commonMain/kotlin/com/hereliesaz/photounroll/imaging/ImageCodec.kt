package com.hereliesaz.photounroll.imaging

import androidx.compose.ui.graphics.ImageBitmap

/** The only platform-specific imaging surface: compressed bytes <-> [RgbaImage] <-> Compose. */
expect object ImageCodec {
    /**
     * Decodes JPEG/PNG/WebP bytes. Does NOT apply EXIF orientation; see [loadPhoto].
     * @param atLeast the longest side the caller needs. A decoder that can read a file at a
     *   reduced size may return anything no smaller than this, sparing the memory of the full
     *   image; others ignore it and return the image whole.
     */
    fun decode(bytes: ByteArray, atLeast: Int = Int.MAX_VALUE): RgbaImage

    /** Encodes opaque JPEG at [quality] 0..100. */
    fun encodeJpeg(image: RgbaImage, quality: Int = 92): ByteArray

    fun toImageBitmap(image: RgbaImage): ImageBitmap
}

/** Decodes, rights it per EXIF, and caps the longest side so every target stays inside its memory budget. */
fun loadPhoto(bytes: ByteArray, maxDim: Int = 1600): RgbaImage =
    ImageCodec.decode(bytes, maxDim).withExifOrientation(Exif.orientation(bytes)).scaledToMax(maxDim)
