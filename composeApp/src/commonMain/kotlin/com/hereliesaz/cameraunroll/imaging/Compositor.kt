package com.hereliesaz.cameraunroll.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns aligned strips into one image without ghosts or steps.
 *
 * Averaging overlapping photos doubles every line that is a pixel out and smears the rest, so
 * nothing is averaged. Each part of the result comes from one photo:
 *
 * 1. **Exposure**: per-strip color gains are solved so neighbors agree where they overlap.
 * 2. **Registration**: a slide cannot make two photos from different spots agree everywhere.
 *    Across each overlap the leftover disagreement is measured block by block, and each photo
 *    is bent half way toward its neighbor, fully at the cut and not at all at its own middle,
 *    so both show every mark at the same place where they meet.
 * 3. **Seams**: between neighbors, a cut runs top to bottom through the overlap where the two
 *    photos differ least and both look at the surface about equally squarely.
 * 4. **Two bands**: detail (what is left after a heavy blur) switches photo at the cut, within
 *    a couple of pixels, so it is never doubled; the blurred remainder (lighting, glare,
 *    color cast) fades from one photo to the next across the whole overlap, so no step shows.
 *
 * Strips are placed at whole-pixel offsets; the only resampling is the bend of step 2.
 *
 * @param xs,ys where each strip's top-left corner sits on the canvas; consecutive strips overlap left to right
 * @param loop the last strip continues into the first and the canvas is exactly one turn wide
 */
