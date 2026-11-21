package com.hereliesaz.photounroll.imaging

/** Minimal JPEG/EXIF reader: orientation (0x0112) and capture time. */
object Exif {
    fun orientation(jpeg: ByteArray): Int = tiff(jpeg)?.let { (t, end) -> readTiff(jpeg, t, end) } ?: 1

    /**
     * Capture time as a sortable number (YYYYMMDDhhmmss × 1000 + ms), from DateTimeOriginal
     * (+ SubSecTimeOriginal), falling back to IFD0 DateTime. Only for ordering; null if absent.
     */
    fun captureTime(jpeg: ByteArray): Long? {
        val (t, end) = tiff(jpeg) ?: return null
        if (t + 8 > end) return null
        val little = jpeg.u8(t) == 0x49
        val ifd0 = t + u32(jpeg, t + 4, little)
        val exifIfd = tag(jpeg, ifd0, end, little, 0x8769)?.let { t + u32(jpeg, it + 8, little) }
        val stamp = exifIfd?.let { ascii(jpeg, t, end, little, it, 0x9003) } ?: ascii(jpeg, t, end, little, ifd0, 0x0132)
        val digits = stamp?.filter { it.isDigit() }?.take(14)?.takeIf { it.length == 14 } ?: return null
        val sub = exifIfd?.let { ascii(jpeg, t, end, little, it, 0x9291) }?.filter { it.isDigit() }.orEmpty()
        val ms = (sub + "000").take(3).toInt()
        return digits.toLong() * 1000 + ms
    }

    /** 35 mm-equivalent focal length (FocalLengthIn35mmFilm), or null if absent. */
    fun focalLength35(jpeg: ByteArray): Float? {
        val (t, end) = tiff(jpeg) ?: return null
        if (t + 8 > end) return null
        val little = jpeg.u8(t) == 0x49
        val exifIfd = tag(jpeg, t + u32(jpeg, t + 4, little), end, little, 0x8769)?.let { t + u32(jpeg, it + 8, little) } ?: return null
        val e = tag(jpeg, exifIfd, end, little, 0xA405) ?: return null
        return u16(jpeg, e + 8, little).takeIf { it > 0 }?.toFloat()
    }

    /** Finds the EXIF TIFF header: (start, end) or null. */
    private fun tiff(jpeg: ByteArray): Pair<Int, Int>? {
        if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8) return null
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg.u8(pos) != 0xFF) return null
            val marker = jpeg.u8(pos + 1)
            if (marker == 0xDA || marker == 0xD9) return null // image data reached: no EXIF
            val len = (jpeg.u8(pos + 2) shl 8) or jpeg.u8(pos + 3)
            val body = pos + 4
            if (marker == 0xE1 && len >= 8 && body + 6 <= jpeg.size &&
                jpeg.u8(body) == 'E'.code && jpeg.u8(body + 1) == 'x'.code &&
                jpeg.u8(body + 2) == 'i'.code && jpeg.u8(body + 3) == 'f'.code
            ) return (body + 6) to minOf(jpeg.size, body + len - 2)
            pos = pos + 2 + len
        }
        return null
    }

    /** Offset of the 12-byte entry for [id] in the IFD at [ifd], or null. */
    private fun tag(b: ByteArray, ifd: Int, end: Int, little: Boolean, id: Int): Int? {
        if (ifd < 0 || ifd + 2 > end) return null
        for (i in 0 until u16(b, ifd, little)) {
            val e = ifd + 2 + i * 12
            if (e + 12 > end) return null
            if (u16(b, e, little) == id) return e
        }
        return null
    }

    private fun ascii(b: ByteArray, t: Int, end: Int, little: Boolean, ifd: Int, id: Int): String? {
        val e = tag(b, ifd, end, little, id) ?: return null
        val n = u32(b, e + 4, little)
        if (n <= 0 || n > 64) return null
        val at = if (n <= 4) e + 8 else t + u32(b, e + 8, little)
        if (at < 0 || at + n > end) return null
        return CharArray(n) { b.u8(at + it).toChar() }.concatToString().trimEnd('\u0000', ' ')
    }

    private fun readTiff(b: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end) return 1
        val little = b.u8(tiff) == 0x49
        val ifd = tiff + u32(b, tiff + 4, little)
        if (ifd + 2 > end) return 1
        val count = u16(b, ifd, little)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > end) break
            if (u16(b, entry, little) == 0x0112) {
                val o = u16(b, entry + 8, little)
                return if (o in 1..8) o else 1
            }
        }
        return 1
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int =
        if (little) b.u8(at) or (b.u8(at + 1) shl 8) else (b.u8(at) shl 8) or b.u8(at + 1)

    private fun u32(b: ByteArray, at: Int, little: Boolean): Int =
        if (little) u16(b, at, true) or (u16(b, at + 2, true) shl 16)
        else (u16(b, at, false) shl 16) or u16(b, at + 2, false)
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
