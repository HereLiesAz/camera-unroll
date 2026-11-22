package com.hereliesaz.cameraunroll.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mean over a (2rx+1)×(2ry+1) box, ignoring cells marked -1 (no data). Running sums: O(w·h). */
internal fun boxMean(v: FloatArray, w: Int, h: Int, rx: Int, ry: Int): FloatArray {
    val sumX = FloatArray(w * h)
    val cntX = FloatArray(w * h)
    for (y in 0 until h) {
        var sum = 0f; var c = 0
        for (k in 0..min(w - 1, rx)) { val t = v[y * w + k]; if (t != -1f) { sum += t; c++ } }
        for (x in 0 until w) {
            sumX[y * w + x] = sum; cntX[y * w + x] = c.toFloat()
            val add = x + rx + 1
            if (add < w) { val t = v[y * w + add]; if (t != -1f) { sum += t; c++ } }
            val drop = x - rx
            if (drop >= 0) { val t = v[y * w + drop]; if (t != -1f) { sum -= t; c-- } }
        }
    }
    val out = FloatArray(w * h)
    for (x in 0 until w) {
        var sum = 0f; var c = 0f
        for (k in 0..min(h - 1, ry)) { sum += sumX[k * w + x]; c += cntX[k * w + x] }
        for (y in 0 until h) {
            out[y * w + x] = if (c <= 0f) 0f else sum / c
            val add = y + ry + 1
            if (add < h) { sum += sumX[add * w + x]; c += cntX[add * w + x] }
            val drop = y - ry
            if (drop >= 0) { sum -= sumX[drop * w + x]; c -= cntX[drop * w + x] }
        }
    }
    return out
}

/**
 * @property closedLoop true when the last strip was aligned back onto the first, so the
 *   image's right edge continues into its left edge and [image].width is exactly one turn.
 */
class StitchResult(
    val image: RgbaImage,
    val closedLoop: Boolean,
    val pxPerRadian: Float?,
)

/**
 * Aligns strips that each overlap their neighbor by translation only, then feather-blends.
 * Shared by both modes: unrolled cylinder faces and cylindrically projected frames
 * both differ from their neighbors by a slide.
 */
object Stitcher {
    private const val COARSE_H = 48
    internal const val FINE_H = 192
    internal const val MIN_OVERLAP = 0.08f
    internal const val MAX_DY = 0.1f

