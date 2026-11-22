package com.hereliesaz.cameraunroll.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An unwrapped (or reprojected) image plus a per-column confidence used when blending.
 * [pxPerRadian] is the horizontal scale: columns are uniform in angle, both for a panorama
 * (so the result can be mapped to a sphere) and for an unrolled pillar (so one turn's width
 * is known).
 */
class WeightedStrip(
    val image: RgbaImage,
    val columnWeight: FloatArray,
    val pxPerRadian: Float? = null,
) {
    init { require(columnWeight.size == image.width) }
}

/**
 * Flattens the visible face of a cylinder.
 *
 * Axis vertical in the frame. Sampling uniformly in the surface angle θ (not x) is what
 * undoes the foreshortening: each output column is the same arc length.
 *
 * Without a focal length the view is taken as orthographic: x = cx + r·sinθ, rim rows
 * y = rimY + bulge·cosθ. With one, the camera's distance follows from the silhouette width
 * (see [Perspective]) and the true pinhole projection is used, which is what lets photos
 * taken at different distances produce strips of the same scale.
 */
object CylinderUnroller {

    /**
     * @param maxAngleDeg half-width of the arc to keep. Near the silhouette the surface is
     *   seen edge-on and resolution collapses, so 50–70° is the useful range; with
     *   [focalPx] it is also held short of the last few degrees the camera can see.
     * @param focalPx focal length in pixels of [photo]; null for an orthographic view.
     * @param correction residual lean and size error measured against the neighboring strips
     *   (perspective view only), folded into this one resampling.
     * @param pxPerRadius output scale (perspective view only). Photos taken at different
     *   distances given the same value yield strips of the same size, with no second resampling;
     *   null keeps this photo's own scale on the surface nearest the camera.
     * @param profile the pillar's radius down the frame, relative to its guides' (perspective
     *   view only; see [DepthSides.profile]); null for a true cylinder.
     */
    fun unroll(
        photo: RgbaImage,
        guides: CylinderGuides,
        maxAngleDeg: Float = 60f,
        focalPx: Float? = null,
        correction: Stitcher.Correction? = null,
        pxPerRadius: Float? = null,
        profile: FloatArray? = null,
    ): WeightedStrip {
        if (focalPx != null) return unrollPerspective(photo, guides, maxAngleDeg, focalPx, correction ?: Stitcher.Correction(), pxPerRadius, profile)
        val g = guides.normalized()
        require(g.isValid) { "Guides do not enclose a cylinder" }
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val radius = (g.right - g.left) * w / 2f
        val cx = (g.left + g.right) * w / 2f
        val thetaMax = (maxAngleDeg.coerceIn(5f, 89f) * PI / 180.0).toFloat()

        val outW = max(2, (2f * radius * thetaMax).roundToInt())
        val outH = max(2, ((g.bottomY - g.topY) * h).roundToInt())
        val out = RgbaImage(outW, outH)
        val weight = FloatArray(outW)

        for (ox in 0 until outW) {
            val theta = -thetaMax + (ox + 0.5f) / outW * 2f * thetaMax
            val c = cos(theta)
            val sx = cx + radius * sin(theta)
            val yTop = (g.topY + g.topBulge * c) * h
            val yBottom = (g.bottomY + g.bottomBulge * c) * h
            // cosθ is the foreshortening factor: how many source pixels back one output pixel.
            weight[ox] = c
            for (oy in 0 until outH) {
                val v = (oy + 0.5f) / outH
                out[ox, oy] = photo.sample(sx, yTop + v * (yBottom - yTop))
            }
        }
        return WeightedStrip(out, weight, radius)
    }

