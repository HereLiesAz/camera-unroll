package com.hereliesaz.photounroll.capture

import com.hereliesaz.photounroll.capture.CaptureGuide.Fault
import com.hereliesaz.photounroll.capture.CaptureGuide.Reading
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureGuideTest {
    private fun reading(center: Float = 0.5f, width: Float = 0.36f, lean: Float = 0f, taper: Float = 0f) =
        Reading(center - width / 2f, center + width / 2f, lean, taper)

    @Test
    fun aGoodFirstFrameIsTaken() = assertEquals(null, CaptureGuide.assess(reading(), reference = null, stepDeg = null))

    @Test
    fun noPillarComesFirst() = assertEquals(Fault.NoPillar, CaptureGuide.assess(null, 0.36f, 30f))

    @Test
    fun framingIsCorrectedBeforeAnythingElse() {
        assertEquals(Fault.AimLeft, CaptureGuide.assess(reading(center = 0.3f, lean = 20f), null, null))
        assertEquals(Fault.AimRight, CaptureGuide.assess(reading(center = 0.7f), null, null))
    }

    @Test
    fun laterPhotosMustMatchTheFirstOnesSize() {
        assertEquals(Fault.StepBack, CaptureGuide.assess(reading(width = 0.47f), reference = 0.36f, stepDeg = 30f))
        assertEquals(Fault.StepCloser, CaptureGuide.assess(reading(width = 0.27f), reference = 0.36f, stepDeg = 30f))
        assertEquals(null, CaptureGuide.assess(reading(width = 0.40f), reference = 0.36f, stepDeg = 30f))
    }

    @Test
    fun tiltIsNamedByTheWayToFixIt() {
        assertEquals(Fault.Straighten, CaptureGuide.assess(reading(lean = 9f), null, null))
        assertEquals(Fault.TiltDown, CaptureGuide.assess(reading(taper = 0.3f), null, null))
        assertEquals(Fault.TiltUp, CaptureGuide.assess(reading(taper = -0.3f), null, null))
    }

    @Test
    fun theWalkBetweenPhotosIsBounded() {
        assertEquals(Fault.KeepWalking, CaptureGuide.assess(reading(), 0.36f, stepDeg = 5f))
        assertEquals(Fault.TooFar, CaptureGuide.assess(reading(), 0.36f, stepDeg = -70f))
        assertEquals(Fault.Lost, CaptureGuide.assess(reading(), 0.36f, stepDeg = Float.NaN))
        assertEquals(null, CaptureGuide.assess(reading(), 0.36f, stepDeg = -30f))
    }
}
