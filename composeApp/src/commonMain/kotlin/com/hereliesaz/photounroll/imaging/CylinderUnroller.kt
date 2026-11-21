package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An unwrapped (or reprojected) image plus a per-column confidence used when blending.
 * [pxPerRadian] is set when columns are uniform in angle (panorama), so the stitched
 * result can be mapped to a sphere.
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
     */
    fun unroll(
        photo: RgbaImage,
        guides: CylinderGuides,
        maxAngleDeg: Float = 60f,
        focalPx: Float? = null,
        correction: Stitcher.Correction? = null,
        pxPerRadius: Float? = null,
    ): WeightedStrip {
        if (focalPx != null) return unrollPerspective(photo, guides, maxAngleDeg, focalPx, correction ?: Stitcher.Correction(), pxPerRadius)
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
        return WeightedStrip(out, weight)
    }

    private fun unrollPerspective(
        photo: RgbaImage, guides: CylinderGuides, maxAngleDeg: Float, focalPx: Float, fix: Stitcher.Correction, pxPerRadius: Float?,
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
        val d = Perspective.distance((g.right - g.left) * w / 2f, focalPx)
        val scale = Perspective.frontScale(d, focalPx)
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
        return WeightedStrip(out, weight)
    }

    /** This photo's own scale on the surface nearest the camera, in pixels per pillar radius. */
    fun nativeScale(photo: RgbaImage, guides: CylinderGuides, focalPx: Float): Float {
        val g = guides.normalized()
        return Perspective.frontScale(Perspective.distance((g.right - g.left) * photo.width / 2f, focalPx), focalPx)
    }

    /** Stay this far (radians) inside the silhouette, where a pixel covers too much surface. */
    private const val TANGENT_MARGIN = 0.1f
}