internal class Compositor(
    private val strips: List<WeightedStrip>,
    private val xs: IntArray,
    private val ys: IntArray,
    private val canvasW: Int,
    private val canvasH: Int,
    private val loop: Boolean,
) {
    private val n = strips.size

    /** A cut between strip [left] and the next one: its column per canvas row, in [left]'s unwrapped frame. */
    private class Seam(val x: IntArray, val feather: Float)

    fun run(progress: (Float) -> Unit): RgbaImage {
        val gains = gains()
        val pairs = if (loop) n else n - 1
        val planes = strips.map { Stitcher.Plane.of(it.image, max(8, (it.image.height * flowScale).roundToInt())).texture() }
        val flows = (0 until pairs).map { flow(it, planes) }
        progress(0.2f)
        // Cuts are made left to right, each kept to the right of the one before: where three
        // photos overlap, two of them can then never both claim the same detail.
        val seams = ArrayList<Seam>(pairs)
        for (i in 0 until pairs) seams += seam(i, gains, seams.lastOrNull(), flows[i])
        progress(0.3f)

        val size = canvasW * canvasH
        val low = FloatArray(size * 3)
        val lowW = FloatArray(size)
        val high = FloatArray(size * 3)
        val highW = FloatArray(size)
        for (i in 0 until n) {
            val left = when {
                i > 0 -> seams[i - 1].let { Seam(it.x, it.feather) }
                loop -> seams[n - 1].let { s -> Seam(IntArray(canvasH) { s.x[it] - canvasW }, s.feather) }
                else -> null
            }
            val right = if (i < pairs) seams[i] else null
            val leftFlow = if (i > 0) flows[i - 1] else if (loop) flows[n - 1].shifted(-canvasW) else null
            val bent = bend(i, left, right, leftFlow, if (i < pairs) flows[i] else null)
            paste(i, bent, gains[i], left, right, low, lowW, high, highW)
            progress(0.3f + 0.6f * (i + 1) / n)
        }

        val full = RgbaImage(canvasW, canvasH)
        val covered = IntArray(canvasH)
        for (k in 0 until size) {
            if (lowW[k] <= 0f) { full.pixels[k] = 0xFF000000.toInt(); continue }
            covered[k / canvasW]++
            val hw = highW[k]
            var p = 0xFF
            for (c in 0 until 3) {
                val v = low[3 * k + c] / lowW[k] + if (hw > 0f) high[3 * k + c] / hw else 0f
                p = (p shl 8) or v.roundToInt().coerceIn(0, 255)
            }
            full.pixels[k] = p
        }
        return cropToCovered(full, covered)
    }

    /**
     * Strips sit at slightly different heights; keep only the rows every strip reaches, so no
     * row is patched from a photo that was not meant to supply it.
     */
    private fun cropToCovered(img: RgbaImage, covered: IntArray): RgbaImage {
        val need = (canvasW * 0.995f).toInt()
        var top = max(0, (0 until n).maxOf { ys[it] })
        var bottom = min(canvasH - 1, (0 until n).minOf { strips[it].image.height + ys[it] } - 1)
        if (bottom - top < canvasH / 2) { top = 0; bottom = canvasH - 1 }
        while (top < bottom && covered[top] < need) top++
        while (bottom > top && covered[bottom] < need) bottom--
        val h = bottom - top + 1
        if (h == canvasH || h < canvasH / 2) return img
        return RgbaImage(canvasW, h, img.pixels.copyOfRange(top * canvasW, (bottom + 1) * canvasW))
    }

    /** Column of strip [j] in strip [i]'s unwrapped frame, where j follows i (wrapping at the end). */
    private fun nextX(i: Int): Int = if (i == n - 1) xs[0] + canvasW else xs[i + 1]

    private fun pixel(i: Int, cx: Int, canvasY: Int): Int {
        val img = strips[i].image
        val y = canvasY - ys[i]
        if (cx < 0 || cx >= img.width || y < 0 || y >= img.height) return 0
        return img.pixels[y * img.width + cx]
    }

    /**
     * Per-strip RGB gains. Each overlap says how two neighbors' exposures relate; the chain of
     * ratios is solved in logs, any disagreement around a loop spread evenly, and the average
     * gain pinned to 1 so the result is no brighter or darker than the photos.
     */
    private fun gains(): Array<FloatArray> {
        val pairs = if (loop) n else n - 1
        val lg = Array(n + 1) { FloatArray(3) }
        for (i in 0 until pairs) {
            val j = (i + 1) % n
            val x0 = max(xs[i], nextX(i))
            val x1 = min(xs[i] + strips[i].image.width, nextX(i) + strips[j].image.width)
            val sumI = DoubleArray(3)
            val sumJ = DoubleArray(3)
            var count = 0
            val step = max(1, min(x1 - x0, canvasH) / 160)
            var y = 0
            while (y < canvasH) {
                var x = x0
                while (x < x1) {
                    val a = pixel(i, x - xs[i], y)
                    val b = pixel(j, x - nextX(i), y)
                    if ((a ushr 24) >= 128 && (b ushr 24) >= 128 && usable(a) && usable(b)) {
                        for (c in 0 until 3) { sumI[c] += ((a shr (16 - 8 * c)) and 0xFF); sumJ[c] += ((b shr (16 - 8 * c)) and 0xFF) }
                        count++
                    }
                    x += step
                }
                y += step
            }
            for (c in 0 until 3) {
                val ratio = if (count < 50 || sumJ[c] < 1.0 || sumI[c] < 1.0) 0f else ln(sumI[c] / sumJ[c]).toFloat()
                lg[i + 1][c] = lg[i][c] + ratio
            }
        }
        return Array(n) { i ->
            FloatArray(3) { c ->
                val closing = if (loop) lg[n][c] * i / n else 0f
                val mean = (0 until n).map { lg[it][c] - if (loop) lg[n][c] * it / n else 0f }.average().toFloat()
                exp(lg[i][c] - closing - mean).coerceIn(0.5f, 2f)
            }
        }
    }

    /** Clipped shadows and highlights say nothing about exposure. */
    private fun usable(p: Int): Boolean {
        val m = max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF))
        return m in 12..245
    }

    /**
     * The cut between strip [i] and its successor: the top-to-bottom path through their overlap
     * (moving at most one column per row) with the least total difference between the two
     * photos, nudged toward where both see the surface equally squarely.
     */
    private fun seam(i: Int, gains: Array<FloatArray>, previous: Seam?, flow: Flow): Seam {
        val j = (i + 1) % n
        val a = strips[i]
        val b = strips[j]
        val x0 = max(xs[i], nextX(i))
        val x1 = min(xs[i] + a.image.width, nextX(i) + b.image.width)
        val w = x1 - x0
        if (w < 4) {
            // No real overlap: butt the strips together half way.
            val mid = (xs[i] + a.image.width + nextX(i)) / 2
            return Seam(IntArray(canvasH) { max(mid, (previous?.x?.get(it) ?: mid) + MIN_GAP) }, 2f)
        }
        val cost = FloatArray(w * canvasH)
        val f = FloatArray(2)
        for (y in 0 until canvasH) for (x in 0 until w) {
            val ca = x0 + x - xs[i]
            val cb = x0 + x - nextX(i)
            // Compare what the two photos will show here once each is bent half way to the other.
            flow.at(x0 + x, y, f)
            val pa = pixel(i, ca - (f[0] / 2f).roundToInt(), y - (f[1] / 2f).roundToInt())
            val pb = pixel(j, cb + (f[0] / 2f).roundToInt(), y + (f[1] / 2f).roundToInt())
            var bias = SQUARENESS * abs(a.columnWeight[ca] - b.columnWeight[cb])
            if (previous != null && x0 + x < previous.x[y] + MIN_GAP) bias += BEHIND
            cost[y * w + x] = bias + if ((pa ushr 24) < 128 || (pb ushr 24) < 128) MISSING else {
                var d = 0f
                for (c in 0 until 3) {
                    val s = 16 - 8 * c
                    d += abs(((pa shr s) and 0xFF) * gains[i][c] - ((pb shr s) and 0xFF) * gains[j][c])
                }
                d
            }
        }
        // Dynamic programming, bottom up: best total from each cell to the last row.
        for (y in canvasH - 2 downTo 0) for (x in 0 until w) {
            var best = cost[(y + 1) * w + x]
            if (x > 0) best = min(best, cost[(y + 1) * w + x - 1])
            if (x < w - 1) best = min(best, cost[(y + 1) * w + x + 1])
            cost[y * w + x] += best
        }
        val path = IntArray(canvasH)
        var x = (0 until w).minBy { cost[it] }
        for (y in 0 until canvasH) {
            if (y > 0) {
                var best = x
                if (x > 0 && cost[y * w + x - 1] < cost[y * w + best]) best = x - 1
                if (x < w - 1 && cost[y * w + x + 1] < cost[y * w + best]) best = x + 1
                x = best
            }
            path[y] = x0 + x
        }
        return Seam(path, (w * FEATHER).coerceIn(6f, max(6f, canvasH / 5f)))
    }

    /**
     * Adds strip [i] to the canvas: its blurred band weighted by a wide ramp either side of its
     * cuts, its detail band by a ramp only a few pixels wide. A whisper of weight everywhere the
     * strip has data fills any spot no cut assigned to anyone.
     */
    private fun paste(
        i: Int, img: RgbaImage, gain: FloatArray, left: Seam?, right: Seam?,
        low: FloatArray, lowW: FloatArray, high: FloatArray, highW: FloatArray,
    ) {
        val s = strips[i]
        val w = img.width
        val h = img.height
        val radius = max(3, canvasH / 48)
        val blurred = Array(3) { c ->
            val shift = 16 - 8 * c
            val v = FloatArray(w * h) { if ((img.pixels[it] ushr 24) < 128) -1f else ((img.pixels[it] shr shift) and 0xFF) * gain[c] }
            // Two box passes make a tent: no blocky halo around bright spots.
            val once = boxMean(v, w, h, radius, radius)
            for (k in once.indices) if (v[k] == -1f) once[k] = -1f
            boxMean(once, w, h, radius, radius)
        }
        for (cy in 0 until canvasH) {
            val y = cy - ys[i]
            if (y < 0 || y >= h) continue
            for (cx in 0 until w) {
                val p = img.pixels[y * w + cx]
                if ((p ushr 24) < 128) continue
                val ux = xs[i] + cx
                var x = ux
                if (loop) x = ((x % canvasW) + canvasW) % canvasW else if (x !in 0 until canvasW) continue
                var wide = 1f
                var narrow = 1f
                if (left != null) {
                    val d = ux - left.x[cy] + 0.5f
                    wide *= (0.5f + d / (2f * left.feather)).coerceIn(0f, 1f)
                    narrow *= (0.5f + d / (2f * DETAIL_FEATHER)).coerceIn(0f, 1f)
                }
                if (right != null) {
                    val d = right.x[cy] - ux - 0.5f
                    wide *= (0.5f + d / (2f * right.feather)).coerceIn(0f, 1f)
                    narrow *= (0.5f + d / (2f * DETAIL_FEATHER)).coerceIn(0f, 1f)
                }
                val fill = FILL * max(0.01f, s.columnWeight[cx])
                wide += fill
                narrow += fill
                val k = cy * canvasW + x
                for (c in 0 until 3) {
                    val l = blurred[c][y * w + cx]
                    val v = ((p shr (16 - 8 * c)) and 0xFF) * gain[c]
                    low[3 * k + c] += wide * l
                    high[3 * k + c] += narrow * (v - l)
                }
                lowW[k] += wide
                highW[k] += narrow
            }
        }
    }

    /** Working planes for registration are this fraction of full size. */
    private val flowScale = min(1f, FLOW_H.toFloat() / canvasH)

    /**
     * How far, at each point of an overlap, the right-hand photo's content sits from the
     * left-hand one's (in full pixels), on a coarse grid with bilinear lookup.
     * Node (gx, gy) is at canvas column x0 + gx·step, row gy·step, in the left strip's frame.
     */
    private class Flow(val x0: Int, val step: Int, val gw: Int, val gh: Int, val u: FloatArray, val v: FloatArray) {
        fun shifted(dx: Int) = Flow(x0 + dx, step, gw, gh, u, v)

        fun at(x: Int, y: Int, out: FloatArray) {
            if (gw == 0) { out[0] = 0f; out[1] = 0f; return }
            val fx = ((x - x0).toFloat() / step).coerceIn(0f, gw - 1f)
            val fy = (y.toFloat() / step).coerceIn(0f, gh - 1f)
            val ax = min(gw - 1, fx.toInt()); val bx = min(gw - 1, ax + 1)
            val ay = min(gh - 1, fy.toInt()); val by = min(gh - 1, ay + 1)
            val tx = fx - ax; val ty = fy - ay
            fun mix(a: FloatArray) = (a[ay * gw + ax] * (1 - tx) + a[ay * gw + bx] * tx) * (1 - ty) +
                (a[by * gw + ax] * (1 - tx) + a[by * gw + bx] * tx) * ty
            out[0] = mix(u); out[1] = mix(v)
        }
    }

    /**
     * Block matching across the overlap of strip [i] and its successor, on texture planes:
     * around each grid node, the small shift of the right photo that best matches the left.
     * Nodes with no confident match take their neighbors' values; the field is then smoothed,
     * because real disagreement (a slightly wrong distance or tilt) varies slowly.
     */
    private fun flow(i: Int, planes: List<Stitcher.Plane>): Flow {
        val j = (i + 1) % n
        val x0 = max(xs[i], nextX(i))
        val x1 = min(xs[i] + strips[i].image.width, nextX(i) + strips[j].image.width)
        val step = max(4, canvasH / 40)
        if (x1 - x0 < 2 * step) return Flow(x0, step, 0, 0, FloatArray(0), FloatArray(0))
        val gw = (x1 - x0) / step + 1
        val gh = canvasH / step + 1
        val u = FloatArray(gw * gh)
        val v = FloatArray(gw * gh)
        val ok = BooleanArray(gw * gh)
        val a = planes[i]
        val b = planes[j]
        val f = flowScale
        val reach = max(2, (FLOW_REACH * canvasH * f).roundToInt())
        val score = FloatArray((2 * reach + 1) * (2 * reach + 1))
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val cx = x0 + gx * step
            val cy = gy * step
            val ax = ((cx - xs[i]) * f).roundToInt(); val ay = ((cy - ys[i]) * f).roundToInt()
            val bx = ((cx - nextX(i)) * f).roundToInt(); val by = ((cy - ys[j]) * f).roundToInt()
            var best = -2f
            var bu = 0
            var bv = 0
            for (dv in -reach..reach) for (du in -reach..reach) {
                val sc = blockNcc(a, ax, ay, b, bx + du, by + dv)
                score[(dv + reach) * (2 * reach + 1) + du + reach] = sc
                // Prefer no shift on ties: featureless paint must not invent a bend.
                val biased = sc - 0.002f * (abs(du) + abs(dv))
                if (biased > best) { best = biased; bu = du; bv = dv }
            }
            if (best < FLOW_TRUST) continue
            // Parabolic sub-cell peak along each axis.
            fun peak(m: Float, c: Float, p: Float): Float {
                val curve = m - 2 * c + p
                return if (curve < -1e-6f && m > -1f && p > -1f) (0.5f * (m - p) / curve).coerceIn(-0.5f, 0.5f) else 0f
            }
            fun sc(du: Int, dv: Int) =
                if (abs(du) > reach || abs(dv) > reach) -1f else score[(dv + reach) * (2 * reach + 1) + du + reach]
            val c = sc(bu, bv)
            u[gy * gw + gx] = (bu + peak(sc(bu - 1, bv), c, sc(bu + 1, bv))) / f
            v[gy * gw + gx] = (bv + peak(sc(bu, bv - 1), c, sc(bu, bv + 1))) / f
            ok[gy * gw + gx] = true
        }
        fillAndSmooth(u, ok.copyOf(), gw, gh)
        fillAndSmooth(v, ok, gw, gh)
        return Flow(x0, step, gw, gh, u, v)
    }

    /** NCC of the (2·BLOCK+1)² block of [a] at (ax, ay) against [b]'s at (bx, by); -1 if too little data. */
    private fun blockNcc(a: Stitcher.Plane, ax: Int, ay: Int, b: Stitcher.Plane, bx: Int, by: Int): Float {
        var cnt = 0; var sa = 0f; var sb = 0f; var saa = 0f; var sbb = 0f; var sab = 0f
        for (dy in -BLOCK..BLOCK) {
            val ya = ay + dy; val yb = by + dy
            if (ya < 0 || ya >= a.h || yb < 0 || yb >= b.h) continue
            for (dx in -BLOCK..BLOCK) {
                val xa = ax + dx; val xb = bx + dx
                if (xa < 0 || xa >= a.w || xb < 0 || xb >= b.w) continue
                val va = a.v[ya * a.w + xa]; val vb = b.v[yb * b.w + xb]
                if (va < 0f || vb < 0f) continue
                cnt++; sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
            }
        }
        if (cnt < (2 * BLOCK + 1) * (2 * BLOCK + 1) * 6 / 10) return -1f
        val cov = sab - sa * sb / cnt
        val va = saa - sa * sa / cnt
        val vb = sbb - sb * sb / cnt
        if (va <= 1e-3f || vb <= 1e-3f) return -1f
        return cov / kotlin.math.sqrt(va * vb)
    }

    /** Unknown nodes take the mean of known neighbors (repeated until none are left), then four 3×3 blurs. */
    private fun fillAndSmooth(a: FloatArray, ok: BooleanArray, gw: Int, gh: Int) {
        if (ok.none { it }) { a.fill(0f); return }
        while (ok.any { !it }) {
            val next = ok.copyOf()
            for (y in 0 until gh) for (x in 0 until gw) {
                if (ok[y * gw + x]) continue
                var sum = 0f; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until gw && yy in 0 until gh && ok[yy * gw + xx]) { sum += a[yy * gw + xx]; c++ }
                }
                if (c > 0) { a[y * gw + x] = sum / c; next[y * gw + x] = true }
            }
            next.copyInto(ok)
        }
        repeat(4) {
            val src = a.copyOf()
            for (y in 0 until gh) for (x in 0 until gw) {
                var sum = 0f; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until gw && yy in 0 until gh) { sum += src[yy * gw + xx]; c++ }
                }
                a[y * gw + x] = sum / c
            }
        }
    }

    /**
     * Strip [i] bent to meet its neighbors: at its left cut it is read half the measured
     * disagreement one way, at its right cut half the other way, fading to nothing half way
     * between, where the photo is trusted as it is.
     */
    private fun bend(i: Int, left: Seam?, right: Seam?, leftFlow: Flow?, rightFlow: Flow?): RgbaImage {
        val img = strips[i].image
        if ((leftFlow == null || leftFlow.gw == 0) && (rightFlow == null || rightFlow.gw == 0)) return img
        val w = img.width
        val h = img.height
        val out = RgbaImage(w, h)
        val f = FloatArray(2)
        for (y in 0 until h) {
            val cy = (y + ys[i]).coerceIn(0, canvasH - 1)
            val l = left?.x?.get(cy) ?: xs[i]
            val r = right?.x?.get(cy) ?: (xs[i] + w)
            for (cx in 0 until w) {
                val ux = xs[i] + cx
                val t = if (r > l) (ux - l).toFloat() / (r - l) else 0.5f
                var ox = 0f
                var oy = 0f
                if (leftFlow != null && left != null) {
                    val k = (1f - 2f * t).coerceIn(0f, 1f)
                    if (k > 0f) { leftFlow.at(ux, cy, f); ox += k * f[0] / 2f; oy += k * f[1] / 2f }
                }
                if (rightFlow != null && right != null) {
                    val k = (2f * t - 1f).coerceIn(0f, 1f)
                    if (k > 0f) { rightFlow.at(ux, cy, f); ox -= k * f[0] / 2f; oy -= k * f[1] / 2f }
                }
                out.pixels[y * w + cx] =
                    if (ox == 0f && oy == 0f) img.pixels[y * w + cx] else img.sampleCubic(cx + 0.5f + ox, y + 0.5f + oy)
            }
        }
        return out
    }

    private companion object {
        const val FLOW_H = 320
        /** Largest local disagreement looked for, as a fraction of the height. */
        const val FLOW_REACH = 0.025f
        const val FLOW_TRUST = 0.45f
        const val BLOCK = 12
        /** Cost of cutting where one photo has no data, per pixel. */
        const val MISSING = 200f
        /** Cost of cutting to the left of the previous cut: effectively forbidden. */
        const val BEHIND = 1e6f
        const val MIN_GAP = 2
        /** Pull toward the column both strips see equally squarely (weights differ by 0..1). */
        const val SQUARENESS = 30f
        /** Lighting fades across this fraction of the overlap, either side of the cut. */
        const val FEATHER = 0.35f
        const val DETAIL_FEATHER = 1.5f
        const val FILL = 1e-4f
    }
}
