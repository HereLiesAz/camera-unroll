package com.hereliesaz.cameraunroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImagingTest {
    /** Non-repeating texture around the circumference, varying along the axis too. */
    private fun texture(u: Double, v: Double): Int {
        val s = 0.45 * sin(u) + 0.3 * sin(7 * u + 1) + 0.25 * sin(17 * u + 6 * v)
        val g = (128 + 120 * s).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private val guides = CylinderGuides(left = 0.25f, right = 0.75f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.85f, bottomBulge = 0.06f)

    /** Orthographic render of the textured cylinder turned by [phi]. */
    private fun renderCylinder(phi: Double, w: Int = 400, h: Int = 300): RgbaImage {
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF404040.toInt() })
        val r = (guides.right - guides.left) * w / 2.0
        val cx = (guides.left + guides.right) * w / 2.0
        for (x in 0 until w) {
            val sx = (x + 0.5 - cx) / r
            if (abs(sx) >= 1) continue
            val theta = asin(sx)
            val c = cos(theta)
            val yt = (guides.topY + guides.topBulge * c) * h
            val yb = (guides.bottomY + guides.bottomBulge * c) * h
            for (y in 0 until h) {
                val v = (y + 0.5 - yt) / (yb - yt)
                if (v in 0.0..1.0) img[x, y] = texture(theta + phi, v)
            }
        }
        return img
    }

    private fun gray(p: Int) = (p shr 8) and 0xFF

    @Test
    fun unrollRecoversSurfaceUniformlyInAngle() {
        val strip = CylinderUnroller.unroll(renderCylinder(0.0), guides, 60f).image
        val r = (guides.right - guides.left) * 400 / 2.0
        assertEquals((2 * r * PI / 3).roundToInt(), strip.width, "arc length of ±60°")
        var err = 0.0
        var n = 0
        for (ox in 4 until strip.width - 4 step 3) for (oy in 4 until strip.height - 4 step 5) {
            val theta = -PI / 3 + (ox + 0.5) / strip.width * 2 * PI / 3
            err += abs(gray(strip[ox, oy]) - gray(texture(theta, (oy + 0.5) / strip.height)))
            n++
        }
        assertTrue(err / n < 12, "mean abs error ${err / n}")
    }

    @Test
    fun walkAroundClosesToOneCircumference() {
        val views = 8
        val strips = (0 until views).map { k ->
            CylinderUnroller.unroll(renderCylinder(2 * PI * k / views), guides, 60f)
        }
        val result = Stitcher.stitch(strips, closeLoop = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(result.closedLoop)
        assertTrue(abs(result.image.width - expected) / expected < 0.03, "width ${result.image.width} vs $expected")
    }

    @Test
    fun reversedCaptureOrderIsDetected() {
        val strips = (0 until 6).map { k ->
            CylinderUnroller.unroll(renderCylinder(-2 * PI * k / 6), guides, 60f)
        }
        val result = Stitcher.stitch(strips, closeLoop = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(abs(result.image.width - expected) / expected < 0.03, "width ${result.image.width} vs $expected")
    }

    @Test
    fun panoramaFromRotatingCamera() {
        // Pinhole frames of a textured world cylinder, camera turning 45° per frame.
        val w = 320; val h = 200; val hfov = 70.0
        val f = (w / 2) / tan(hfov * PI / 360)
        val frames = (0 until 8).map { k ->
            RgbaImage(w, h, IntArray(w * h) { i ->
                val x = i % w + 0.5 - w / 2.0
                val y = i / w + 0.5 - h / 2.0
                val theta = kotlin.math.atan2(x, f)
                val elev = y / kotlin.math.sqrt(x * x + f * f)
                texture(theta + 2 * PI * k / 8, elev)
            })
        }
        val rendered = Pipelines.panorama(frames, hfov.toFloat(), fullTurn = true)
        assertTrue(rendered.wraps)
        val expected = 2 * PI * f
        assertTrue(abs(rendered.image.width - expected) / expected < 0.03, "width ${rendered.image.width} vs $expected")
    }

    @Test
    fun exifOrientationAndXmpInjection() {
        // SOI, APP1 Exif (big-endian TIFF, one IFD entry: orientation = 6), SOS, EOI.
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val len = body.size + 2
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            body + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(6, Exif.orientation(jpeg))

        val out = PanoramaXmp.inject(jpeg, PanoramaXmp.gpano(1000, 200, 1000 / (2 * PI).toFloat()))
        assertEquals(6, Exif.orientation(out), "EXIF still first")
        val text = out.decodeToString()
        assertTrue("GPano:FullPanoWidthPixels=\"1000\"" in text)
        assertTrue("GPano:CroppedAreaTopPixels=\"150\"" in text)
        assertTrue(text.indexOf("ns.adobe.com/xap") > text.indexOf("Exif"))
    }

    @Test
    fun orientationRotatesUpright() {
        val img = RgbaImage(2, 1, intArrayOf(1, 2))
        val r = img.withExifOrientation(6)
        assertEquals(1, r.width); assertEquals(2, r.height)
        assertEquals(1, r[0, 0]); assertEquals(2, r[0, 1])
    }
}

class EdgeSuggesterTest {
    @Test
    fun findsSilhouetteAndRims() {
        val g = CylinderGuides(left = 0.3f, right = 0.7f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.8f, bottomBulge = 0.06f)
        val w = 400; val h = 400
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF303840.toInt() })
        val r = (g.right - g.left) * w / 2f; val cx = (g.left + g.right) * w / 2f
        for (x in 0 until w) {
            val s = (x + 0.5f - cx) / r
            if (abs(s) >= 1f) continue
            val c = kotlin.math.sqrt(1f - s * s)
            for (y in 0 until h) {
                val yy = (y + 0.5f) / h
                // Stripes inside the body must not be mistaken for edges.
                if (yy > g.topY + g.topBulge * c && yy < g.bottomY + g.bottomBulge * c) {
                    img[x, y] = if ((x / 9) % 2 == 0) 0xFFE0D0C0.toInt() else 0xFFB02020.toInt()
                }
            }
        }
        val s = EdgeSuggester.suggest(img)
        val tol = 0.015f
        assertTrue(abs(s.left - g.left) < tol, "left ${s.left}")
        assertTrue(abs(s.right - g.right) < tol, "right ${s.right}")
        assertTrue(abs(s.topY - g.topY) < tol, "topY ${s.topY}")
        assertTrue(abs(s.topBulge - g.topBulge) < tol, "topBulge ${s.topBulge}")
        assertTrue(abs(s.bottomY - g.bottomY) < tol, "bottomY ${s.bottomY}")
        assertTrue(abs(s.bottomBulge - g.bottomBulge) < tol, "bottomBulge ${s.bottomBulge}")
    }
}

