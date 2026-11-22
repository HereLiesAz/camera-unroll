package com.hereliesaz.cameraunroll.capture

import com.hereliesaz.cameraunroll.imaging.CylinderGuides
import com.hereliesaz.cameraunroll.imaging.CylinderUnroller
import com.hereliesaz.cameraunroll.imaging.EdgeSuggester
import com.hereliesaz.cameraunroll.imaging.Leveler
import com.hereliesaz.cameraunroll.imaging.RgbaImage
import com.hereliesaz.cameraunroll.imaging.Stitcher
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

/**
 * The judgment behind guided capture, free of any camera or screen: given what the camera
 * sees now and what has been shot so far, is this the moment for the next photo, and if not,
 * what one thing should the photographer change?
 *
 * A good set of photos has the pillar centered, upright, the same size in every frame (the
 * same distance), and each photo a fixed walk around from the last. Each frame is measured
 * for exactly those, in that order of urgency. The tolerances are loose on purpose: the
 * unroll corrects lean, tilt and distance by itself, so the guide only has to keep the
 * photographer out of what it cannot correct (a pillar half out of frame, no overlap).
 */
object CaptureGuide {
    /** Where the pillar is in a frame; positions are fractions of the frame's width. */
    class Reading(val left: Float, val right: Float, val leanDeg: Float, val taper: Float) {
        val center get() = (left + right) / 2f
        val width get() = right - left
    }

    /** The one thing to fix; shown to the photographer as [hint]. */
    enum class Fault(val hint: String) {
        NoPillar("aim at the pillar"),
        AimLeft("aim left"),
        AimRight("aim right"),
        StepBack("step back"),
        StepCloser("step closer"),
        Straighten("hold the phone upright"),
        TiltDown("tip the phone down"),
        TiltUp("tip the phone up"),
        KeepWalking("keep walking around"),
        TooFar("too far: go back a little"),
        Lost("go back toward the last shot"),
    }

    /** Finds the pillar in a (small) live frame; null when there is none to be seen. */
    fun read(frame: RgbaImage): Reading? {
        val img = frame.scaledToMax(EdgeSuggester.WORK_SIZE)
        val s = Leveler.measure(img) ?: return null
        val lean = (atan(s.lean) * 180.0 / PI).toFloat()
        return Reading(s.leftAt / img.width, s.rightAt / img.width, lean, s.spread * img.height / s.width)
    }

    /**
     * @param reference the pillar's width in the first photo taken, which every later photo
     *   should match (same width = same distance); null before the first photo
     * @param stepDeg how far around the pillar the camera has come since the last photo;
     *   null before the first photo, NaN when the view no longer overlaps that photo
     * @return what to fix, or null when everything is right and the photo should be taken
     */
    fun assess(reading: Reading?, reference: Float?, stepDeg: Float?): Fault? {
        reading ?: return Fault.NoPillar
        if (reading.center < 0.5f - OFF_CENTER) return Fault.AimLeft
        if (reading.center > 0.5f + OFF_CENTER) return Fault.AimRight
        val (narrowest, widest) = reference?.let { it / SIZE_SLACK to it * SIZE_SLACK } ?: (FIRST_NARROWEST to FIRST_WIDEST)
        if (reading.width > widest) return Fault.StepBack
        if (reading.width < narrowest) return Fault.StepCloser
        if (abs(reading.leanDeg) > MAX_LEAN_DEG) return Fault.Straighten
        // Wider toward the bottom means it narrows upward: the phone is tipped up.
        if (reading.taper > MAX_TAPER) return Fault.TiltDown
        if (reading.taper < -MAX_TAPER) return Fault.TiltUp
        if (stepDeg != null) {
            if (stepDeg.isNaN()) return Fault.Lost
            if (abs(stepDeg) < MIN_STEP_DEG) return Fault.KeepWalking
            if (abs(stepDeg) > MAX_STEP_DEG) return Fault.TooFar
        }
        return null
    }

    /**
     * The pillar's face in [frame], flattened quickly and small: what is compared between the
     * last photo and the live view to tell how far around the camera has come.
     */
    fun face(frame: RgbaImage, reading: Reading): RgbaImage =
        CylinderUnroller.unroll(frame, CylinderGuides(reading.left, reading.right, 0.12f, 0f, 0.88f, 0f), FACE_ARC_DEG).image

    /**
     * Degrees around the pillar from the view [last] to the view [now] (signed: positive when
     * the surface has slid left, i.e. the camera moved right), or NaN if they share nothing.
     */
    fun step(last: RgbaImage, now: RgbaImage): Float {
        val h = minOf(last.height, now.height)
        val a = if (last.height == h) last else last.resized(maxOf(2, last.width * h / last.height), h)
        val b = if (now.height == h) now else now.resized(maxOf(2, now.width * h / now.height), h)
        val link = Stitcher.align(a, b)
        if (link.score < Stitcher.MATCHES) return Float.NaN
        // The face spans 2·FACE_ARC_DEG across its width.
        return link.dx / a.width * 2f * FACE_ARC_DEG
    }

    /** Degrees of pillar one photo covers by itself, for the progress ring. */
    const val PHOTO_ARC_DEG = 120f

    /** The walk the guide asks for between photos; the ring's step cue fills toward this. */
    const val TARGET_STEP_DEG = 30f

    /** The pillar's width the ghost shows before any photo fixes it. */
    const val FIRST_WIDTH = 0.36f

    private const val FACE_ARC_DEG = 60f
    private const val OFF_CENTER = 0.09f
    private const val FIRST_NARROWEST = 0.26f
    private const val FIRST_WIDEST = 0.48f
    private const val SIZE_SLACK = 1.22f
    private const val MAX_LEAN_DEG = 6f
    private const val MAX_TAPER = 0.16f
    private const val MIN_STEP_DEG = 15f
    private const val MAX_STEP_DEG = 50f
}