    private fun unrollPerspective(
        photo: RgbaImage, guides: CylinderGuides, maxAngleDeg: Float, focalPx: Float, fix: Stitcher.Correction, pxPerRadius: Float?,
        profile: FloatArray?,
    ): WeightedStrip {
        var g = guides.normalized()
        require(g.isValid) { "Guides do not enclose a cylinder" }
        // A stretch about the middle row is the same as moving both rims: rows run linearly
        // between them, so the new rims are points on (or just beyond) that same line.
        if (fix.stretch != 0f) {
            val t0 = 0.5f - 0.5f / (1f + fix.stretch)
            val t1 = 0.5f + 0.5f / (1f + fix.stretch)
            g = g.copy(
                topY = g.topY + (g.bottomY - g.topY) * t0, topBulge = g.topBulge + (g.bottomBulge - g.topBulge) * t0,
                bottomY = g.topY + (g.bottomY - g.topY) * t1, bottomBulge = g.topBulge + (g.bottomBulge - g.topBulge) * t1,
            )
        }
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val cx = (g.left + g.right) * w / 2f
        // Where the rims' bulge would vanish: the row the camera looks along. (The rims were
        // drawn for a straight pillar as wide as the guides, so that is the distance they hold.)
        val straight = Perspective.distance((g.right - g.left) * w / 2f, focalPx)
        val horizon = ((g.topY - straight * g.topBulge) + (g.bottomY - straight * g.bottomBulge)) / 2f * h
        // A pillar whose radius changes has no one radius to measure by. The guides give it
        // where they happen to sit in the frame, which changes as the phone is tipped; the
        // radius at the camera's own height does not, so that one is the unit, for every photo.
        val atEye = profile?.let { radiusAt(it, horizon / h) } ?: 1f
        val d = if (atEye == 1f) straight else Perspective.distance((g.right - g.left) * w / 2f * atEye, focalPx)
        // The guides' rows count heights in the radius they were drawn for; those same numbers
        // are kept as heights in the unit above, so every photo's strip covers the same stretch.
        val scale = Perspective.frontScale(straight, focalPx)
        val limit = Perspective.tangentAngle(d) - TANGENT_MARGIN
        val thetaMax = min((maxAngleDeg.coerceIn(5f, 89f) * PI / 180.0).toFloat(), max(0.1f, limit))

        // By default one output pixel = one source pixel on the surface nearest the camera.
        val frontTop = (g.topY + g.topBulge) * h
        val frontBottom = (g.bottomY + g.bottomBulge) * h
        val outScale = pxPerRadius ?: scale
        val outW = max(2, (2f * outScale * thetaMax).roundToInt())
        val outH = max(2, ((frontBottom - frontTop) / scale * outScale).roundToInt())
        val out = RgbaImage(outW, outH)
        val weight = FloatArray(outW)
        // How squarely the camera sees a column: 1 at the front, 0 at the tangent.
        for (ox in 0 until outW) {
            val c = cos(-thetaMax + (ox + 0.5f) / outW * 2f * thetaMax)
            weight[ox] = max(0f, (d * c - 1f) / (d - 1f))
        }
        val edge = Perspective.tangentAngle(d)
        for (oy in 0 until outH) {
            val v = (oy + 0.5f) / outH
            // Leftover lean: this row is read a little to one side, in surface angle.
            val lean = fix.shear * (oy + 0.5f - outH / 2f) / outScale
            if (profile != null) {
                // Not a true cylinder: this row's radius is rho times the guides'. The surface
                // is nearer or farther by that much, which moves both where a given angle lands
                // sideways and how far the row sits from the horizon; columns stay uniform in
                // angle, so a swelling is flattened like the rest.
                val front = frontTop + v * (frontBottom - frontTop)
                // The profile was read off the silhouette, which lies deeper into the pillar than
                // its front and so shows this same height a little nearer the horizon.
                val rho = radiusAt(profile, (horizon + (front - horizon) * (straight - 1f) / (d - 1f / d)) / h) / atEye
                val rowEdge = Perspective.tangentAngle(d / rho)
                for (ox in 0 until outW) {
                    val theta = -thetaMax + (ox + 0.5f) / outW * 2f * thetaMax - lean
                    if (theta <= -rowEdge || theta >= rowEdge) continue
                    val c = cos(theta)
                    val sx = cx + focalPx * rho * sin(theta) / (d - rho * c)
                    val sy = horizon + (front - horizon) * (straight - 1f) / (d - rho * c)
                    out[ox, oy] = photo.sampleCubic(sx, sy)
                }
                continue
            }
            for (ox in 0 until outW) {
                val theta = -thetaMax + (ox + 0.5f) / outW * 2f * thetaMax - lean
                if (theta <= -edge || theta >= edge) continue // past the silhouette: no surface there
                val c = cos(theta)
                val sx = cx + focalPx * sin(theta) / (d - c)
                // Rows shrink toward the horizon as the surface recedes: 1 at the front, D/(D+1) at the tangent.
                val k = (d + 1f) * (d - 1f) / (d - c)
                val yTop = (g.topY - d * g.topBulge + g.topBulge * k) * h
                val yBottom = (g.bottomY - d * g.bottomBulge + g.bottomBulge * k) * h
                out[ox, oy] = photo.sampleCubic(sx, yTop + v * (yBottom - yTop))
            }
        }
        return WeightedStrip(out, weight, outScale)
    }

    /** [profile] (evenly spaced from the top of the frame to the bottom) read at height [y] in 0..1. */
    private fun radiusAt(profile: FloatArray, y: Float): Float {
        val f = y.coerceIn(0f, 1f) * (profile.size - 1)
        val i = min(profile.size - 2, f.toInt())
        return profile[i] + (profile[i + 1] - profile[i]) * (f - i)
    }

    /** This photo's own scale on the surface nearest the camera, in pixels per pillar radius. */
    fun nativeScale(photo: RgbaImage, guides: CylinderGuides, focalPx: Float): Float {
        val g = guides.normalized()
        return Perspective.frontScale(Perspective.distance((g.right - g.left) * photo.width / 2f, focalPx), focalPx)
    }

    /** Stay this far (radians) inside the silhouette, where a pixel covers too much surface. */
    private const val TANGENT_MARGIN = 0.1f
}