class PhotoAdjustTest {
    private fun pattern(w: Int, h: Int) = RgbaImage(w, h, IntArray(w * h) { i ->
        val g = ((i % w) * 7 + (i / w) * 13) and 0xFF
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    })

    @Test
    fun identityReturnsSameImage() {
        val img = pattern(20, 10)
        assertTrue(PhotoAdjust().apply(img) === img)
    }

    @Test
    fun homographyHitsCorners() {
        val dst = listOf(NPoint(2f, 3f), NPoint(50f, 1f), NPoint(60f, 40f), NPoint(-5f, 30f))
        val h = Homography.fromUnitSquare(dst)
        PhotoAdjust.FULL_FRAME.zip(dst).forEach { (s, d) ->
            val p = h.map(s.x, s.y)
            assertTrue(abs(p.x - d.x) < 1e-3f && abs(p.y - d.y) < 1e-3f, "$s -> $p, want $d")
        }
    }

    @Test
    fun quarterTurnMatchesExactRotation() {
        val img = pattern(16, 16)
        val turned = PhotoAdjust(rotationDeg = 90f).apply(img)
        val exact = img.rotated90()
        var diff = 0
        for (y in 2 until 14) for (x in 2 until 14) diff += abs(((turned[x, y] shr 8) and 0xFF) - ((exact[x, y] shr 8) and 0xFF))
        assertTrue(diff / 144 < 2, "mean diff ${diff / 144}")
    }

