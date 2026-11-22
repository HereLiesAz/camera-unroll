package com.hereliesaz.cameraunroll.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Orders unordered photos of one subject by where their flattened strips overlap, and
 * finds where a video that circled past 360° has come back to its start. Works on the same
 * strips the stitcher uses, so "adjacent" means exactly what stitching needs it to mean.
 */
object Arranger {
    private const val PLANE_W = 96
    private const val PLANE_H = 32

    /**
     * Returns indices of [strips] left to right along the surface, by where their content says
     * they sit (see [Stitcher.sequence]); a full walk-around is cut wherever its two ends meet.
     *
     * [rank] (optional, from metadata: capture time or file-name sequence) decides only when
     * the pictures cannot: with no overlap anywhere, the rank order is returned as is.
     */
    fun order(strips: List<RgbaImage>, progress: (Float) -> Unit = {}, rank: IntArray? = null): IntArray {
        val n = strips.size
        val byRank = rank?.let { r -> (0 until n).sortedBy { r[it] }.toIntArray() } ?: IntArray(n) { it }
        if (n <= 2) return byRank
        // One height for all, as the stitcher will see them.
        val h = min(Stitcher.FINE_H, strips.map { it.height }.sorted()[n / 2])
        val seq = Stitcher.sequence(strips.map { it.resized(max(2, (it.width.toFloat() * h / it.height).roundToInt()), h) }, progress = progress)
        return if (seq.matched) seq.order else byRank
    }

    /**
     * For a sequence that may have gone past a full turn: the number of leading frames to
     * keep so the last frame stops just short of coming back around to the first.
     * Returns `strips.size` when no frame in the second half sees what the first sees.
     */
    fun framesInOneTurn(strips: List<RgbaImage>): Int {
        val n = strips.size
        if (n < 6) return n
        val first = plane(strips[0])
        var best = -1
        var bestScore = SAME_VIEW
        for (k in n / 2 until n) {
            val p = plane(strips[k])
            val s = sameView(first, p)
            if (s > bestScore) { bestScore = s; best = k }
        }
        // One frame short of the match: never past the start, so the seam still slides forward.
        return if (best < 0) n else max(2, best - 1)
    }

    private const val SAME_VIEW = 0.8f

    /**
     * Texture-only plane: local average removed, then local contrast equalized. A cylinder is
     * lit and foreshortened differently in every view; without this, smooth shading dominates
     * the comparison and any two views look alike at the wrong overlap.
     */
    internal fun plane(img: RgbaImage): Stitcher.Plane {
        val src = Stitcher.Plane.fixed(img, PLANE_W, PLANE_H)
        val w = src.w
        val h = src.h
        val mean = boxMean(src.v, w, h, BOX_X, BOX_Y)
        val dev = FloatArray(w * h) { if (src.v[it] < 0f) -1f else src.v[it] - mean[it] }
        val energy = boxMean(FloatArray(w * h) { if (dev[it] == -1f && src.v[it] < 0f) -1f else dev[it] * dev[it] }, w, h, BOX_X, BOX_Y)
        val out = FloatArray(w * h) {
            if (src.v[it] < 0f) -1f
            // 256 keeps data cells non-negative: -1 is reserved for "no data".
            else 256f + 40f * dev[it] / (kotlin.math.sqrt(energy[it]) + 3f)
        }
        return Stitcher.Plane(w, h, out)
    }

    private const val BOX_X = 10
    private const val BOX_Y = 6

    /** Best NCC with the two planes nearly on top of each other (small slide, tiny tilt). */
    private fun sameView(a: Stitcher.Plane, b: Stitcher.Plane): Float {
        val maxDx = max(1, (0.06f * min(a.w, b.w)).roundToInt())
        val maxDy = max(1, (Stitcher.MAX_DY * PLANE_H).roundToInt())
        var best = -1f
        for (dy in -maxDy..maxDy) for (dx in -maxDx..maxDx) {
            val (s, n) = Stitcher.ncc(a, b, dx, dy, max(16, a.w * a.h / 3))
            if (n > 0 && s > best) best = s
        }
        return best
    }

}
