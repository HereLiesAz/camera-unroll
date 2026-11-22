package com.hereliesaz.cameraunroll.imaging

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Monocular depth from the bundled Depth Anything V2 Small network (Apache-2.0), where the
 * platform has a runtime for it. A pillar is the nearest thing in its photo and stands out of
 * a depth map as a bright column whatever its paint, the light or the clutter behind it,
 * which is what [DepthSides] reads.
 */
expect object DepthModel {
    /**
     * Depth of [photo] as a small gray image (nearer = brighter, stretched to the full range),
     * or null when no runtime is available here or the model fails; callers fall back to color.
     */
    suspend fun estimate(photo: RgbaImage): RgbaImage?
}

/** What every platform's runtime needs around the network itself. */
object DepthIo {
    const val MODEL_FILE = "depth_anything_v2_small_q.onnx"
    const val INPUT_NAME = "pixel_values"

    /** The network takes sides in multiples of its 14 px patch; this is plenty to find a pillar. */
    private const val LONG_SIDE = 392
    private const val PATCH = 14

    class Input(val width: Int, val height: Int, val chw: FloatArray)

    /** [photo] scaled to the network's size, as planar RGB normalized the way it was trained. */
    fun input(photo: RgbaImage): Input {
        val s = LONG_SIDE.toFloat() / max(photo.width, photo.height)
        val w = max(PATCH, (photo.width * s / PATCH).roundToInt() * PATCH)
        val h = max(PATCH, (photo.height * s / PATCH).roundToInt() * PATCH)
        val img = photo.resized(w, h)
        val n = w * h
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = img.pixels[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
            out[n + i] = (((p shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
            out[2 * n + i] = ((p and 0xFF) / 255f - 0.406f) / 0.225f
        }
        return Input(w, h, out)
    }

    /** The network's output (relative inverse depth, any scale) as a gray image, nearest = white. */
    fun image(values: FloatArray, width: Int, height: Int): RgbaImage? {
        if (values.size < width * height) return null
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in 0 until width * height) { val v = values[i]; if (v < lo) lo = v; if (v > hi) hi = v }
        if (!(hi - lo > 1e-6f)) return null
        return RgbaImage(width, height, IntArray(width * height) {
            val g = ((values[it] - lo) / (hi - lo) * 255f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        })
    }
}

/**
 * The pillar's two sides, read from a depth image ([DepthModel.estimate]).
 *
 * The pillar is the bright column nearest the middle. In each row, starting inside it and
 * walking outward, a side is where depth falls off a cliff (the background, or a pole behind);
 * the gentle darkening of a round surface turning away is not a cliff and is walked across.
 * A line through each side's points gives where it is and how it leans.
 */
object DepthSides {
    fun measure(depth: RgbaImage): Leveler.Sides? {
        val w = depth.width
        val h = depth.height
        if (w < 16 || h < 16) return null
        fun d(x: Int, y: Int) = depth.pixels[y * w + x] and 0xFF

        // Seed: the column that is nearest over the middle rows, leaning toward the frame's center.
        var seed = -1
        var best = -1f
        for (x in 2 until w - 2) {
            var sum = 0
            for (y in h / 5 until h * 4 / 5) sum += d(x, y)
            val centered = 1f - kotlin.math.min(1f, kotlin.math.abs(x.toFloat() / w - 0.5f) / 0.5f)
            val score = sum * (0.6f + 0.4f * centered)
            if (score > best) { best = score; seed = x }
        }
        if (seed < 0) return null

        val ys = ArrayList<Float>()
        val lefts = ArrayList<Float>()
        val rights = ArrayList<Float>()
        var y = (h * 0.08f).toInt()
        while (y < (h * 0.92f).toInt()) {
            val plateau = d(seed, y)
            if (plateau >= NEAR) {
                fun walk(dir: Int): Int? {
                    var x = seed
                    while (x in 2 until w - 2) {
                        if (d(x, y) - d(x + 2 * dir, y) > CLIFF || d(x + dir, y) < plateau - FALLEN) return x
                        x += dir
                    }
                    return null
                }
                val l = walk(-1)
                val r = walk(1)
                if (l != null && r != null && r - l >= w / 12) { ys += y - h / 2f; lefts += l.toFloat(); rights += r + 1f }
            }
            y += 3
        }
        if (ys.size < 12) return null
        // Things standing against the pillar (plants at its foot, a beam at its head) are as
        // near as it is and widen those rows; a pillar's width barely changes, so rows that
        // disagree with the typical width are not the pillar alone.
        val typical = ys.indices.map { rights[it] - lefts[it] }.sorted()[ys.size / 2]
        val clean = ys.indices.filter { kotlin.math.abs(rights[it] - lefts[it] - typical) <= WIDTH_SLACK * typical }
        if (clean.size < 12) return null
        val (la, ls) = line(clean.map { ys[it] }, clean.map { lefts[it] })
        val (ra, rs) = line(clean.map { ys[it] }, clean.map { rights[it] })
        if (ra - la < w / 12f) return null
        return Leveler.Sides(la, ls, ra, rs)
    }

    /** Least squares x = a + k·y, refit three times without points far off the line. */
    private fun line(ys: List<Float>, xs: List<Float>): Pair<Float, Float> {
        var keep = ys.indices.toList()
        var a = 0f
        var k = 0f
        repeat(4) {
            val n = keep.size.toFloat()
            var sy = 0f; var sx = 0f; var syy = 0f; var syx = 0f
            for (i in keep) { sy += ys[i]; sx += xs[i]; syy += ys[i] * ys[i]; syx += ys[i] * xs[i] }
            val det = n * syy - sy * sy
            if (kotlin.math.abs(det) < 1e-6f) return (sx / n) to 0f
            k = (n * syx - sy * sx) / det
            a = (sx - k * sy) / n
            val res = keep.map { kotlin.math.abs(xs[it] - (a + k * ys[it])) }
            val cut = kotlin.math.max(1.5f, 2f * res.sorted()[res.size / 2])
            val next = keep.filterIndexed { j, _ -> res[j] <= cut }
            if (next.size < 8 || next.size == keep.size) return a to k
            keep = next
        }
        return a to k
    }

    private const val WIDTH_SLACK = 0.1f

    /** A pillar this dim is not the nearest thing in the row: something is in front. */
    private const val NEAR = 90
    /** Drop in depth over two pixels that counts as leaving the surface (of 255). */
    private const val CLIFF = 18
    private const val FALLEN = 64
}