    @Test
    fun warpCropsToQuad() {
        val img = pattern(40, 40)
        // Selecting the right half stretches it to fill the frame.
        val out = PhotoAdjust(quad = listOf(NPoint(0.5f, 0f), NPoint(1f, 0f), NPoint(1f, 1f), NPoint(0.5f, 1f))).apply(img)
        assertEquals(img.sample(30.25f, 10.5f), out.sample(20.5f, 10.5f))
    }
}

class ArrangeTest {
    private val guides = CylinderGuides(left = 0.25f, right = 0.75f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.85f, bottomBulge = 0.06f)

    /** Pink-noise-like surface: many harmonics with fixed pseudo-random phases, so no two views alias. */
    private val harmonics = run {
        var seed = 12345L
        fun next(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 10000) / 10000.0 }
        List(60) { i -> doubleArrayOf((i + 1).toDouble(), 1.0 / (i + 1) * 0.9 + 0.02, next() * 2 * PI, (next() - 0.5) * 8) }
    }

    private fun texture(u: Double, v: Double): Int {
        var s = 0.0
        var norm = 0.0
        for (h in harmonics) { s += h[1] * sin(h[0] * u + h[2] + h[3] * v); norm += h[1] * h[1] }
        val g = (128 + 120 * s / kotlin.math.sqrt(norm)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun render(phi: Double, w: Int = 400, h: Int = 300): RgbaImage {
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF404040.toInt() })
        val r = (guides.right - guides.left) * w / 2.0
        val cx = (guides.left + guides.right) * w / 2.0
        for (x in 0 until w) {
            val sx = (x + 0.5 - cx) / r
            if (abs(sx) >= 1) continue
            val theta = asin(sx)
            val c = cos(theta)
            val yt = (guides.topY + guides.topBulge * c) * h
            val yb = (guides.bottomY + guides.bottomBulge * c) * h
            for (y in 0 until h) {
                val v = (y + 0.5 - yt) / (yb - yt)
                if (v in 0.0..1.0) img[x, y] = texture(theta + phi, v)
            }
        }
        return img
    }

    /** True when [order] walks the ring one step at a time, in either direction. */
    private fun isRingOrder(order: IntArray, n: Int): Boolean {
        fun step(a: Int, b: Int) = (b - a + n) % n
        val steps = (0 until order.size - 1).map { step(order[it], order[it + 1]) }
        return steps.all { it == 1 } || steps.all { it == n - 1 }
    }

    @Test
    fun shuffledWalkAroundIsPutBackInOrder() {
        val n = 10
        val shuffle = intArrayOf(6, 2, 9, 0, 4, 7, 1, 8, 3, 5)
        val photos = shuffle.map { render(2 * PI * it / n) }
        val order = Pipelines.arrangeCylinder(photos, List(n) { guides }, 60f)
        val recovered = IntArray(n) { shuffle[order[it]] }
        // A ring has no start: the path may begin anywhere, so check it as a cyclic walk.
        val steps = (0 until n - 1).map { (recovered[it + 1] - recovered[it] + n) % n }
        assertTrue(steps.all { it == 1 } || steps.all { it == n - 1 }, "recovered ${recovered.toList()}")
    }

    @Test
    fun partialArcKeepsItsOrder() {
        val shuffle = intArrayOf(3, 0, 4, 1, 2)
        val photos = shuffle.map { render(0.5 * it) }
        val order = Pipelines.arrangeCylinder(photos, List(5) { guides }, 60f)
        val recovered = order.map { shuffle[it] }
        assertTrue(recovered == listOf(0, 1, 2, 3, 4) || recovered == listOf(4, 3, 2, 1, 0), "recovered $recovered")
    }

    @Test
    fun overshotVideoIsCutAtOneTurn() {
        // 48 frames across 1.25 turns: frame 38.4 would be the first view again.
        val total = 48
        val photos = (0 until total).map { render(2 * PI * 1.25 * it / total) }
        val strips = photos.map { CylinderUnroller.unroll(it, guides, 60f).image }
        val keep = Arranger.framesInOneTurn(strips)
        assertTrue(keep in 34..39, "kept $keep")
        val result = Pipelines.unrollCylinder(photos, List(total) { guides }, 60f, fullTurn = true, trimToTurn = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(abs(result.image.width - expected) / expected < 0.04, "width ${result.image.width} vs $expected")
    }

    @Test
    fun undershotVideoIsLeftAlone() {
        val total = 30
        val photos = (0 until total).map { render(2 * PI * 0.9 * it / total) }
        val strips = photos.map { CylinderUnroller.unroll(it, guides, 60f).image }
        assertEquals(total, Arranger.framesInOneTurn(strips))
    }
}

