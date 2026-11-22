package com.hereliesaz.cameraunroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Nobody holds a phone level. A pillar's two sides say how it was held: their common lean is
 * the camera's roll, and their convergence is its pitch (tipped up, the pillar narrows toward
 * the top). [level] turns that into the [PhotoAdjust] that stands the pillar upright with
 * parallel sides, plus where the horizon then lies, which sets how the rims curve.
 */
object Leveler {
    /** @property horizon horizon row as a fraction of the height, on the adjusted photo. */
    class Level(val adjust: PhotoAdjust, val horizon: Float)

    /** The two sides as lines x = at + slope·(y − h/2), in pixels of the measured image. */
    class Sides(val leftAt: Float, val leftSlope: Float, val rightAt: Float, val rightSlope: Float) {
        val lean get() = (leftSlope + rightSlope) / 2f
        val spread get() = rightSlope - leftSlope
        val width get() = rightAt - leftAt
    }

    /**
     * @param depth depth image of [photo] ([DepthModel.estimate]) if there is one: the sides are
     *   then read from it, which no paint, lamp or background can mislead; without it they are
     *   read from color edges.
     */
    fun level(photo: RgbaImage, focal35: Float? = null, depth: RgbaImage? = null): Level {
        val img = depth ?: photo.scaledToMax(EdgeSuggester.WORK_SIZE)
        val measure: (RgbaImage) -> Sides? = if (depth != null) DepthSides::measure else ::measure
        val none = Level(PhotoAdjust(), 0.5f)
        if (img.width < 16 || img.height < 16) return none
        val raw = measure(img) ?: return none

        // Roll: rotate so the sides lean equally and oppositely. The sign convention of the
        // rotation is settled by measuring again, not by assuming it.
        var adjust = PhotoAdjust()
        var now = raw
        val rollDeg = (atan(raw.lean) * 180.0 / PI).toFloat()
        if (abs(rollDeg) in MIN_DEG..MAX_ROLL_DEG) {
            for (sign in listOf(1f, -1f)) {
                val tryAdjust = PhotoAdjust(rotationDeg = sign * rollDeg)
                val m = measure(tryAdjust.apply(img)) ?: continue
                if (abs(m.lean) < abs(now.lean)) { adjust = tryAdjust; now = m }
            }
        }

        // Pitch: the sides meet at a vanishing point; a warp whose own sides aim at that same
        // point makes them parallel. The middle row stays put, so scale there is untouched.
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        val relSpread = now.spread * h / max(1f, now.width) // fractional width change, top to bottom
        var horizon = 0.5f
        if (abs(relSpread) in MIN_SPREAD..MAX_SPREAD) {
            val dyV = -now.width / now.spread // rows from the middle row to the vanishing point
            val xV = now.leftAt + now.leftSlope * dyV
            val sl = (xV - 0f) / dyV
            val sr = (xV - w) / dyV
            val quad = listOf(
                NPoint((0f - sl * h / 2f) / w, 0f), NPoint((w - sr * h / 2f) / w, 0f),
                NPoint((w + sr * h / 2f) / w, 1f), NPoint((0f + sl * h / 2f) / w, 1f),
            )
            val tryAdjust = adjust.copy(quad = quad)
            val m = runCatching { measure(tryAdjust.apply(img)) }.getOrNull()
            if (m != null && abs(m.spread) < abs(now.spread)) {
                // tan(pitch) = f × (relative width change per row); the horizon sits f·tan(pitch) off center.
                val f = Perspective.focalPx(focal35, img.width, img.height)
                horizon = (0.5f + f * f * (now.spread / now.width) / h).coerceIn(-0.5f, 1.5f)
                adjust = tryAdjust
            }
        }
        return Level(adjust, horizon)
    }

    /**
     * Follows each side found by [EdgeSuggester.sides] up and down the frame in bands of rows
     * and fits a line through where it is strongest; a second pass looks only near the first
     * fit, so clutter beside the pillar cannot drag a band away.
     */
    internal fun measure(img: RgbaImage): Sides? {
        val (l, r) = EdgeSuggester.sides(img) ?: return null
        val w = img.width
        val h = img.height
        fun diff(a: Int, b: Int) =
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

        fun track(x0: Float): Pair<Float, Float>? {
            var at = x0
            var slope = 0f
            for (radius in intArrayOf(max(3, w / 16), 3)) {
                val ys = ArrayList<Float>()
                val xs = ArrayList<Float>()
                for (b in 0 until BANDS) {
                    val y0 = (h * (0.08f + 0.84f * b / BANDS)).toInt()
                    val y1 = (h * (0.08f + 0.84f * (b + 1) / BANDS)).toInt()
                    val yc = (y0 + y1) / 2f - h / 2f
                    val center = (at + slope * yc).toInt()
                    var best = 0
                    var bestX = -1
                    for (x in max(2, center - radius)..min(w - 3, center + radius)) {
                        var sum = 0
                        for (y in y0 until y1) sum += min(EdgeSuggester.GRAD_CAP, diff(img[x + 2, y], img[x - 2, y]))
                        if (sum > best) { best = sum; bestX = x }
                    }
                    if (bestX >= 0 && best > BAND_MIN * (y1 - y0)) { ys += yc; xs += bestX + 0.5f }
                }
                if (ys.size < BANDS / 2) return null
                val (a, k) = EdgeSuggester.robustFit(ys, xs)
                at = a; slope = k
            }
            return at to slope
        }
        val left = track(l * w) ?: return null
        val right = track(r * w) ?: return null
        if (right.first - left.first < w / 10f) return null
        return Sides(left.first, left.second, right.first, right.second)
    }

    private const val BANDS = 12
    private const val BAND_MIN = 12
    private const val MIN_DEG = 0.3f
    private const val MAX_ROLL_DEG = 12f
    private const val MIN_SPREAD = 0.015f
    private const val MAX_SPREAD = 0.6f
}
