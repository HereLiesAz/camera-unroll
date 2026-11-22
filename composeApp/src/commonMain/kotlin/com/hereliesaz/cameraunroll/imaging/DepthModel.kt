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
    /** Where the pillar's sides were found, row by row (rows as offsets from mid-height). */
    private class Rows(val ys: List<Float>, val lefts: List<Float>, val rights: List<Float>)

    fun measure(depth: RgbaImage): Leveler.Sides? {
        val rows = rows(depth) ?: return null
        val (la, ls) = line(rows.ys, rows.lefts)
        val (ra, rs) = line(rows.ys, rows.rights)
        if (ra - la < depth.width / 12f) return null
        return Leveler.Sides(la, ls, ra, rs)
    }

    /**
     * How the pillar's width swells or pinches along its height, for pillars that are not true
     * cylinders (a barrel, a waist): its radius at [PROFILE_SAMPLES] evenly spaced rows from the
     * top of the frame to the bottom, relative to the straight-sided pillar [measure] fits.
     * Null when the sides are straight to within a few percent, which is nearly always.
     *
     * Only the bend of the sides is read. A steady taper is a different question, asked of
     * the depth and of the whole set: see [taper].
     */
    fun profile(depth: RgbaImage): FloatArray? {
        val rows = rows(depth, SHAPE_SLACK) ?: return null
        val h = depth.height
        val (la, ls) = line(rows.ys, rows.lefts)
        val (ra, rs) = line(rows.ys, rows.rights)
        // Width relative to the straight fit, as a parabola in u = row / half-height.
        val us = rows.ys.map { it / (h / 2f) }
        val ratios = rows.ys.indices.map { i ->
            (rows.rights[i] - rows.lefts[i]) / kotlin.math.max(1f, (ra - la) + (rs - ls) * rows.ys[i])
        }
        // Least squares for ratio = c0 + c2·u² (the straight fit already took the constant and
        // the slope; what is left of interest is the curve).
        var n = 0f; var s2 = 0f; var s4 = 0f; var r0 = 0f; var r2 = 0f
        for (i in us.indices) {
            val u2 = us[i] * us[i]
            n += 1f; s2 += u2; s4 += u2 * u2; r0 += ratios[i]; r2 += ratios[i] * u2
        }
        val det = n * s4 - s2 * s2
        if (kotlin.math.abs(det) < 1e-6f) return null
        val c2 = (n * r2 - s2 * r0) / det
        val c0 = (r0 - c2 * s2) / n
        // The curve must be worth correcting and must actually describe the rows.
        if (kotlin.math.abs(c2) < MIN_CURVE) return null
        var err = 0f
        for (i in us.indices) { val e = ratios[i] - (c0 + c2 * us[i] * us[i]); err += e * e }
        if (kotlin.math.sqrt(err / n) > kotlin.math.abs(c2) / 2f) return null
        // A swelling or a pinch shows at both ends alike. Something leaning on one end (plants
        // at the foot) widens that end only, and is not the pillar's shape.
        fun mean(pick: (Float) -> Boolean) = us.indices.filter { pick(us[it]) }.map { ratios[it] }.takeIf { it.size >= 3 }?.average()?.toFloat()
        val middle = mean { kotlin.math.abs(it) < 0.3f } ?: return null
        val upper = mean { it < -0.4f } ?: return null
        val lower = mean { it > 0.4f } ?: return null
        if ((upper - middle) * c2 <= 0f || (lower - middle) * c2 <= 0f) return null
        if (kotlin.math.min(kotlin.math.abs(upper - middle), kotlin.math.abs(lower - middle)) < MIN_CURVE / 4f) return null
        return FloatArray(PROFILE_SAMPLES) {
            val u = -1f + 2f * it / (PROFILE_SAMPLES - 1)
            ((c0 + c2 * u * u) / c0).coerceIn(0.6f, 1.4f)
        }
    }

    /**
     * What one photo says about whether the pillar itself narrows or widens along its
     * height, before the set of photos is asked ([taperConsensus]).
     *
     * Sides that converge mean one of two things: the pillar tapers, or the phone was tipped.
     * Tipped, the narrow end is narrow because it is farther away, and the depth image shows
     * it receding by the same proportion. Tapered, it is narrow though no farther. So the
     * taper is the part of the width's change that the depth's change does not account for.
     */
    fun taper(depth: RgbaImage): Taper? {
        val rows = rows(depth, TAPER_SLACK) ?: return null
        val w = depth.width
        val h = depth.height
        val (la, ls) = line(rows.ys, rows.lefts)
        val (ra, rs) = line(rows.ys, rows.rights)
        if (ra - la < w / 12f) return null
        val widthChange = (rs - ls) * h / (ra - la)
        // Nearness down the pillar's middle, as a logarithm so that its slope is a proportion.
        val logs = rows.ys.indices.map { i ->
            val y = (rows.ys[i] + h / 2f).toInt().coerceIn(0, h - 1)
            val mid = (rows.lefts[i] + rows.rights[i]) / 2f
            val reach = kotlin.math.max(1, ((rows.rights[i] - rows.lefts[i]) * 0.1f).toInt())
            var sum = 0f
            for (x in (mid - reach).toInt()..(mid + reach).toInt()) sum += depth.pixels[y * w + x.coerceIn(0, w - 1)] and 0xFF
            kotlin.math.ln(kotlin.math.max(1f, sum / (2 * reach + 1)))
        }
        val (_, slope) = line(rows.ys, logs)
        return Taper(widthChange, widthChange - slope * h)
    }

    /**
     * @property widthChange how much wider the pillar is at the bottom of the frame than at the
     *   top, as a share of its width (negative: narrower)
     * @property unexplained the part of [widthChange] that the pillar's receding does not account for
     */
    class Taper(val widthChange: Float, val unexplained: Float)

    /**
     * The one taper a set of photos of the same pillar agrees on (as [Taper.widthChange]), or 0.
     *
     * Whether it tapers: a pillar's taper is in every photo of it, and a single reading is
     * rough, so most photos must show an unexplained change the same way, and a large one.
     * By how much: the depth image is too coarse a ruler for that. But the phone is tipped
     * differently in every photo and the pillar tapers the same in all, so the taper is the
     * width change the photos have in common: their middle value.
     */
    fun taperConsensus(tapers: List<Taper?>): Float {
        val found = tapers.filterNotNull()
        if (found.size < 3 || found.size < MAJORITY * tapers.size) return 0f
        val middle = found.map { it.unexplained }.sorted()[found.size / 2]
        if (kotlin.math.abs(middle) < MIN_TAPER) return 0f
        val agree = found.count { it.unexplained * middle > 0f && kotlin.math.abs(it.unexplained) > MIN_TAPER / 2f }
        if (agree < AGREED * found.size) return 0f
        val common = found.map { it.widthChange }.sorted()[found.size / 2]
        return if (common * middle > 0f) common.coerceIn(-MAX_TAPER, MAX_TAPER) else 0f
    }

    /** The radius down the frame ([PROFILE_SAMPLES] values, top to bottom) of a pillar with that [taper]. */
    fun taperProfile(taper: Float): FloatArray? =
        if (taper == 0f) null else FloatArray(PROFILE_SAMPLES) { 1f + taper * (it.toFloat() / (PROFILE_SAMPLES - 1) - 0.5f) }

    private const val TAPER_SLACK = 0.45f
    /** Unexplained width change, top of frame to bottom, below which a pillar counts as straight. */
    private const val MIN_TAPER = 0.2f
    private const val MAX_TAPER = 0.8f
    private const val AGREED = 0.75f

    const val PROFILE_SAMPLES = 9

    /**
     * One profile for a whole set of photos of the same pillar. A pillar's shape is in every
     * photo of it, whereas clutter that happens to widen both ends of one frame is not: unless
     * a clear majority report the same kind of shape (swelling or pinched), the pillar is
     * taken as straight. What they agree on is their middle value, sample by sample.
     */
    fun consensus(profiles: List<FloatArray?>): FloatArray? {
        val found = profiles.filterNotNull()
        if (profiles.size < 2 || found.isEmpty()) return null
        // Ends wider than the middle (first sample > 1) is a waist; narrower is a barrel.
        val waists = found.filter { it.first() > 1f }
        val agree = if (waists.size * 2 >= found.size) waists else found - waists.toSet()
        if (agree.size < MAJORITY * profiles.size) return null
        return FloatArray(PROFILE_SAMPLES) { k -> agree.map { it[k] }.sorted()[agree.size / 2] }
    }

    private const val MAJORITY = 0.6f

    /** Radius change from mid-height to the frame's ends below which a pillar counts as straight. */
    private const val MIN_CURVE = 0.04f

    /** The pillar's side positions in every row where it stands alone, or null if there is no pillar. */
    private fun rows(depth: RgbaImage, slack: Float = WIDTH_SLACK): Rows? {
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
        // near as it is and widen those rows; a pillar's width changes slowly, so rows far from
        // the typical width are not the pillar alone.
        val typical = ys.indices.map { rights[it] - lefts[it] }.sorted()[ys.size / 2]
        val clean = ys.indices.filter { kotlin.math.abs(rights[it] - lefts[it] - typical) <= slack * typical }
        if (clean.size < 12) return null
        return Rows(clean.map { ys[it] }, clean.map { lefts[it] }, clean.map { rights[it] })
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
    /** Looser, when the question is whether the width itself changes. */
    private const val SHAPE_SLACK = 0.2f

    /** A pillar this dim is not the nearest thing in the row: something is in front. */
    private const val NEAR = 90
    /** Drop in depth over two pixels that counts as leaving the surface (of 255). */
    private const val CLIFF = 18
    private const val FALLEN = 64
}