class PerspectiveTest {
    @Test
    fun distanceFollowsFromWidthAndFocalLength() {
        // Camera 5 radii from the axis: the silhouette's half-width is f / sqrt(D² - 1).
        val f = 1000f
        val half = f / kotlin.math.sqrt(24f)
        assertTrue(abs(Perspective.distance(half, f) - 5f) < 1e-3f)
        assertTrue(abs(Perspective.frontScale(5f, f) - 250f) < 1e-3f)
    }

    @Test
    fun rimIsFlatOnTheHorizonAndBulgesAwayFromIt() {
        val (y0, b0) = Perspective.rim(0.5f, 0.5f, 4f)
        assertTrue(abs(y0 - 0.5f) < 1e-6f && abs(b0) < 1e-6f)
        val (yTop, bTop) = Perspective.rim(0.1f, 0.5f, 4f)
        val (yBottom, bBottom) = Perspective.rim(0.9f, 0.5f, 4f)
        assertTrue(bTop < 0f && bBottom > 0f, "top bulges up, bottom down")
        assertTrue(abs(yTop + bTop - 0.1f) < 1e-6f && abs(yBottom + bBottom - 0.9f) < 1e-6f, "front of each rim is where asked")
    }

    @Test
    fun harmonizeGivesNearAndFarPhotosTheSameStretchOfPillar() {
        // Same pillar, one photo taken closer (wider in frame). After harmonizing, the rims'
        // front rows span the same number of pillar radii in both.
        val size = 1200 to 1600
        val f = Perspective.focalPx(26f, size.first, size.second)
        val guides = listOf(CylinderGuides(0.3f, 0.7f), CylinderGuides(0.38f, 0.62f))
        val out = Perspective.harmonize(guides, listOf(size, size), listOf(f, f), listOf(0.5f, 0.5f))
        fun radii(g: CylinderGuides): Float {
            val d = Perspective.distance((g.right - g.left) / 2f * size.first, f)
            return ((g.bottomY + g.bottomBulge) - (g.topY + g.topBulge)) * size.second / Perspective.frontScale(d, f)
        }
        assertTrue(abs(radii(out[0]) - radii(out[1])) < 0.02f, "${radii(out[0])} vs ${radii(out[1])}")
        assertEquals(guides[0].left, out[0].left)
    }
}

class DepthSidesTest {
    /** A leaning bright column on a dark ground, with a blob of equal depth at its foot. */
    private fun depth(lean: Float): RgbaImage {
        val w = 280; val h = 392
        return RgbaImage(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val cx = 140f + lean * (y - h / 2f)
            val g = when {
                abs(x - cx) < 45f -> 235 - (abs(x - cx) / 45f * 25f).toInt() // round surface: gently darker to the sides
                y > 330 && x in 60..230 -> 230 // plants against the foot
                else -> 30 + y / 10
            }
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        })
    }

    @Test
    fun findsSidesAndLeanDespiteClutterAtTheFoot() {
        val s = DepthSides.measure(depth(0.03f))!!
        assertTrue(abs(s.leftAt - 95f) < 3f, "left ${s.leftAt}")
        assertTrue(abs(s.rightAt - 185f) < 3f, "right ${s.rightAt}")
        assertTrue(abs(s.lean - 0.03f) < 0.006f, "lean ${s.lean}")
        assertTrue(abs(s.spread) < 0.006f, "spread ${s.spread}")
    }

    @Test
    fun nothingNearMeansNoPillar() {
        val flat = RgbaImage(280, 392, IntArray(280 * 392) { 0xFF202020.toInt() })
        assertEquals(null, DepthSides.measure(flat))
    }
}

