package com.hereliesaz.cameraunroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Puts photos of a four-sided beam together into its four faces, laid flat side by side.
 *
 * A round pillar is stitched: neighbouring photos overlap by some unknown amount and the
 * overlap has to be found and blended. A beam needs none of that. Every photo shows one or
 * two whole faces ([Prism]), so the only questions are *which* face each one is, and which
 * photo shows it best.
 *
 *  1. Which: two faces in one photo are neighbours, left then right. Faces from different
 *     photos that look alike are the same face. Following those two kinds of link sorts
 *     every face into one of four places around the beam.
 *  2. Lining up: photos are taken from slightly different heights and distances, so the
 *     same face comes out a little higher or lower, a little taller or shorter, in each.
 *     Comparing every pair of photos that share a face gives each photo one shift and one
 *     stretch that make them all agree.
 *  3. Best: each face is taken whole from the photo that sees it most squarely and closely.
 *     Other photos only fill rows that one did not reach. Nothing is blended inside a face,
 *     so nothing can ghost; the joins fall on the beam's own corners.
 */
object Beam {
    /** One leveled photo and where it was taken from. [horizon] and [focalPx] are in its pixels. */
    class View(val photo: RgbaImage, val pose: Prism.Pose, val horizon: Float, val focalPx: Float)

    /**
     * @return the faces in order around the beam; [Rendered.wraps] when all four were found.
     *   Photos whose faces match no other photo's are left out.
     */
    fun assemble(views: List<View>, progress: (Float) -> Unit = {}): Rendered {
        require(views.isNotEmpty()) { "No beam found in these photos" }
        fun middle(values: List<Float>) = values.sorted()[values.size / 2]
        // One scale and one stretch of beam for all: the typical photo's.
        val pxPerHalf = middle(views.map { it.focalPx / it.pose.nearest })
        val up = middle(views.map { it.horizon * it.pose.nearest / it.focalPx })
        val down = middle(views.map { (it.photo.height - it.horizon) * it.pose.nearest / it.focalPx })

        // --- 1. Every face of every photo, small, to tell which is which.
        class Piece(val view: Int, val side: Int, val face: Prism.Face, val fine: Gray) { val coarse = fine.shrunk(COARSEN) }
        val pieces = ArrayList<Piece>()
        views.forEachIndexed { i, v ->
            progress(0.35f * i / views.size)
            // A photo shrunk to about the size it is sampled at, so the small faces are not aliased.
            val k = min(1f, 1.5f * FINE / (v.focalPx / v.pose.nearest))
            val small = if (k < 1f) v.photo.resized(max(2, (v.photo.width * k).roundToInt()), max(2, (v.photo.height * k).roundToInt())) else v.photo
            val sx = small.width.toFloat() / v.photo.width
            Prism.faces(small, v.pose, v.horizon * sx, v.focalPx * sx, FINE, up, down)
                .forEachIndexed { side, face -> pieces += Piece(i, side, face, Gray.of(face.image)) }
        }
        val n = pieces.size
        val reach = (pieces[0].coarse.h * REACH).roundToInt()
        val alike = Array(n) { FloatArray(n) { -1f } }
        val offset = Array(n) { IntArray(n) }
        for (a in 0 until n) {
            progress(0.35f + 0.25f * a / n)
            for (b in a + 1 until n) {
                if (pieces[a].view == pieces[b].view) continue
                var best = -1f
                var at = 0
                for (dy in -reach..reach) for (dx in -1..1) {
                    val score = pieces[a].coarse.alike(pieces[b].coarse, dx, dy, 0, pieces[a].coarse.h)
                    if (score > best) { best = score; at = dy }
                }
                alike[a][b] = best; alike[b][a] = best
                offset[a][b] = at; offset[b][a] = -at
            }
        }
        // Places around the beam, relative to each group's first member.
        val root = IntArray(n) { it }
        val place = IntArray(n) // this piece's place minus its root's
        fun find(x: Int): Int {
            if (root[x] == x) return x
            val r = find(root[x])
            place[x] += place[root[x]]
            root[x] = r
            return r
        }
        /** Records that [b] is [step] places right of [a], unless that contradicts what is known. */
        fun join(a: Int, b: Int, step: Int) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            place[rb] = place[a] + step - place[b]
            root[rb] = ra
        }
        for (a in 0 until n) for (b in a + 1 until n) {
            if (pieces[a].view == pieces[b].view) join(a, b, pieces[b].side - pieces[a].side)
        }
        val pairs = ArrayList<IntArray>()
        for (a in 0 until n) for (b in a + 1 until n) if (alike[a][b] >= SAME) pairs += intArrayOf(a, b)
        pairs.sortByDescending { alike[it[0]][it[1]] }
        for ((a, b) in pairs.map { it[0] to it[1] }) join(a, b, 0)
        for (x in 0 until n) find(x)
        val biggest = (0 until n).groupBy { root[it] }.values.maxBy { it.size }
        val first = biggest.minOf { place[it] }
        val span = biggest.maxOf { place[it] } - first + 1
        val wraps = span >= SIDES
        val slots = if (wraps) SIDES else span
        fun slot(x: Int) = (place[x] - first).mod(SIDES)

