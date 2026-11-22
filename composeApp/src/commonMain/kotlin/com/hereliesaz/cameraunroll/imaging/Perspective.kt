package com.hereliesaz.cameraunroll.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pinhole geometry of a vertical cylinder seen from a finite distance, which is what makes
 * photos taken nearer or farther, or with the phone tipped, agree with each other.
 *
 * With the camera D radii from the axis (D > 1), a surface point at angle θ from the line of
 * sight lands at x = cx + f·sinθ/(D − cosθ), and the visible face ends at the tangent,
 * cosθ = 1/D: closer cameras see less than half the pillar. The silhouette's half-width r
 * therefore fixes the distance once the focal length f is known: D = √(1 + (f/r)²).
 * A rim at height Y off the horizon appears at y = yh + f·Y/(D − cosθ): nearest (and so
 * farthest from the horizon) at the front, which is the rim's bulge.
 */
object Perspective {
    /** Phone main cameras sit around here; used when EXIF has no 35 mm-equivalent focal length. */
    const val DEFAULT_FOCAL_35 = 26f

    /** Focal length in pixels of a [width]×[height] frame, from a 35 mm-equivalent focal length. */
    fun focalPx(focal35: Float?, width: Int, height: Int): Float =
        (focal35?.takeIf { it > 5f } ?: DEFAULT_FOCAL_35) / 43.27f * sqrt(width.toFloat() * width + height.toFloat() * height)

    /** Camera distance from the axis in pillar radii, from the silhouette half-width in pixels. */
    fun distance(halfWidthPx: Float, focalPx: Float): Float {
        val q = focalPx / max(1f, halfWidthPx)
        return max(MIN_DISTANCE, sqrt(1f + q * q))
    }

    /** Pixels per pillar radius on the surface nearest the camera. */
    fun frontScale(distance: Float, focalPx: Float): Float = focalPx / (distance - 1f)

    /**
     * Rim guides for a rim whose nearest point sits at row [frontY] (fraction of height) when
     * the horizon is at [horizon]: (rimY, bulge) as [CylinderGuides] wants them.
     */
    fun rim(frontY: Float, horizon: Float, distance: Float): Pair<Float, Float> {
        val bulge = (frontY - horizon) / (distance + 1f)
        return (frontY - bulge) to bulge
    }

    /**
     * Makes a set of photos of one pillar cover the same stretch of it, so their strips share
     * a scale and line up however far away each was taken: every photo keeps its sides, and
     * its rims move to the same heights above and below the horizon (in pillar radii), as far
     * as the tightest photo allows.
     *
     * @param horizons the horizon row of each photo as a fraction of its height
     */
    fun harmonize(
        guides: List<CylinderGuides>,
        sizes: List<Pair<Int, Int>>,
        focalPx: List<Float>,
        horizons: List<Float>,
    ): List<CylinderGuides> {
        if (guides.isEmpty()) return guides
        class View(val d: Float, val scale: Float, val horizonPx: Float, val h: Int)
        val views = guides.indices.map { i ->
            val (w, h) = sizes[i]
            val d = distance((guides[i].right - guides[i].left) / 2f * w, focalPx[i])
            View(d, frontScale(d, focalPx[i]), horizons[i] * h, h)
        }
        // Radii of pillar available above and below the horizon in the tightest photo.
        val up = views.minOf { (it.horizonPx - FRAME_MARGIN * it.h) / it.scale }
        val down = views.minOf { (it.h * (1f - FRAME_MARGIN) - it.horizonPx) / it.scale }
        if (up + down < MIN_EXTENT) return guides
        return guides.indices.map { i ->
            val v = views[i]
            val (topY, topBulge) = rim((v.horizonPx - up * v.scale) / v.h, horizons[i], v.d)
            val (bottomY, bottomBulge) = rim((v.horizonPx + down * v.scale) / v.h, horizons[i], v.d)
            guides[i].copy(topY = topY, topBulge = topBulge, bottomY = bottomY, bottomBulge = bottomBulge)
                .takeIf { it.isValid } ?: guides[i]
        }
    }

    /**
     * The same photos, opened up: every photo's rims move out to as far as a typical photo of
     * the set reaches above and below the horizon. A photo that does not reach that far simply
     * has nothing there (its strip is transparent in those rows), so the strips still share a
     * scale and a horizon row, but none is trimmed to the least of the others.
     * What the result keeps is then decided where the strips are composed, column by column.
     *
     * The horizon is read back from each photo's own rims (it is where their bulge would
     * vanish), so this needs nothing but the guides.
     */
    fun extend(guides: List<CylinderGuides>, sizes: List<Pair<Int, Int>>, focalPx: List<Float>): List<CylinderGuides> {
        if (guides.isEmpty()) return guides
        class View(val d: Float, val scale: Float, val horizon: Float, val h: Int)
        val views = guides.indices.map { i ->
            val (w, h) = sizes[i]
            val g = guides[i]
            val d = distance((g.right - g.left) / 2f * w, focalPx[i])
            val horizon = ((g.topY - d * g.topBulge) + (g.bottomY - d * g.bottomBulge)) / 2f
            View(d, frontScale(d, focalPx[i]), horizon.coerceIn(-0.5f, 1.5f), h)
        }
        // As far as a typical photo reaches. Rows only one or two photos show cannot make a
        // whole row of the result anyway (it needs every column painted), and carrying them
        // would only cost memory; rows most photos show are what trimming to the least threw away.
        fun reach(values: List<Float>): Float = values.sorted()[values.size / 2]
        val up = reach(views.map { it.horizon * it.h / it.scale })
        val down = reach(views.map { (1f - it.horizon) * it.h / it.scale })
        if (up + down < MIN_EXTENT) return guides
        return guides.indices.map { i ->
            val v = views[i]
            val (topY, topBulge) = rim(v.horizon - up * v.scale / v.h, v.horizon, v.d)
            val (bottomY, bottomBulge) = rim(v.horizon + down * v.scale / v.h, v.horizon, v.d)
            guides[i].copy(topY = topY, topBulge = topBulge, bottomY = bottomY, bottomBulge = bottomBulge)
                .takeIf { it.isValid } ?: guides[i]
        }
    }

    /** Closer than this the visible face is a sliver and the estimate is surely wrong. */
    private const val MIN_DISTANCE = 1.3f
    private const val FRAME_MARGIN = 0.02f
    private const val MIN_EXTENT = 0.3f

    /** Half-angle of the face a camera at [distance] can see at all. */
    fun tangentAngle(distance: Float): Float = kotlin.math.acos(min(1f, 1f / distance))
}