class PillarShapeTest {
    /** A bright column whose half-width follows [half] (row as -1..1), on a dark ground. */
    private fun depth(half: (Float) -> Float): RgbaImage {
        val w = 280; val h = 392
        return RgbaImage(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val u = (y - h / 2f) / (h / 2f)
            val g = if (abs(x - 140f) < half(u)) 230 else 30
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        })
    }

    @Test
    fun aStraightPillarHasNoProfile() = assertEquals(null, DepthSides.profile(depth { 45f }))

    @Test
    fun aTaperIsLeftToTheLeveler() = assertEquals(null, DepthSides.profile(depth { 45f + 6f * it }))

    @Test
    fun aBarrelIsMeasured() {
        // 12% wider at mid-height than at the frame's ends.
        val p = DepthSides.profile(depth { 45f * (1.12f - 0.12f * it * it) })!!
        val mid = p[p.size / 2]
        assertTrue(abs(p.first() / mid - 1f / 1.12f) < 0.03f, "top ${p.first() / mid}")
        assertTrue(abs(p.last() / mid - 1f / 1.12f) < 0.03f, "bottom ${p.last() / mid}")
    }

    @Test
    fun aFlatProfileUnrollsLikeACylinder() {
        val w = 300; val h = 400; val f = 320f
        // Smooth gradients, so a sub-pixel difference in sampling shows as a small difference.
        val img = RgbaImage(w, h, IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x + y) * 255 / (w + h))
        })
        // Rims a level camera would see: both agree on where the horizon is.
        val d = Perspective.distance(0.2f * w, f)
        val (topY, topBulge) = Perspective.rim(0.12f, 0.5f, d)
        val (bottomY, bottomBulge) = Perspective.rim(0.88f, 0.5f, d)
        val g = CylinderGuides(0.3f, 0.7f, topY, topBulge, bottomY, bottomBulge)
        val plain = CylinderUnroller.unroll(img, g, 55f, f).image
        val flat = CylinderUnroller.unroll(img, g, 55f, f, profile = FloatArray(DepthSides.PROFILE_SAMPLES) { 1f }).image
        assertEquals(plain.width, flat.width); assertEquals(plain.height, flat.height)
        var worst = 0
        for (i in plain.pixels.indices) for (shift in intArrayOf(16, 8, 0)) {
            worst = maxOf(worst, abs(((plain.pixels[i] shr shift) and 0xFF) - ((flat.pixels[i] shr shift) and 0xFF)))
        }
        assertTrue(worst <= 3, "largest channel difference $worst")
    }
}

class ShapeConsensusTest {
    private val waist = FloatArray(DepthSides.PROFILE_SAMPLES) { 1f + 0.06f * abs(it - 4) / 4f }
    private val barrel = FloatArray(DepthSides.PROFILE_SAMPLES) { 1f - 0.06f * abs(it - 4) / 4f }

    @Test
    fun twoOfEightIsClutterNotShape() =
        assertEquals(null, DepthSides.consensus(listOf(null, null, waist, null, null, null, null, waist)))

    @Test
    fun mostPhotosAgreeingIsAShape() {
        val p = DepthSides.consensus(listOf(waist, waist, null, waist, waist, waist))!!
        assertTrue(abs(p.first() - 1.06f) < 1e-4f && abs(p[4] - 1f) < 1e-4f)
    }

    @Test
    fun oppositeShapesDoNotAddUp() =
        assertEquals(null, DepthSides.consensus(listOf(waist, barrel, waist, barrel, null)))
}

/** A square beam drawn by ray casting, for [PrismTest] and [BeamTest]. Lengths are in half-widths. */
private object BeamScene {
    const val W = 360
    const val H = 480
    const val FOCAL = 420f
    const val HORIZON = 250f

