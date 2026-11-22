package com.hereliesaz.cameraunroll.imaging

/**
 * Finished render: pixels to show, plus how to write them out.
 * @property pxPerRadian horizontal pixels per radian of the subject's surface, when known
 *   (an unrolled pillar); for a pillar this is also pixels per radius, so it gives its shape.
 */
class Rendered(val image: RgbaImage, val wraps: Boolean, private val xmp: String?, val pxPerRadian: Float? = null) {
    /** Radians of the way around the subject the image covers; a full turn when it [wraps]. */
    val arc: Float?
        get() = if (wraps) (2 * kotlin.math.PI).toFloat() else pxPerRadian?.let { kotlin.math.min((2 * kotlin.math.PI).toFloat(), image.width / it) }

    /** Height of the image in subject radii. */
    val heightRadii: Float?
        get() = when {
            pxPerRadian != null -> image.height / pxPerRadian
            wraps -> image.height / (image.width / (2 * kotlin.math.PI).toFloat())
            else -> null
        }

    fun toJpeg(quality: Int = 92): ByteArray {
        val jpeg = ImageCodec.encodeJpeg(image, quality)
        return if (xmp == null) jpeg else PanoramaXmp.inject(jpeg, xmp)
    }
}

/** The user-facing operations, end to end. CPU-bound; call off the main thread. */
object Pipelines {
    /**
     * How far above or below its neighbor a hand-held pillar strip may sit (fraction of its
     * height). Each strip's vertical place rests on an estimated horizon, and a photographer who
     * raises, lowers or tips the phone between shots moves it by far more than a steady sweep does.
     */
    private const val HANDHELD_DY = 0.3f


    /**
     * Capture order of [photos], recovered from how their flattened strips overlap. Needs the
     * same guides (or lens FOV) as the final render, so redo it after correcting them.
     */
    fun arrangeCylinder(
        photos: List<RgbaImage>,
        guides: List<CylinderGuides>,
        maxAngleDeg: Float,
        progress: (Float) -> Unit = {},
        rank: IntArray? = null,
        focalPx: List<Float?>? = null,
    ): IntArray = Arranger.order(
        photos.indices.map { CylinderUnroller.unroll(photos[it], guides[it], maxAngleDeg, focalPx?.get(it)).image },
        progress,
        rank,
        if (focalPx != null) HANDHELD_DY else Stitcher.MAX_DY,
    )

    fun arrangePanorama(photos: List<RgbaImage>, hfovDeg: Float, progress: (Float) -> Unit = {}, rank: IntArray? = null): IntArray =
        Arranger.order(photos.map { CylindricalProjector.project(it, hfovDeg).image }, progress, rank)

    /**
     * Walk-around photos of a cylinder -> its flattened surface.
     * @param focalPx per-photo focal length in pixels; enables the perspective unroll.
     * @param profiles per-photo radius profile for pillars that swell or pinch ([DepthSides.profile]).
     * @param fullHeight use everything each photo shows of the pillar instead of the rims in
     *   [guides] (see [Perspective.extend]); for rims that were placed by geometry, not by hand.
     * @param trimToTurn the photos are a continuous sequence (video) that may have gone past
     *   one full turn; frames after the first view returns are dropped.
     */
    fun unrollCylinder(
        photos: List<RgbaImage>,
        guides: List<CylinderGuides>,
        maxAngleDeg: Float,
        fullTurn: Boolean,
        trimToTurn: Boolean = false,
        progress: (Float) -> Unit = {},
        focalPx: List<Float?>? = null,
        fullHeight: Boolean = false,
        profiles: List<FloatArray?>? = null,
    ): Rendered {
        require(photos.size == guides.size)
        @Suppress("NAME_SHADOWING")
        val guides = if (fullHeight && focalPx != null && focalPx.all { it != null }) {
            Perspective.extend(guides, photos.map { it.width to it.height }, focalPx.map { it!! })
        } else guides
        // One scale for all: the median photo's, so nearer shots are not blown up to match
        // nor the best ones thrown away, and no strip is resampled a second time to fit.
        val perspective = focalPx != null && focalPx.all { it != null }
        val scale = if (!perspective) null else
            photos.indices.map { CylinderUnroller.nativeScale(photos[it], guides[it], focalPx!![it]!!) }.sorted()[photos.size / 2]
        var strips = photos.indices.map { i ->
            progress(0.15f * i / photos.size)
            CylinderUnroller.unroll(photos[i], guides[i], maxAngleDeg, focalPx?.get(i), null, scale, profiles?.get(i))
        }
        // Second pass: what still separates neighbors after geometry (a degree of lean, a few
        // percent of size) is measured on the strips and unrolled away.
        if (perspective && strips.size >= 2) {
            val fixes = Stitcher.corrections(strips.map { it.image }, HANDHELD_DY)
            strips = photos.indices.map { i ->
                progress(0.15f + 0.15f * i / photos.size)
                CylinderUnroller.unroll(photos[i], guides[i], maxAngleDeg, focalPx!![i], fixes[i], scale, profiles?.get(i))
            }
        }
        if (trimToTurn && fullTurn) strips = strips.take(Arranger.framesInOneTurn(strips.map { it.image }))
        val result = if (strips.size == 1) StitchResult(strips[0].image, false, null)
        else Stitcher.stitch(strips, fullTurn, if (perspective) HANDHELD_DY else Stitcher.MAX_DY) { progress(0.3f + 0.7f * it) }
        // Strips are uniform in surface angle and carry their scale, so the result's width
        // says how far around the pillar it goes.
        val ppr = result.pxPerRadian ?: strips[0].pxPerRadian
        return Rendered(result.image, result.closedLoop, xmp = null, pxPerRadian = ppr)
    }

    /** Photos taken turning in place -> equirectangular band with Photo Sphere metadata. */
    fun panorama(
        photos: List<RgbaImage>,
        hfovDeg: Float,
        fullTurn: Boolean,
        trimToTurn: Boolean = false,
        progress: (Float) -> Unit = {},
    ): Rendered {
        var strips = photos.mapIndexed { i, p ->
            progress(0.3f * i / photos.size)
            CylindricalProjector.project(p, hfovDeg)
        }
        if (trimToTurn && fullTurn) strips = strips.take(Arranger.framesInOneTurn(strips.map { it.image }))
        val stitched = if (strips.size == 1) StitchResult(strips[0].image, false, strips[0].pxPerRadian)
        else Stitcher.stitch(strips, fullTurn, Stitcher.MAX_DY) { progress(0.3f + 0.6f * it) }
        val ppr = stitched.pxPerRadian ?: strips[0].pxPerRadian!!
        val band = CylindricalProjector.toEquirectangular(stitched.image, ppr)
        progress(1f)
        return Rendered(band, stitched.closedLoop, PanoramaXmp.gpano(band.width, band.height, ppr))
    }
}
