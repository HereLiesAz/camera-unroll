package com.hereliesaz.cameraunroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Four-sided beams: a square column seen from outside, flattened face by face.
 *
 * A round pillar has no corners and is unrolled by angle. A beam is four planes, and a plane
 * can be flattened exactly once the camera's place is known. Seen from above, the beam is a
 * square and the camera sees either one face (two vertical edges) or two faces meeting at the
 * corner nearest it (three edges). Where those edges fall in the frame fixes where the camera
 * stands relative to the beam: its distance, its sideways offset, and how far the beam is
 * turned. That is [pose]. From the pose every point of each visible face has a known place in
 * the photo, which is all [unroll] needs.
 *
 * Everything is measured in half-widths of the beam (so a face is 2 wide), with the camera
 * at the origin looking along +z, x to the right, and heights taken from the horizon.
 */
object Prism {
    /**
     * @property phi how far the nearest corner is turned from pointing straight at the camera
     *   (radians, positive = to the right); null when only one face is seen, square on
     * @property d distance from the camera to the beam's axis, along the view
     * @property e the axis's offset to the right of the view line
     */
    class Pose(val phi: Float?, val d: Float, val e: Float) {
        /** Depth of the surface nearest the camera, which sets the photo's scale on the beam. */
        val nearest: Float get() = if (phi == null) d - 1f else d - ROOT2 * cos(phi)

        /** The visible faces, left to right, each from its left corner to its right corner (x, z). */
        internal fun faces(): List<FloatArray> {
            if (phi == null) return listOf(floatArrayOf(e - 1f, d - 1f, e + 1f, d - 1f))
            val c = ROOT2 * cos(phi)
            val s = ROOT2 * sin(phi)
            // Left silhouette corner, nearest corner, right silhouette corner.
            val ax = e - c; val az = d - s
            val mx = e + s; val mz = d - c
            val bx = e + c; val bz = d + s
            return listOf(floatArrayOf(ax, az, mx, mz), floatArrayOf(mx, mz, bx, bz))
        }
    }

    /**
     * Where the camera stands, from where the beam's vertical edges fall in a leveled photo.
     *
     * @param left,right the silhouette's edges, in pixels right of the frame's center column
     * @param corner the edge between two visible faces, or null if only one face is seen
     * @return null when no square beam could be standing there (too close, or edges out of order)
     */
    fun pose(left: Float, corner: Float?, right: Float, focalPx: Float): Pose? {
        if (right - left < 2f) return null
        if (corner == null || corner <= left + 1f || corner >= right - 1f) {
            // One face, square on: its corners are at (e ∓ 1, d − 1).
            val depth = 2f * focalPx / (right - left)
            val e = left * depth / focalPx + 1f
            return Pose(null, depth + 1f, e).takeIf { it.d > MIN_DISTANCE }
        }
        // Each corner (rx, rz) from the axis must project to its edge: f·(e + rx) = x·(d + rz),
        // which is linear in e and d once the turn phi is fixed. Three corners, two unknowns:
        // the turn that leaves the least disagreement is the answer.
        val xs = floatArrayOf(left, corner, right)
        fun solve(phi: Float): FloatArray {
            val c = ROOT2 * cos(phi)
            val s = ROOT2 * sin(phi)
            val rx = floatArrayOf(-c, s, c)
            val rz = floatArrayOf(-s, -c, s)
            var aa = 0f; var ab = 0f; var bb = 0f; var ay = 0f; var by = 0f
            for (k in 0 until 3) {
                val a = focalPx; val b = -xs[k]; val y = xs[k] * rz[k] - focalPx * rx[k]
                aa += a * a; ab += a * b; bb += b * b; ay += a * y; by += b * y
            }
            val det = aa * bb - ab * ab
            if (abs(det) < 1e-6f) return floatArrayOf(0f, 0f, Float.MAX_VALUE)
            val e = (ay * bb - by * ab) / det
            val d = (aa * by - ab * ay) / det
            var err = 0f
            for (k in 0 until 3) { val r = focalPx * (e + rx[k]) - xs[k] * (d + rz[k]); err += r * r }
            return floatArrayOf(e, d, err)
        }
        var best = Float.MAX_VALUE
        var bestPhi = 0f
        val limit = (PI / 4).toFloat() - 0.01f
        var steps = 0
        while (steps <= 2 * SEARCH) {
            val phi = -limit + limit * steps / SEARCH
            val r = solve(phi)
            if (r[1] > MIN_DISTANCE && r[2] < best) { best = r[2]; bestPhi = phi }
            steps++
        }
        if (best == Float.MAX_VALUE) return null
        val r = solve(bestPhi)
        return Pose(bestPhi, r[1], r[0])
    }

    /**
     * One face of the beam as one photo shows it, flattened.
     *
     * @property squarely how squarely the camera sees it: 1 looking straight at it, 0 edge-on
     * @property depth distance from the camera to the face's middle, in half-widths
     */
    class Face(val image: RgbaImage, val squarely: Float, val depth: Float)