    /** What is painted on face [k] at [s] (0..1 along it) and height [y]: smooth, and different on every face. */
    fun paint(k: Int, s: Float, y: Float): Int {
        // Each face has its own grain: were they the same waves merely shifted, one face could
        // pass for another seen a little higher up.
        fun wave(a: Float, b: Float, c: Float) = sin((a + 0.6f * k) * s * 6.283f + (b + 0.8f * k * k - 1.3f * k) * y + c + k * 1.7f)
        val v = 0.5f + 0.2f * wave(1f, 2.1f, 0.3f) + 0.15f * wave(3f, -4.3f, 1.1f) + 0.12f * wave(5f, 7.9f, 2.4f + k)
        val g = (v * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (((g * (3 + k)) / 6) shl 8) or (255 - g)
    }

    /**
     * The beam seen from [d] half-widths away with corner [corner] turned [phi] radians off
     * straight ahead (null: face [corner] seen square on). Faces count up to the right.
     */
    fun photo(phi: Float?, d: Float, corner: Int): RgbaImage {
        // Left to right: (x0, z0, x1, z1, face).
        val faces = if (phi == null) listOf(floatArrayOf(-1f, d - 1f, 1f, d - 1f, corner.toFloat())) else {
            val c = 1.4142135f * cos(phi); val s = 1.4142135f * sin(phi)
            listOf(
                floatArrayOf(-c, d - s, s, d - c, ((corner + 3) % 4).toFloat()),
                floatArrayOf(s, d - c, c, d + s, corner.toFloat()),
            )
        }
        val img = RgbaImage(W, H, IntArray(W * H) { 0xFF202020.toInt() })
        for (px in 0 until W) {
            val dx = (px + 0.5f - W / 2f) / FOCAL
            for (f in faces) {
                // Where the ray x = dx·z meets the face's line.
                val ex = f[2] - f[0]; val ez = f[3] - f[1]
                val den = ex - dx * ez
                if (abs(den) < 1e-6f) continue
                val t = (dx * f[1] - f[0]) / den
                if (t < 0f || t > 1f) continue
                val z = f[1] + t * ez
                for (py in 0 until H) img[px, py] = paint(f[4].toInt(), t, (py + 0.5f - HORIZON) * z / FOCAL)
            }
        }
        return img
    }

    /** The beam's edges in [photo]'s frame, exactly, as [Prism.read] would report them. */
    fun reading(phi: Float?, d: Float): Prism.Reading {
        fun at(x: Float, z: Float) = 0.5f + FOCAL * x / z / W
        if (phi == null) return Prism.Reading(at(-1f, d - 1f), null, at(1f, d - 1f))
        val c = 1.4142135f * cos(phi); val s = 1.4142135f * sin(phi)
        return Prism.Reading(at(-c, d - s), at(s, d - c), at(c, d + s))
    }
}

class PrismTest {
    @Test
    fun theCameraIsFoundFromWhereTheEdgesFall() {
        for ((phi, d) in listOf(0.3f to 8f, -0.5f to 6f, 0f to 10f, 0.55f to 7f)) {
            val r = BeamScene.reading(phi, d)
            fun px(v: Float) = (v - 0.5f) * BeamScene.W
            val pose = assertNotNull(Prism.pose(px(r.left), px(r.corner!!), px(r.right), BeamScene.FOCAL))
            assertEquals(phi, pose.phi!!, 0.02f)
            assertEquals(d, pose.d, 0.1f)
            assertEquals(0f, pose.e, 0.05f)
        }
    }

    @Test
    fun aFaceComesOutAsItWasPainted() {
        val phi = 0.4f; val d = 7f
        val r = BeamScene.reading(phi, d)
        fun px(v: Float) = (v - 0.5f) * BeamScene.W
        val pose = Prism.pose(px(r.left), px(r.corner!!), px(r.right), BeamScene.FOCAL)!!
        val faces = Prism.faces(BeamScene.photo(phi, d, corner = 1), pose, BeamScene.HORIZON, BeamScene.FOCAL, 40f, up = 2f, down = 2f)
        assertEquals(2, faces.size)
        // The corner is turned to the right, so the left face (face 0) is the one seen more squarely.
        assertTrue(faces[0].squarely > faces[1].squarely)
        val img = faces[0].image
        var off = 0f; var n = 0
        for (v in 10 until img.height - 10 step 7) for (u in 6 until img.width - 6 step 5) {
            val want = BeamScene.paint(0, (u + 0.5f) / img.width, -2f + (v + 0.5f) / 40f)
            off += abs(((img[u, v] shr 16) and 0xFF) - ((want shr 16) and 0xFF)); n++
        }
        assertTrue(off / n < 6f, "mean error ${off / n}")
    }

