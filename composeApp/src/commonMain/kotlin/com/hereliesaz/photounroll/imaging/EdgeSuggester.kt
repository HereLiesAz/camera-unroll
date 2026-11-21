package com.hereliesaz.photounroll.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First guess at [CylinderGuides], meant to be corrected by hand. Two detectors:
 *
 * **Edges** (the usual case: a pillar running out of the frame, any background). The sides of
 * a cylinder are the two longest near-vertical edges with nothing as strong between them;
 * given a depth image, they are read from that instead (see [DepthSides]).
 * Rims are not visible, so they are placed at the frame's top and bottom and curved as
 * perspective dictates (see [Perspective.rim]).
 *
 * **Silhouette** (a whole object against a plain background). Separates the object from the
 * background by color distance from the frame border, takes the silhouette's side columns as
 * the edges, then fits each rim to the silhouette's top and bottom outline:
 * y(x) = rimY + k·cosθ with sinθ = (x - cx)/r. Wins when it finds one solid shape with both
 * rims inside the frame.
 */
object EdgeSuggester {
    internal const val WORK_SIZE = 320

    /**
     * @param focal35 35 mm-equivalent focal length from EXIF, if known.
     * @param horizon row of the horizon as a fraction of the height (0.5 for a level camera).
     */
    fun suggest(photo: RgbaImage, focal35: Float? = null, horizon: Float = 0.5f): CylinderGuides =
        detect(photo, focal35, horizon).guides

    /** @property rimsSeen the rims were found in the photo, not placed at the frame's ends. */
    class Suggestion(val guides: CylinderGuides, val rimsSeen: Boolean)

    /** @param depth depth image of [photo] as it is now (leveled), if there is one; preferred for the sides. */
    fun detect(photo: RgbaImage, focal35: Float? = null, horizon: Float = 0.5f, depth: RgbaImage? = null): Suggestion =
        silhouetteOf(photo)?.let { Suggestion(it, true) } ?: Suggestion(framed(photo, focal35, horizon, depth), false)

    private fun silhouetteOf(photo: RgbaImage): CylinderGuides? {
        val img = photo.scaledToMax(WORK_SIZE)
        return if (img.width < 16 || img.height < 16) null else silhouette(img)
    }

    private fun framed(photo: RgbaImage, focal35: Float?, horizon: Float, depth: RgbaImage?): CylinderGuides {
        val img = photo.scaledToMax(WORK_SIZE)
        if (img.width < 16 || img.height < 16) return CylinderGuides()
        val fromDepth = depth?.let { DepthSides.measure(it) }?.let { it.leftAt / depth.width to it.rightAt / depth.width }
        val (left, right) = fromDepth ?: sides(img) ?: return CylinderGuides()

        // Rims off-frame: put each just inside the frame, curved as the camera's distance dictates.
        val d = Perspective.distance((right - left) / 2f * photo.width, Perspective.focalPx(focal35, photo.width, photo.height))
        val (topY, topBulge) = Perspective.rim(RIM_MARGIN, horizon, d)
        val (bottomY, bottomBulge) = Perspective.rim(1f - RIM_MARGIN, horizon, d)
        return CylinderGuides(left, right, topY, topBulge, bottomY, bottomBulge).takeIf { it.isValid } ?: CylinderGuides()
    }

    private const val RIM_MARGIN = 0.03f

    /**
     * Left and right side of the cylinder as fractions of the width, or null if no pair of
     * long vertical edges stands out. Works on the middle rows, where a pillar is least
     * cluttered by ground and ceiling.
     */
    internal fun sides(img: RgbaImage): Pair<Float, Float>? {
        val w = img.width
        val h = img.height
        val y0 = h / 5
        val y1 = h * 4 / 5
        val rows = y1 - y0
        fun diff(a: Int, b: Int) =
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

        // Per column: mean horizontal color step (clipped, so a few lights cannot outvote a
        // long edge) × the fraction of rows that have a real step. Each row looks ±2 px
        // sideways so a slightly leaning edge still lands in one column.
        val edge = FloatArray(w)
        val sum = FloatArray(w)
        val hits = IntArray(w)
        val g = IntArray(w)
        for (y in y0 until y1) {
            for (x in 2 until w - 2) g[x] = min(GRAD_CAP, diff(img[x + 2, y], img[x - 2, y]))
            for (x in 2 until w - 2) {
                var m = 0
                for (k in max(2, x - 2)..min(w - 3, x + 2)) if (g[k] > m) m = g[k]
                sum[x] += m
                if (m > GRAD_REAL) hits[x]++
            }
        }
        for (x in 0 until w) edge[x] = sum[x] / rows * hits[x] / rows

        val m = max(2, w / 40)
        val minWidth = (0.15f * w).toInt()
        val maxWidth = (0.9f * w).toInt()
        var best = 0f
        var bestL = -1
        var bestR = -1
        for (l in m until w - m) {
            var inner = 0f // strongest edge strictly inside (l, r), grown as r advances
            for (r in l + minWidth until min(w - m, l + maxWidth)) {
                val in1 = r - 3 * m - 1
                if (in1 < l + 3 * m) continue
                inner = max(inner, edge[in1])
                val mid = (l + r) / 2f / w
                val centered = 1f - min(1f, abs(mid - 0.5f) / 0.35f)
                val score = (2f * min(edge[l], edge[r]) + 0.5f * (edge[l] + edge[r])) * (0.5f + 0.5f * centered) - inner
                if (score > best) { best = score; bestL = l; bestR = r }
            }
        }
        if (bestL < 0 || min(edge[bestL], edge[bestR]) < EDGE_MIN) return null
        return bestL.toFloat() / w to (bestR + 1f) / w
    }

