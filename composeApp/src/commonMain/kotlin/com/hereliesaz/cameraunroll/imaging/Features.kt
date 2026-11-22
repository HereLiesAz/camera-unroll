package com.hereliesaz.cameraunroll.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A second way to tell how one strip sits against another, independent of correlation.
 *
 * Correlation slides whole strips over each other and asks where they agree most overall. That
 * can be led astray when most of the overlap is bare paint and the telling marks are few, or
 * when two parts of the surface look broadly alike. Here the marks are found first (corners:
 * points where brightness changes in every direction), each is described by the texture around
 * it, marks are paired between the strips by that description alone, and every pair votes for
 * the slide that would bring it together. Wrong pairs vote all over; right ones vote together,
 * so the winning slide and how many voted for it say both where and how surely.
 */
internal object Features {
    class Mark(val x: Int, val y: Int, val look: FloatArray)

    /** @property votes how many marks agreed; a handful is chance, dozens is not. */
    class Match(val dx: Float, val dy: Float, val votes: Int)

    /** The strongest corners of a texture plane (see [Stitcher.Plane.texture]), spread out, each with its look. */
    fun marks(plane: Stitcher.Plane): List<Mark> {
        val w = plane.w
        val h = plane.h
        val raw = plane.raw ?: plane.v
        if (w < 2 * BORDER + 3 || h < 2 * BORDER + 3) return emptyList()
        // Harris corner response from the brightness gradients.
        val ix = FloatArray(w * h)
        val iy = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val k = y * w + x
            if (plane.v[k] < 0f || plane.v[k - 1] < 0f || plane.v[k + 1] < 0f || plane.v[k - w] < 0f || plane.v[k + w] < 0f) continue
            ix[k] = (raw[k + 1] - raw[k - 1]) / 2f
            iy[k] = (raw[k + w] - raw[k - w]) / 2f
        }
        val response = FloatArray(w * h)
        for (y in BORDER until h - BORDER) for (x in BORDER until w - BORDER) {
            var a = 0f; var b = 0f; var c = 0f
            for (dy in -2..2) for (dx in -2..2) {
                val k = (y + dy) * w + x + dx
                a += ix[k] * ix[k]; b += ix[k] * iy[k]; c += iy[k] * iy[k]
            }
            val trace = a + c
            response[y * w + x] = a * c - b * b - 0.05f * trace * trace
        }
        // Local maxima only, strongest first, none within a few pixels of a stronger one.
        val peaks = ArrayList<Int>()
        for (y in BORDER until h - BORDER) for (x in BORDER until w - BORDER) {
            val r = response[y * w + x]
            if (r <= 0f) continue
            var top = true
            loop@ for (dy in -SPREAD..SPREAD) for (dx in -SPREAD..SPREAD) {
                val yy = y + dy; val xx = x + dx
                if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                if (response[yy * w + xx] > r) { top = false; break@loop }
            }
            if (top) peaks += y * w + x
        }
        peaks.sortByDescending { response[it] }
        return peaks.take(MAX_MARKS).mapNotNull { k -> look(plane, k % w, k / w)?.let { Mark(k % w, k / w, it) } }
    }

    /** The texture around a point, mean removed and scaled to unit length; null near missing data. */
    private fun look(plane: Stitcher.Plane, x: Int, y: Int): FloatArray? {
        val out = FloatArray((2 * PATCH + 1) * (2 * PATCH + 1))
        var i = 0
        var sum = 0f
        for (dy in -PATCH..PATCH) for (dx in -PATCH..PATCH) {
            val v = plane.v[(y + dy) * plane.w + x + dx]
            if (v < 0f) return null
            out[i++] = v
            sum += v
        }
        val mean = sum / out.size
        var norm = 0f
        for (k in out.indices) { out[k] -= mean; norm += out[k] * out[k] }
        if (norm < 1e-3f) return null
        val scale = 1f / sqrt(norm)
        for (k in out.indices) out[k] *= scale
        return out
    }

    /**
     * Where [b]'s origin sits in [a]'s frame (plane pixels), by vote; null when too few marks
     * agree on anything. [maxDy] bounds the vertical slide considered, in plane rows.
     */
    fun match(a: List<Mark>, b: List<Mark>, maxDy: Int): Match? {
        if (a.size < MIN_VOTES || b.size < MIN_VOTES) return null
        fun best(from: Mark, among: List<Mark>, flip: Int): Int {
            var top = -2f
            var second = -2f
            var at = -1
            for (j in among.indices) {
                val m = among[j]
                if (abs((from.y - m.y) * flip) > maxDy) continue
                var dot = 0f
                for (k in from.look.indices) dot += from.look[k] * m.look[k]
                if (dot > top) { second = top; top = dot; at = j } else if (dot > second) second = dot
            }
            // Alike enough, and clearly more alike than the runner-up: a mark that resembles
            // two places says nothing about which.
            return if (top >= ALIKE && top - second >= DISTINCT) at else -1
        }
        val votes = ArrayList<Pair<Int, Int>>()
        for (i in a.indices) {
            val j = best(a[i], b, 1)
            if (j < 0 || best(b[j], a, 1) != i) continue // both must pick each other
            votes += (a[i].x - b[j].x) to (a[i].y - b[j].y)
        }
        if (votes.size < MIN_VOTES) return null
        // The slide most votes fall near.
        var winner = votes[0]
        var count = 0
        for (v in votes) {
            val c = votes.count { abs(it.first - v.first) <= NEAR && abs(it.second - v.second) <= NEAR }
            if (c > count) { count = c; winner = v }
        }
        if (count < MIN_VOTES) return null
        val agreeing = votes.filter { abs(it.first - winner.first) <= NEAR && abs(it.second - winner.second) <= NEAR }
        return Match(
            agreeing.map { it.first }.average().toFloat(),
            agreeing.map { it.second }.average().toFloat(),
            agreeing.size,
        )
    }

    private const val PATCH = 6
    private const val BORDER = PATCH + 3
    private const val SPREAD = 4
    private const val MAX_MARKS = 350
    private const val ALIKE = 0.7f
    private const val DISTINCT = 0.06f
    private const val NEAR = 3
    /** Fewer agreeing marks than this is what chance alone produces. */
    const val MIN_VOTES = 6
}