    @Test
    fun aBeamIsToldFromARoundPillar() {
        fun reading(ridge: Float?) = Prism.Reading(0.3f, ridge?.let { 0.3f + 0.4f * it }, 0.7f)
        // Round: the nearest point stays in the middle.
        assertFalse(Prism.isBeam(List(8) { reading(0.5f + 0.03f * (it % 3 - 1)) }))
        // A beam: flat faces, and corners that wander from side to side.
        assertTrue(Prism.isBeam(listOf(reading(null), reading(0.3f), reading(0.68f), reading(null), reading(0.33f), reading(0.5f))))
        assertFalse(Prism.isBeam(listOf(reading(null), null)))
    }

    @Test
    fun softEdgesAreMovedOntoThePhotosOwn() {
        // A bright pillar from 30% to 70% of the frame; the depth image put its sides a little inside.
        val img = RgbaImage(400, 300, IntArray(400 * 300) { if (it % 400 in 120 until 280) 0xFFD0D0D0.toInt() else 0xFF202020.toInt() })
        val sharp = EdgeSuggester.sharpen(img, listOf(0.315f, 0.69f), listOf(0f, 0f), listOf(0.025f, 0.025f))
        assertEquals(0.3f, sharp[0], 0.006f)
        assertEquals(0.7f, sharp[1], 0.006f)
        // Nothing crisp within reach: left where it was.
        assertEquals(0.5f, EdgeSuggester.sharpen(img, listOf(0.5f), listOf(0f), listOf(0.025f))[0])
    }
}

class BeamTest {
    @Test
    fun photosAroundABeamBecomeItsFourFacesInOrder() {
        // Seven photos walking around, in no particular order, from slightly different distances.
        val shots = listOf(Triple(0.35f, 7f, 1), Triple(null, 8f, 3), Triple(-0.3f, 7.5f, 2), Triple(0.1f, 6.5f, 3), Triple(null, 7f, 0), Triple(-0.4f, 8f, 0), Triple(0.5f, 7f, 2))
        val out = Pipelines.unrollBeam(
            shots.map { BeamScene.photo(it.first, it.second, it.third) },
            shots.map { BeamScene.reading(it.first, it.second) },
            shots.map { BeamScene.HORIZON / BeamScene.H },
            shots.map { BeamScene.FOCAL },
        )
        assertTrue(out.wraps)
        assertEquals(4, out.sides)
        val faceW = out.image.width / 4
        // Whichever face comes first, the rest follow it around.
        fun like(slot: Int, face: Int): Float {
            // Which row is the camera's height is not pinned down (the result is cropped), so
            // the whole face is tried at every height and the best one counts.
            var best = Float.MAX_VALUE
            for (shift in -80..80 step 2) {
                var off = 0f; var n = 0
                for (v in out.image.height / 4 until out.image.height * 3 / 4 step 9) for (u in 4 until faceW - 4 step 5) {
                    val got = (out.image[slot * faceW + u, v] shr 16) and 0xFF
                    val want = (BeamScene.paint(face, (u + 0.5f) / faceW, (v + shift - out.image.height / 2f) / (faceW / 2f)) shr 16) and 0xFF
                    off += abs(got - want); n++
                }
                best = min(best, off / n)
            }
            return best
        }
        val first = (0 until 4).minBy { like(0, it) }
        for (slot in 0 until 4) {
            val face = (first + slot) % 4
            assertTrue(like(slot, face) < 20f, "slot $slot is not face $face: ${like(slot, face)}")
            for (other in 1..3) assertTrue(like(slot, (face + other) % 4) > like(slot, face) * 2f, "slot $slot could as well be face ${(face + other) % 4}")
        }
    }
}

class PartialBeamTest {
    @Test
    fun aFaceSeenOnlyAtAGlanceIsNotClaimed() {
        // Faces 0, 1 and 2 are photographed square-on and at corners; face 3 only once, at 79° off its normal.
        val shots = listOf(Triple(null, 8f, 0), Triple(null, 7.5f, 1), Triple(null, 8f, 2), Triple(0.35f, 7f, 1), Triple(-0.3f, 7.5f, 2), Triple(0.6f, 7f, 3))
        val out = Pipelines.unrollBeam(
            shots.map { BeamScene.photo(it.first, it.second, it.third) },
            shots.map { BeamScene.reading(it.first, it.second) },
            shots.map { BeamScene.HORIZON / BeamScene.H },
            shots.map { BeamScene.FOCAL },
        )
        assertFalse(out.wraps)
        assertEquals(4, out.sides)
        // Three faces of four: 270° of the way around, and the strip's width says so.
        assertEquals(1.5f * PI.toFloat(), assertNotNull(out.arc), 0.02f)
    }