    /**
     * @param maxDy how far (fraction of strip height) neighbors may sit above or below each other.
     */
    fun stitch(input: List<WeightedStrip>, closeLoop: Boolean, maxDy: Float = MAX_DY, progress: (Float) -> Unit = {}): StitchResult {
        require(input.isNotEmpty()) { "Nothing to stitch" }
        val targetH = input.map { it.image.height }.sorted()[input.size / 2]
        var strips = input.map { it.scaledToHeight(targetH) }

        // Where each strip sits is read from the strips themselves (see [sequence]); the order
        // they arrive in, and which way the photographer walked, do not matter.
        val seq = sequence(strips.map { it.image }, maxDy) { progress(0.6f * it) }
        // A photo taken almost where another was adds no surface, only one more cut to hide:
        // strips that advance less than a tenth of a width past the last kept one are left out.
        val kept = ArrayList<Int>()
        var links: List<Link> = ArrayList<Link>().also { out ->
            var dx = 0f
            var dy = 0f
            var score = 1f
            seq.order.forEachIndexed { k, index ->
                if (k == 0) { kept += index; return@forEachIndexed }
                dx += seq.links[k - 1].dx; dy += seq.links[k - 1].dy; score = min(score, seq.links[k - 1].score)
                val last = k == seq.order.size - 1
                if (dx >= MIN_STEP * strips[kept.last()].image.width || (last && kept.size == 1)) {
                    kept += index
                    out += Link(dx, dy, score)
                    dx = 0f; dy = 0f; score = 1f
                }
            }
        }
        strips = kept.map { strips[it] }

        // "Full turn" is a hope, not a fact: if the last strip does not really continue into
        // the first, wrapping would fold the whole image onto itself. Leave it open instead.
        var loop = closeLoop && strips.size >= 2
        if (loop) {
            val closing = align(seq.planes[kept.last()], seq.planes[kept.first()], forwardOnly = true, maxDy)
            // Where the strips' scale is known, so is one turn's width; a "loop" far from that
            // width is two look-alike ends (bare paint matches bare paint), not a closed circle.
            val scales = strips.mapNotNull { it.pxPerRadian }
            val turn = if (scales.size == strips.size) 2f * kotlin.math.PI.toFloat() * scales.sorted()[scales.size / 2] else null
            val around = links.sumOf { it.dx.toDouble() }.toFloat() + closing.dx
            val plausible = turn == null || kotlin.math.abs(around / turn - 1f) <= TURN_SLACK
            if (closing.score >= CLOSES_LOOP && plausible) links = links + closing else loop = false
        }

        val n = strips.size
        // Offsets accumulate as floats and are rounded once per strip, so measurement error
        // stays per-strip instead of piling up around a loop.
        val exact = FloatArray(n)
        val ysF = FloatArray(n)
        for (i in 1 until n) {
            exact[i] = exact[i - 1] + links[i - 1].dx
            ysF[i] = ysF[i - 1] + links[i - 1].dy
        }
        val canvasW: Int
        if (loop) {
            canvasW = max(1, links.sumOf { it.dx.toDouble() }.roundToInt())
            // Spread vertical drift so the seam closes without a step.
            val drift = links.sumOf { it.dy.toDouble() }.toFloat()
            for (i in 0 until n) ysF[i] -= drift * i / n
        } else {
            canvasW = (0 until n).maxOf { exact[it].roundToInt() + strips[it].image.width }
        }
        val yMean = ysF.average().toFloat()
        // Whole-pixel placement: strips are pasted, never resampled again.
        val xs = IntArray(n) { exact[it].roundToInt() }
        val ys = IntArray(n) { (ysF[it] - yMean).roundToInt() }

        val out = Compositor(strips, xs, ys, canvasW, targetH, loop).run { progress(0.6f + 0.4f * it) }
        progress(1f)

        val ppr = when {
            loop -> (canvasW / (2 * kotlin.math.PI)).toFloat()
            else -> strips.mapNotNull { it.pxPerRadian }.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }
        }
        return StitchResult(out, loop, ppr?.takeIf { strips.all { s -> s.pxPerRadian != null } })
    }

    private const val MIN_STEP = 0.1f

    /** A closing match weaker than this is no match: the photos do not go all the way around. */
    private const val CLOSES_LOOP = 0.35f
    private const val TURN_SLACK = 0.2f

    /** Offset of strip b's origin relative to strip a's, in a's pixels; [score] is the match's correlation. */
    class Link(val dx: Float, val dy: Float, val score: Float = 1f)

    /** A strip's two working planes, built once however many neighbors it is compared with. */
    internal class Planes(val coarse: Plane, val fine: Plane, val fullH: Int)

    internal fun planes(img: RgbaImage): Planes =
        Planes(Plane.of(img, COARSE_H).texture(), Plane.of(img, min(FINE_H, img.height)).texture(), img.height)

    /**
     * @property order indices into the input, left to right along the surface
     * @property links offset of each strip in [order] from the one before it
     * @property matched false when nothing overlapped anything: [order] is then the input order
     */
    internal class Sequence(val order: IntArray, val links: List<Link>, val planes: List<Planes>, val matched: Boolean)

    /**
     * Works out where strips of one height sit relative to each other. Every pair (or, in a
     * long run, every pair of near neighbors) is aligned; the most trustworthy matches that
     * connect everything (a maximum spanning tree) give each strip a position, and sorting by
     * position is the order. Photos shot out of sequence, in both directions, or twice from
     * the same spot all land where their content says they belong.
     */
    internal fun sequence(images: List<RgbaImage>, maxDy: Float = MAX_DY, progress: (Float) -> Unit = {}): Sequence {
        val n = images.size
        val planes = images.map(::planes)
        if (n < 2) return Sequence(IntArray(n) { it }, emptyList(), planes, n > 0)
        val window = if (n > EXHAUSTIVE) NEIGHBORS else n
        val pair = HashMap<Int, Link>()
        var done = 0
        val total = (0 until n).sumOf { i -> (i + 1 until n).count { j -> j - i <= window || n - (j - i) <= window } }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (j - i > window && n - (j - i) > window) continue
            pair[i * n + j] = align(planes[i], planes[j], forwardOnly = false, maxDy)
            progress(0.9f * ++done / total)
        }
        fun link(i: Int, j: Int): Link? =
            if (i < j) pair[i * n + j] else pair[j * n + i]?.let { Link(-it.dx, -it.dy, it.score) }

        // Prim's algorithm on match score; positions follow the tree.
        val x = FloatArray(n)
        val inTree = BooleanArray(n)
        inTree[0] = true
        var weakest = 1f
        repeat(n - 1) {
            var best: Link? = null
            var bestTrust = Float.NEGATIVE_INFINITY
            var from = -1
            var to = -1
            for (i in 0 until n) if (inTree[i]) for (j in 0 until n) if (!inTree[j]) {
                val l = link(i, j) ?: continue
                val trust = trust(l, i, j, n)
                if (trust > bestTrust) { bestTrust = trust; best = l; from = i; to = j }
            }
            if (best == null) {
                // Disconnected (only possible with a neighbor window): keep input order beyond here.
                to = (0 until n).first { !inTree[it] }
                x[to] = (0 until n).filter { inTree[it] }.maxOf { x[it] } + planes[to].fullH
                weakest = 0f
            } else {
                x[to] = x[from] + best!!.dx
                weakest = min(weakest, best!!.score)
            }
            inTree[to] = true
        }
        val matched = pair.values.any { it.score >= MATCHES }
        val order = if (matched) (0 until n).sortedBy { x[it] }.toIntArray() else IntArray(n) { it }
        // Neighbors overlap most, so their own match is the best measure of the step between
        // them; where it is poor, the tree's positions stand in.
        val links = (1 until n).map { k ->
            val a = order[k - 1]
            val b = order[k]
            val direct = link(a, b) ?: align(planes[a], planes[b], forwardOnly = false, maxDy)
            if (direct.score >= MATCHES && direct.dx > 0f) direct else Link(max(1f, x[b] - x[a]), 0f, direct.score)
        }
        progress(1f)
        return Sequence(order, links, planes, matched)
    }

    /** One row band's own match between two strips: where it is ([y], in a's rows) and its offset. */
    internal class BandLink(val y: Float, val dx: Float, val dy: Float, val score: Float)

    /**
     * Re-measures [link] separately in [bands] horizontal bands. If two strips differ by more
     * than a slide (one a little larger, or leaning), the bands disagree in a telling way:
     * dy growing down the strip is a scale difference, dx growing down it a lean.
     */
    internal fun bandLinks(a: RgbaImage, b: RgbaImage, link: Link, bands: Int = 6): List<BandLink> {
        val out = ArrayList<BandLink>()
        val dx = link.dx.roundToInt()
        val dy = link.dy.roundToInt()
        for (k in 0 until bands) {
            val y0 = max(max(0, dy), a.height * k / bands)
            val y1 = min(min(a.height, b.height + dy), a.height * (k + 1) / bands)
            if (y1 - y0 < a.height / (bands * 2)) continue
            val pa = Plane.of(a.crop(0, y0, a.width, y1 - y0), BAND_H).texture()
            val pb = Plane.of(b.crop(0, y0 - dy, b.width, y1 - y0), BAND_H).texture()
            val f = pa.h.toFloat() / (y1 - y0)
            val r = max(2, (BAND_REACH * a.height * f).roundToInt())
            val c = (dx * f).roundToInt()
            val best = search(pa, pb, (c - r)..(c + r), -r..r)
            val minPixels = max(16, (MIN_OVERLAP * min(pa.w, pb.w) * pa.h / 2).toInt())
            val score = ncc(pa, pb, best.first, best.second, minPixels).first
            out += BandLink((y0 + y1) / 2f, best.first / f, dy + best.second / f, score)
        }
        return out
    }

    /**
     * What a strip needs, beyond sliding, to agree with its neighbors.
     * @property shear columns of sideways drift per row (a leftover lean of the pillar)
     * @property stretch fractional height change about the middle row (a leftover size error)
     */
    class Correction(val shear: Float = 0f, val stretch: Float = 0f)

    /**
     * Geometry puts each strip nearly right; the pictures say how far off it still is. For
     * each pair of neighbors the band offsets are fitted with a line down the strip: the
     * slope of dx is their difference in lean, the slope of dy their difference in size.
     * Differences chain along the sequence into one correction per strip, centered so the
     * set as a whole is neither tilted nor resized.
     */
    internal fun corrections(images: List<RgbaImage>, maxDy: Float = MAX_DY): List<Correction> {
        val n = images.size
        if (n < 2) return List(n) { Correction() }
        val seq = sequence(images, maxDy)
        if (!seq.matched) return List(n) { Correction() }
        val shear = FloatArray(n)
        val stretch = FloatArray(n)
        for (k in 1 until n) {
            val a = seq.order[k - 1]
            val b = seq.order[k]
            val good = bandLinks(images[a], images[b], seq.links[k - 1]).filter { it.score >= BAND_TRUST }
            var dShear = 0f
            var dStretch = 0f
            if (good.size >= 3) {
                val ys = good.map { it.y }
                dShear = EdgeSuggester.robustFit(ys, good.map { it.dx }).second.coerceIn(-MAX_SHEAR, MAX_SHEAR)
                dStretch = EdgeSuggester.robustFit(ys, good.map { it.dy }).second.coerceIn(-MAX_STRETCH, MAX_STRETCH)
            }
            shear[b] = shear[a] + dShear
            stretch[b] = stretch[a] + dStretch
        }
        val meanShear = shear.average().toFloat()
        val meanStretch = stretch.average().toFloat()
        return List(n) { Correction(shear[it] - meanShear, stretch[it] - meanStretch) }
    }

    private const val BAND_TRUST = 0.35f
    private const val MAX_SHEAR = 0.12f
    private const val MAX_STRETCH = 0.12f
    private const val BAND_H = 64
    private const val BAND_REACH = 0.03f

    /**
     * How far to believe a match when building the sequence. Photos are usually taken in order,
     * so a match between input neighbors is believed a little sooner than an equally good one
     * between strangers: on a surface that repeats (tiles, stripes) the pictures alone cannot
     * tell one repeat from the next, and capture order can.
     */
    private fun trust(l: Link, i: Int, j: Int, n: Int): Float {
        val apart = kotlin.math.abs(i - j)
        return l.score + if (apart == 1 || apart == n - 1) IN_ORDER else 0f
    }

    private const val IN_ORDER = 0.08f

    /** Past this many strips, only strips near each other in the input are compared. */
    private const val EXHAUSTIVE = 14
    private const val NEIGHBORS = 6

    /** Below this correlation two strips are taken not to overlap at all. */
    internal const val MATCHES = 0.25f

    /**
     * Coarse-to-fine normalized cross-correlation over horizontal and small vertical shifts,
     * on texture alone (see [Plane.texture]): the same paint under a different lamp, exposure
     * or angle must still match itself, and raw brightness would match the lamp instead.
     */
    fun align(a: RgbaImage, b: RgbaImage, forwardOnly: Boolean = false, maxDy: Float = MAX_DY): Link =
        align(planes(a), planes(b), forwardOnly, maxDy)

    internal fun align(a: Planes, b: Planes, forwardOnly: Boolean, maxDy: Float): Link {
        val ca = a.coarse
        val cb = b.coarse
        val minOv = max(3, (MIN_OVERLAP * min(ca.w, cb.w)).roundToInt())
        val dyR = max(1, (maxDy * COARSE_H).roundToInt())
        val dxs = if (forwardOnly) 1..(ca.w - minOv) else -(cb.w - minOv)..(ca.w - minOv)
        val coarse = search(ca, cb, dxs, -dyR..dyR)

        val fa = a.fine
        val fb = b.fine
        val ratio = fa.h.toFloat() / ca.h
        val r = ratio.roundToInt() + 1
        val cx = (coarse.first * ratio).roundToInt()
        val cy = (coarse.second * ratio).roundToInt()
        val fine = search(fa, fb, (cx - r)..(cx + r), (cy - r)..(cy + r))

        // Sub-pixel: the correlation peak is parabolic near its top, so the neighbors on either
        // side of the best slide say which way, and how far, the true peak lies.
        val minPixels = max(16, (MIN_OVERLAP * min(fa.w, fb.w) * fa.h / 2).toInt())
        val sm = ncc(fa, fb, fine.first - 1, fine.second, minPixels).first
        val s0 = ncc(fa, fb, fine.first, fine.second, minPixels).first
        val sp = ncc(fa, fb, fine.first + 1, fine.second, minPixels).first
        val curve = sm - 2 * s0 + sp
        val offset = if (curve < -1e-6f && sm > -1f && sp > -1f) (0.5f * (sm - sp) / curve).coerceIn(-0.5f, 0.5f) else 0f

        val toFull = a.fullH.toFloat() / fa.h
        return Link((fine.first + offset) * toFull, fine.second * toFull, s0)
    }

    /**
     * Contrast (luma levels) below which texture is not amplified to full strength: resampling
     * grain and sensor noise sit there, and they line up with the frame, not with the subject.
     */
    private const val FAINT = 10f

    /** Preference for level alignment: a full strip-height of vertical slide costs this much correlation. */
    private const val LEVEL = 0.25f

    private fun search(a: Plane, b: Plane, dxs: IntRange, dys: IntRange): Pair<Int, Int> {
        var best = Float.NEGATIVE_INFINITY
        var bestDx = 0
        var bestDy = 0
        val minPixels = max(16, (MIN_OVERLAP * min(a.w, b.w) * a.h / 2).toInt())
        val fullOverlap = min(a.w, b.w).toFloat() * min(a.h, b.h)
        for (dy in dys) for (dx in dxs) {
            val (s, n) = ncc(a, b, dx, dy, minPixels)
            // Thin overlaps correlate by luck; discount them. Favor level alignment on ties.
            val support = sqrt(min(1f, n / (0.3f * fullOverlap)))
            val score = s * support - LEVEL * abs(dy) / a.h
            if (score > best) { best = score; bestDx = dx; bestDy = dy }
        }
        return bestDx to bestDy
    }

    /**
     * NCC of a(x, y) against b(x - dx, y - dy) over their valid overlap. For texture planes it
     * is a blend of two correlations: texture, which lighting cannot fool, and plain
     * brightness, which a repeating pattern cannot (one repeat looks like the next in texture,
     * but the broad light and dark around it differ).
     */
    internal fun ncc(a: Plane, b: Plane, dx: Int, dy: Int, minPixels: Int): Pair<Float, Int> {
        val x0 = max(0, dx); val x1 = min(a.w, dx + b.w)
        val y0 = max(0, dy); val y1 = min(a.h, dy + b.h)
        if (x1 <= x0 || y1 <= y0) return -1f to 0
        val ar = a.raw
        val br = b.raw
        val both = ar != null && br != null
        var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        var ra = 0.0; var rb = 0.0; var raa = 0.0; var rbb = 0.0; var rab = 0.0
        for (y in y0 until y1) {
            val ia = y * a.w
            val ib = (y - dy) * b.w - dx
            for (x in x0 until x1) {
                val va = a.v[ia + x]; val vb = b.v[ib + x]
                if (va < 0f || vb < 0f) continue
                n++; sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
                if (both) {
                    val pa = ar!![ia + x]; val pb = br!![ib + x]
                    ra += pa; rb += pb; raa += pa * pa; rbb += pb * pb; rab += pa * pb
                }
            }
        }
        if (n < minPixels) return -1f to n
        fun corr(sa: Double, sb: Double, saa: Double, sbb: Double, sab: Double): Float? {
            val cov = sab - sa * sb / n
            val va = saa - sa * sa / n
            val vb = sbb - sb * sb / n
            return if (va <= 1e-6 || vb <= 1e-6) null else (cov / sqrt(va * vb)).toFloat()
        }
        val texture = corr(sa, sb, saa, sbb, sab) ?: return -1f to n
        if (!both) return texture to n
        val brightness = corr(ra, rb, raa, rbb, rab) ?: 0f
        return (TEXTURE_SHARE * texture + (1f - TEXTURE_SHARE) * brightness) to n
    }

    private const val TEXTURE_SHARE = 0.6f

    /** Luma at a working height; -1 marks pixels with no data (outside the source photo). */
    internal class Plane(val w: Int, val h: Int, val v: FloatArray, val raw: FloatArray? = null) {
        /**
         * Texture only: local average removed, then local contrast equalized, over a
         * neighborhood a sixth of the height. Shading, glare and exposure vanish; marks stay.
         * The untouched brightness rides along as [raw] for [ncc]'s second opinion.
         */
        fun texture(): Plane {
            val r = max(2, h / 6)
            val mean = boxMean(v, w, h, r, r)
            val dev = FloatArray(w * h) { if (v[it] < 0f) -1f else v[it] - mean[it] }
            val energy = boxMean(FloatArray(w * h) { if (v[it] < 0f) -1f else dev[it] * dev[it] }, w, h, r, r)
            // 256 keeps data cells non-negative: -1 is reserved for "no data".
            return Plane(w, h, FloatArray(w * h) { if (v[it] < 0f) -1f else 256f + 40f * dev[it] / (sqrt(energy[it]) + FAINT) }, v)
        }

        companion object {
            fun of(img: RgbaImage, height: Int): Plane {
                val h = min(height, img.height)
                val w = max(1, (img.width * h.toFloat() / img.height).roundToInt())
                val s = img.resized(w, h)
                val l = s.luma()
                for (i in l.indices) if ((s.pixels[i] ushr 24) < 128) l[i] = -1f
                return Plane(w, h, l)
            }

            /** Exact size, aspect ignored: both sides of a comparison are squeezed the same way. */
            fun fixed(img: RgbaImage, w: Int, h: Int): Plane {
                val s = img.resized(w, h)
                val l = s.luma()
                for (i in l.indices) if ((s.pixels[i] ushr 24) < 128) l[i] = -1f
                return Plane(w, h, l)
            }
        }
    }
}

private fun WeightedStrip.scaledToHeight(h: Int): WeightedStrip {
    // A pixel or two of difference is rounding, not scale: resampling for it would only soften.
    if (kotlin.math.abs(image.height - h) <= max(2, h / 300)) return this
    val s = h.toFloat() / image.height
    val w = max(2, (image.width * s).roundToInt())
    val src = columnWeight
    return WeightedStrip(
        image.resized(w, h),
        FloatArray(w) { src[min(src.size - 1, (it / s).toInt())] },
        pxPerRadian?.times(s),
    )
}