        // --- 2. One shift and one stretch per photo, so that shared faces agree.
        val used = biggest.map { pieces[it].view }.distinct()
        val index = IntArray(views.size) { -1 }
        used.forEachIndexed { k, v -> index[v] = k }
        val h = pieces[0].fine.h
        val unknowns = 2 * used.size // stretch (as rows over the whole height), then shift, per photo
        val normal = Array(unknowns) { DoubleArray(unknowns + 1) }
        fun observe(coefficients: Map<Int, Double>, value: Double, weight: Double) {
            for ((i, ci) in coefficients) {
                for ((j, cj) in coefficients) normal[i][j] += weight * ci * cj
                normal[i][unknowns] += weight * ci * value
            }
        }
        for (a in biggest) for (b in biggest) {
            if (a >= b || pieces[a].view == pieces[b].view || slot(a) != slot(b) || alike[a][b] < SAME * 0.8f) continue
            val fa = pieces[a].fine; val fb = pieces[b].fine
            val coarse = offset[a][b] * COARSEN
            val top = max(0, -coarse); val bottom = min(h, h - coarse)
            val mid = (top + bottom) / 2
            for ((y0, y1) in listOf(top to mid, mid to bottom)) {
                if (y1 - y0 < h / 10) continue
                val scores = FloatArray(2 * NUDGE + 1) { fa.alike(fb, 0, coarse + it - NUDGE, y0, y1) }
                var best = 0
                for (k in scores.indices) if (scores[k] > scores[best]) best = k
                if (scores[best] < SAME * 0.6f) continue
                var dy = (coarse + best - NUDGE).toFloat()
                if (best > 0 && best < scores.lastIndex) {
                    val curve = scores[best - 1] - 2 * scores[best] + scores[best + 1]
                    if (curve < 0f) dy += 0.5f * (scores[best - 1] - scores[best + 1]) / curve
                }
                // Row y of photo a is row y + dy of photo b.
                val t = ((y0 + y1) / 2.0 - h / 2.0) / h
                val ia = index[pieces[a].view]; val ib = index[pieces[b].view]
                observe(mapOf(2 * ia to t, 2 * ib to -t, 2 * ia + 1 to 1.0, 2 * ib + 1 to -1.0), dy.toDouble(), scores[best].toDouble())
            }
        }
        for (i in 0 until unknowns) observe(mapOf(i to 1.0), 0.0, GENTLY) // without evidence, leave a photo as it is
        val solved = solve(normal)
        val stretch = FloatArray(views.size) { 1f }
        val shift = FloatArray(views.size)
        used.forEachIndexed { k, v ->
            stretch[v] = (1.0 + solved[2 * k] / h).toFloat().coerceIn(1f - MAX_STRETCH, 1f + MAX_STRETCH)
            shift[v] = (solved[2 * k + 1] / FINE).toFloat()
        }