    @Test
    fun aCompleteBeamStillClosesIntoATurn() {
        // Corner views link neighbors: 0-1, 1-2 and 2-3.
        val shots = listOf(Triple(null, 8f, 0), Triple(null, 7.5f, 1), Triple(null, 8f, 2), Triple(null, 7f, 3), Triple(0.3f, 7f, 1), Triple(-0.3f, 7.5f, 2), Triple(0.25f, 7f, 3))
        val out = Pipelines.unrollBeam(
            shots.map { BeamScene.photo(it.first, it.second, it.third) },
            shots.map { BeamScene.reading(it.first, it.second) },
            shots.map { BeamScene.HORIZON / BeamScene.H },
            shots.map { BeamScene.FOCAL },
        )
        assertTrue(out.wraps)
        assertEquals(2f * PI.toFloat(), assertNotNull(out.arc), 0.02f)
    }
}

class TaperTest {
    /** A depth image of a pillar whose width and nearness both change linearly down the frame. */
    private fun depth(widthChange: Float, nearnessChange: Float): RgbaImage {
        val w = 150; val h = 200
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF000000.toInt() })
        for (y in 0 until h) {
            val t = (y + 0.5f) / h - 0.5f
            val half = 22f * (1f + widthChange * t)
            val near = (200f * (1f + nearnessChange * t)).toInt().coerceIn(0, 255)
            for (x in (w / 2f - half).toInt()..(w / 2f + half).toInt()) img[x, y] = (0xFF shl 24) or (near shl 16) or (near shl 8) or near
        }
        return img
    }

    @Test
    fun aTippedPhoneIsNotATaper() {
        // Narrower toward the top and receding by the same share: tilt explains all of it.
        val t = assertNotNull(DepthSides.taper(depth(widthChange = 0.2f, nearnessChange = 0.2f)))
        assertEquals(0.2f, t.widthChange, 0.03f)
        assertEquals(0f, t.unexplained, 0.04f)
    }

    @Test
    fun narrowingWithoutRecedingIsATaper() {
        val t = assertNotNull(DepthSides.taper(depth(widthChange = 0.4f, nearnessChange = 0f)))
        assertEquals(0.4f, t.unexplained, 0.05f)
    }

    @Test
    fun aTaperNeedsTheSetBehindIt() {
        fun t(width: Float, unexplained: Float) = DepthSides.Taper(width, unexplained)
        // Every photo sees it; each is tipped a little differently. The common width change is the taper.
        assertEquals(0.4f, DepthSides.taperConsensus(listOf(t(0.45f, 0.5f), t(0.4f, 0.38f), t(0.33f, 0.42f), t(0.41f, 0.6f), t(0.38f, 0.33f))), 0.011f)
        // Noise around nothing.
        assertEquals(0f, DepthSides.taperConsensus(listOf(t(0.1f, 0.08f), t(-0.05f, -0.1f), t(0.02f, 0.12f), t(0f, -0.04f))))
        // One photo says so, the rest do not.
        assertEquals(0f, DepthSides.taperConsensus(listOf(t(0.5f, 0.5f), t(0f, 0.02f), t(0.03f, -0.02f), t(0f, 0f))))
        assertEquals(null, DepthSides.taperProfile(0f))
        val profile = assertNotNull(DepthSides.taperProfile(0.4f))
        assertEquals(0.8f, profile.first(), 1e-4f)
        assertEquals(1.2f, profile.last(), 1e-4f)
    }

    @Test
    fun theLevelerLeavesAPillarsOwnTaperIn() {
        // Sides that converge by 30% top to bottom, all of it the pillar's own.
        val img = depth(widthChange = 0.3f, nearnessChange = 0f)
        assertNotEquals(PhotoAdjust.FULL_FRAME, Leveler.level(img, null, img).adjust.quad)
        assertEquals(PhotoAdjust.FULL_FRAME, Leveler.level(img, null, img, taper = 0.3f).adjust.quad)
    }
}