    private const val SOLID = 0.75f
    internal const val GRAD_CAP = 120
    private const val GRAD_REAL = 30
    private const val EDGE_MIN = 6f

    /** The object as a whole against a plain background; null when there is no such thing. */
    private fun silhouette(img: RgbaImage): CylinderGuides? {
        val w = img.width
        val h = img.height
        val mask = foreground(img) ?: return null

        val colCount = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) colCount[x]++
        val peak = colCount.max()
        if (peak < h / 8) return null
        val left = colCount.indexOfFirst { it > peak / 2 }
        val right = colCount.indexOfLast { it > peak / 2 }
        if (right - left < w / 20) return null

        val cx = (left + right + 1) / 2f
        val r = (right + 1 - left) / 2f
        val xs = ArrayList<Float>()
        val tops = ArrayList<Float>()
        val bottoms = ArrayList<Float>()
        for (x in left + (r * 0.1f).toInt()..right - (r * 0.1f).toInt()) {
            val top = (0 until h).firstOrNull { mask[it * w + x] } ?: continue
            val bottom = (h - 1 downTo 0).first { mask[it * w + x] }
            val s = (x + 0.5f - cx) / r
            xs += sqrt(max(0f, 1f - s * s))
            tops += top.toFloat()
            bottoms += bottom + 1f
        }
        if (xs.size < 8) return null
        val (topY, topK) = robustFit(xs, tops)
        val (bottomY, bottomK) = robustFit(xs, bottoms)

        val g = CylinderGuides(
            left = left.toFloat() / w,
            right = (right + 1f) / w,
            topY = topY / h,
            topBulge = abs(topK) / h,
            bottomY = bottomY / h,
            bottomBulge = abs(bottomK) / h,
        ).normalized()
        if (!g.isValid || g.topY < RIM_MARGIN || g.bottomY > 1f - RIM_MARGIN) return null
        // A real silhouette fills its box and little else; a busy scene's "foreground" does neither.
        val y0 = (g.topY * h).toInt().coerceIn(0, h - 1)
        val y1 = (g.bottomY * h).toInt().coerceIn(y0 + 1, h)
        var inside = 0
        for (y in y0 until y1) for (x in left..right) if (mask[y * w + x]) inside++
        val total = mask.count { it }
        val box = (y1 - y0) * (right - left + 1)
        return g.takeIf { inside > SOLID * box && inside > SOLID * total }
    }

    /** Pixels that differ from the border's median color by more than its spread allows. */
    private fun foreground(img: RgbaImage): BooleanArray? {
        val w = img.width
        val h = img.height
        val band = max(1, min(w, h) / 30)
        val border = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < band || y < band || x >= w - band || y >= h - band) border += img[x, y]
        }
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        val bg = intArrayOf(16, 8, 0).map { s -> border.map { ch(it, s) }.sorted()[border.size / 2] }
        fun dist(p: Int) = intArrayOf(16, 8, 0).withIndex().sumOf { (i, s) -> abs(ch(p, s) - bg[i]) }
        val spread = border.map { dist(it) }.sorted()[border.size * 9 / 10]
        val threshold = max(40, spread * 2)
        val mask = BooleanArray(w * h) { dist(img.pixels[it]) > threshold }
        return mask.takeIf { m -> m.count { it } > w * h / 50 }
    }

    /** Least squares y = a + k·c, refit once without the worst residuals. */
    internal fun robustFit(c: List<Float>, y: List<Float>): Pair<Float, Float> {
        fun fit(idx: List<Int>): Pair<Float, Float> {
            val n = idx.size.toFloat()
            var sc = 0f; var sy = 0f; var scc = 0f; var scy = 0f
            for (i in idx) { sc += c[i]; sy += y[i]; scc += c[i] * c[i]; scy += c[i] * y[i] }
            val det = n * scc - sc * sc
            if (abs(det) < 1e-6f) return (sy / n) to 0f
            val k = (n * scy - sc * sy) / det
            return (sy - k * sc) / n to k
        }
        val all = c.indices.toList()
        val (a, k) = fit(all)
        val res = all.map { abs(y[it] - (a + k * c[it])) }
        val cut = res.sorted()[res.size * 4 / 5]
        val kept = all.filter { res[it] <= cut }
        return if (kept.size >= 4) fit(kept) else a to k
    }
}