    /**
     * Flattens each face of the beam [photo] shows, left to right, at [pxPerHalf] pixels per
     * half-width in both directions. Rows run from [up] half-widths above the horizon to
     * [down] below; what the photo does not reach is left transparent, so photos taken
     * nearer or farther still produce faces of one size.
     *
     * @param horizon the horizon's row in [photo], in pixels
     * @param stretch,shift a correction to this photo's heights, found by comparing it with
     *   the others ([Beam]): the height it shows at a row is that row's own height, moved
     *   back by [shift] half-widths and shrunk by [stretch] about the middle row
     */
    fun faces(
        photo: RgbaImage, pose: Pose, horizon: Float, focalPx: Float, pxPerHalf: Float, up: Float, down: Float,
        stretch: Float = 1f, shift: Float = 0f,
    ): List<Face> {
        val faceW = max(2, (2f * pxPerHalf).roundToInt())
        val outH = max(2, ((up + down) * pxPerHalf).roundToInt())
        val centerX = photo.width / 2f
        val middle = (down - up) / 2f
        return pose.faces().map { face ->
            val x0 = face[0]; val z0 = face[1]; val x1 = face[2]; val z1 = face[3]
            val nx = z1 - z0; val nz = -(x1 - x0) // the face's normal, pointing at the camera's side
            val mx = (x0 + x1) / 2f; val mz = (z0 + z1) / 2f
            val far = sqrt(mx * mx + mz * mz)
            val squarely = max(0f, -(nx * mx + nz * mz) / (sqrt(nx * nx + nz * nz) * far))
            val out = RgbaImage(faceW, outH)
            for (u in 0 until faceW) {
                val s = (u + 0.5f) / faceW
                val x = x0 + s * (x1 - x0)
                val z = z0 + s * (z1 - z0)
                if (z <= 0.05f) continue
                val sx = centerX + focalPx * x / z - 0.5f
                if (sx < 0f || sx > photo.width - 1f) continue
                for (v in 0 until outH) {
                    val y = (-up + (v + 0.5f) / pxPerHalf - middle - shift) / stretch + middle
                    val sy = horizon + focalPx * y / z - 0.5f
                    if (sy < 0f || sy > photo.height - 1f) continue
                    out[u, v] = photo.sampleCubic(sx, sy) or (0xFF shl 24)
                }
            }
            Face(out, squarely, far)
        }
    }

    /**
     * Sharpens a [Reading] against the photo itself ([EdgeSuggester.sharpen]).
     */
    fun refine(photo: RgbaImage, reading: Reading): Reading {
        val (left, right) = EdgeSuggester.sharpen(photo, listOf(reading.left, reading.right), listOf(0f, 0f), listOf(SIDE_REACH, SIDE_REACH))
        val corner = reading.corner?.let { EdgeSuggester.sharpen(photo, listOf(it), listOf(0f), listOf(CORNER_REACH))[0] }?.takeIf { it > left && it < right }
        return Reading(left, corner, right)
    }

    /** What one leveled photo shows of a beam: its edges at mid-height, as fractions of the width. */
    class Reading(val left: Float, val corner: Float?, val right: Float) {
        /** Where the nearest point of the cross-section lies between the sides (0..1), null if flat. */
        val ridge: Float? get() = corner?.let { (it - left) / (right - left) }
    }

    /**
     * Reads a beam's edges from a depth image ([DepthModel.estimate]) of a leveled photo.
     * The sides are where the depth falls away ([DepthSides]). Between them, a face seen
     * square on is flat; two faces rise to a ridge at the corner between them, and the ridge
     * is the corner's column.
     */
    fun read(depth: RgbaImage): Reading? {
        val sides = DepthSides.measure(depth) ?: return null
        val w = depth.width
        val h = depth.height
        val l = sides.leftAt
        val r = sides.rightAt
        val span = r - l
        if (span < w / 12f) return null
        // The cross-section through the middle rows, with the edge pixels (mixed with the
        // background) left out.
        val x0 = (l + EDGE_SKIP * span).roundToInt().coerceIn(0, w - 1)
        val x1 = (r - EDGE_SKIP * span).roundToInt().coerceIn(x0 + 1, w)
        val profile = FloatArray(x1 - x0)
        val rows = (h * 0.3f).toInt() until (h * 0.7f).toInt()
        for (x in x0 until x1) {
            var sum = 0
            for (y in rows) sum += depth.pixels[y * w + (x + (sides.lean * (y - h / 2f))).roundToInt().coerceIn(0, w - 1)] and 0xFF
            profile[x - x0] = sum.toFloat() / rows.count()
        }
        var peak = 0
        for (i in profile.indices) if (profile[i] > profile[peak]) peak = i
        // A ridge stands above both ends of the cross-section; a flat face does not.
        val rise = profile[peak] - max(profile.first(), profile.last())
        val at = (x0 + peak + 0.5f - l) / span
        val corner = if (rise >= RIDGE && at > EDGE_SKIP * 2f && at < 1f - EDGE_SKIP * 2f) x0 + peak + 0.5f else null
        return Reading(l / w, corner?.let { it / w }, r / w)
    }

    /**
     * Whether a set of photos shows a beam, not a round pillar. Walking around a round
     * pillar, the nearest point of its cross-section stays in the middle. Around a beam it
     * does not: a corner sweeps across from one side to the other, and between corners a face
     * shows flat. Either of those, in enough of the photos, is a beam.
     */
    fun isBeam(readings: List<Reading?>): Boolean {
        val seen = readings.filterNotNull()
        if (seen.size < 3) return false
        val flat = seen.count { it.ridge == null }
        val ridges = seen.mapNotNull { it.ridge }
        val offCenter = ridges.count { abs(it - 0.5f) > OFF_CENTER }
        return (flat + offCenter) * 2 >= seen.size
    }

    private const val ROOT2 = 1.4142135f
    private const val MIN_DISTANCE = 1.8f
    private const val SEARCH = 180
    private const val EDGE_SKIP = 0.08f
    /** Depth levels (of 255) a ridge must stand above the cross-section's ends. */
    private const val RIDGE = 6f
    private const val OFF_CENTER = 0.14f
    private const val SIDE_REACH = 0.015f
    private const val CORNER_REACH = 0.035f
}