        // --- 3. Each face from the photo that shows it best.
        val faceW = max(2, (2f * pxPerHalf).roundToInt())
        val outH = max(2, ((up + down) * pxPerHalf).roundToInt())
        val out = RgbaImage(faceW * slots, outH)
        val rendered = HashMap<Int, List<Prism.Face>>()
        fun full(view: Int) = rendered.getOrPut(view) {
            val v = views[view]
            Prism.faces(v.photo, v.pose, v.horizon, v.focalPx, pxPerHalf, up, down, stretch[view], shift[view])
        }
        for (s in 0 until slots) {
            progress(0.6f + 0.4f * s / slots)
            val candidates = biggest.filter { slot(it) == s }
                .sortedByDescending { pieces[it].face.squarely * views[pieces[it].view].focalPx / pieces[it].face.depth * pieces[it].fine.covered }
            if (candidates.isEmpty()) continue
            val best = candidates[0]
            val face = full(pieces[best].view)[pieces[best].side].image
            var missing = 1f - pieces[best].fine.covered
            for (other in candidates.drop(1).take(FILLS)) {
                if (missing < WORTH_FILLING) break
                fill(face, full(pieces[other].view)[pieces[other].side].image)
                missing = 1f - Gray.of(face).covered
            }
            for (y in 0 until outH) face.pixels.copyInto(out.pixels, y * out.width + s * faceW, y * faceW, (y + 1) * faceW)
        }
        progress(1f)
        // One turn of the beam is four faces: the scale, put as pixels per radian of the way around.
        return Rendered(cropToCovered(out), wraps, xmp = null, pxPerRadian = SIDES * faceW / (2f * PI.toFloat()), sides = SIDES)
    }

    /** Fills what [face] lacks from [other], matched in brightness and faded in over a few rows. */
    private fun fill(face: RgbaImage, other: RgbaImage) {
        val w = face.width
        val h = face.height
        fun alpha(img: RgbaImage, i: Int) = img.pixels[i] ushr 24
        // How the two differ in brightness where both have something.
        val sums = DoubleArray(6)
        for (i in face.pixels.indices) {
            if (alpha(face, i) == 0 || alpha(other, i) == 0) continue
            for (c in 0 until 3) {
                sums[c] += (face.pixels[i] shr (16 - 8 * c)) and 0xFF
                sums[3 + c] += (other.pixels[i] shr (16 - 8 * c)) and 0xFF
            }
        }
        val gain = FloatArray(3) { if (sums[3 + it] > 0.0) (sums[it] / sums[3 + it]).toFloat().coerceIn(0.6f, 1.6f) else 1f }
        // Rows the face has, and how deep inside them each row is.
        val has = BooleanArray(h) { y -> (0 until w).count { alpha(face, y * w + it) != 0 } * 2 > w }
        val inside = IntArray(h)
        var run = 0
        for (y in 0 until h) { run = if (has[y]) run + 1 else 0; inside[y] = run }
        run = 0
        for (y in h - 1 downTo 0) { run = if (has[y]) run + 1 else 0; inside[y] = min(inside[y], run) }
        val fade = max(2, h / 40)
        for (y in 0 until h) {
            val keep = if (has[y]) min(1f, inside[y].toFloat() / fade) else 0f
            if (keep >= 1f) continue
            for (x in 0 until w) {
                val i = y * w + x
                if (alpha(other, i) == 0) continue
                val mine = if (alpha(face, i) == 0) 0f else keep
                var argb = 0xFF shl 24
                for (c in 0 until 3) {
                    val shiftBy = 16 - 8 * c
                    val theirs = (((other.pixels[i] shr shiftBy) and 0xFF) * gain[c]).coerceIn(0f, 255f)
                    val value = mine * ((face.pixels[i] shr shiftBy) and 0xFF) + (1f - mine) * theirs
                    argb = argb or (value.roundToInt().coerceIn(0, 255) shl shiftBy)
                }
                face.pixels[i] = argb
            }
        }
    }

    /** The tallest run of rows that every face fills. */
    private fun cropToCovered(img: RgbaImage): RgbaImage {
        val w = img.width
        var bestStart = 0; var bestLength = 0; var start = 0
        for (y in 0..img.height) {
            val covered = y < img.height && (0 until w).count { img.pixels[y * w + it] ushr 24 != 0 } >= w * COVERED
            if (covered) continue
            if (y - start > bestLength) { bestLength = y - start; bestStart = start }
            start = y + 1
        }
        return if (bestLength < 2 || bestLength == img.height) img else img.crop(0, bestStart, w, bestLength)
    }

    /** Solves the least-squares system gathered in [m] (its last column the right-hand side). */
    private fun solve(m: Array<DoubleArray>): DoubleArray {
        val n = m.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            val swap = m[col]; m[col] = m[pivot]; m[pivot] = swap
            if (abs(m[col][col]) < 1e-12) continue
            for (r in 0 until n) {
                if (r == col) continue
                val k = m[r][col] / m[col][col]
                if (k != 0.0) for (c in col..n) m[r][c] -= k * m[col][c]
            }
        }
        return DoubleArray(n) { if (abs(m[it][it]) < 1e-12) 0.0 else m[it][n] / m[it][it] }
    }

    /** A face in grey, with which of its pixels the photo reached. */
    private class Gray(val w: Int, val h: Int, val luma: FloatArray, val ok: BooleanArray) {
        val covered: Float get() = ok.count { it }.toFloat() / ok.size

        fun shrunk(by: Int): Gray {
            val sw = w / by; val sh = h / by
            val l = FloatArray(sw * sh); val o = BooleanArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                var sum = 0f; var count = 0
                for (dy in 0 until by) for (dx in 0 until by) {
                    val i = (y * by + dy) * w + x * by + dx
                    if (ok[i]) { sum += luma[i]; count++ }
                }
                if (count * 2 >= by * by) { l[y * sw + x] = sum / count; o[y * sw + x] = true }
            }
            return Gray(sw, sh, l, o)
        }

        /**
         * How alike this is to [other] moved so that this (x, y) lies on its (x + dx, y + dy),
         * over rows [y0] until [y1] of this: 1 the same picture, 0 unrelated, -1 too little
         * in common to say.
         */
        fun alike(other: Gray, dx: Int, dy: Int, y0: Int, y1: Int): Float {
            var count = 0
            var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
            for (y in max(y0, -dy) until min(y1, other.h - dy)) {
                for (x in max(0, -dx) until min(w, other.w - dx)) {
                    val i = y * w + x
                    val j = (y + dy) * other.w + x + dx
                    if (!ok[i] || !other.ok[j]) continue
                    val a = luma[i]; val b = other.luma[j]
                    sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b
                    count++
                }
            }
            if (count < w * (y1 - y0) * ENOUGH) return -1f
            val va = saa - sa * sa / count
            val vb = sbb - sb * sb / count
            if (va < 1e-3 || vb < 1e-3) return 0f
            return ((sab - sa * sb / count) / sqrt(va * vb)).toFloat()
        }

        companion object {
            /**
             * [img] with everything broad taken out: each pixel's brightness less that of its
             * surroundings. Faces are told apart by what is painted on them, not by a floor at
             * their foot or the light they stand in, which every face shares.
             */
            fun of(img: RgbaImage): Gray {
                val luma = img.luma()
                val ok = BooleanArray(luma.size) { img.pixels[it] ushr 24 != 0 }
                for (i in luma.indices) if (!ok[i]) luma[i] = -1f
                val radius = max(2, img.width / BROAD)
                val around = boxMean(luma, img.width, img.height, radius, radius)
                return Gray(img.width, img.height, FloatArray(luma.size) { if (ok[it]) luma[it] - around[it] else 0f }, ok)
            }
        }
    }

    private const val SIDES = 4
    /** Pixels per half-width at which faces are compared; a face is twice this wide. */
    private const val FINE = 96f
    /** Faces are first compared this many times smaller still. */
    private const val COARSEN = 4
    /** How far up or down, as a share of the height, the same face may sit in two photos. */
    private const val REACH = 0.3f
    /** How alike two faces must look to be taken for the same one. */
    private const val SAME = 0.45f
    /** Share of the compared area both faces must cover. */
    private const val ENOUGH = 0.35f
    private const val NUDGE = 8
    /** A face's width over the distance within which brightness counts as its surroundings'. */
    private const val BROAD = 8
    private const val GENTLY = 1e-3
    private const val MAX_STRETCH = 0.12f
    private const val FILLS = 2
    private const val WORTH_FILLING = 0.02f
    private const val COVERED = 0.995f
}
