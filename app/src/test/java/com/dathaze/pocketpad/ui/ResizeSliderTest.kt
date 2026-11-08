package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeSliderTest {

    private val min = 0.5f
    private val max = 2.0f

    @Test
    fun theTrackEndsMapToTheSmallestAndLargestSize() {
        assertEquals(min, ResizeSlider.scaleAt(0f, min, max), 0.0001f)
        assertEquals(max, ResizeSlider.scaleAt(1f, min, max), 0.0001f)
    }

    @Test
    fun theMiddleOfTheTrackIsTheMiddleOfTheRange() {
        assertEquals(1.25f, ResizeSlider.scaleAt(0.5f, min, max), 0.0001f)
        assertEquals(0.5f, ResizeSlider.fractionFor(1.25f, min, max), 0.0001f)
    }

    @Test
    fun draggingPastEitherEndStaysAtTheLimit() {
        assertEquals(min, ResizeSlider.scaleAt(-0.8f, min, max), 0.0001f)
        assertEquals(max, ResizeSlider.scaleAt(3f, min, max), 0.0001f)
        assertEquals(0f, ResizeSlider.fractionFor(0.1f, min, max), 0.0001f)
        assertEquals(1f, ResizeSlider.fractionFor(9f, min, max), 0.0001f)
    }

    @Test
    fun aPointOnTheTrackConvertsToTheSamePointOnTheSlider() {
        val left = 120f
        val right = 520f
        assertEquals(0f, ResizeSlider.fractionAt(left, left, right), 0.0001f)
        assertEquals(1f, ResizeSlider.fractionAt(right, left, right), 0.0001f)
        assertEquals(0.5f, ResizeSlider.fractionAt(320f, left, right), 0.0001f)
        assertEquals(320f, ResizeSlider.xAt(0.5f, left, right), 0.0001f)
    }

    @Test
    fun aFingerOutsideTheTrackIsClampedToItsEnds() {
        assertEquals(0f, ResizeSlider.fractionAt(0f, 120f, 520f), 0.0001f)
        assertEquals(1f, ResizeSlider.fractionAt(900f, 120f, 520f), 0.0001f)
    }

    @Test
    fun aTrackWithNoWidthDoesNotDivideByZero() {
        assertEquals(0f, ResizeSlider.fractionAt(200f, 300f, 300f), 0.0001f)
        assertEquals(0f, ResizeSlider.fractionFor(1f, 1f, 1f), 0.0001f)
    }

    @Test
    fun aFingerThatStaysPutIsATap() {
        assertTrue(ResizeSlider.isTap(0f, 0f, 24f))
        assertTrue(ResizeSlider.isTap(24f, 0f, 24f))
        assertFalse(ResizeSlider.isTap(25f, 0f, 24f))
    }

    @Test
    fun aDiagonalDragCountsAsMovementEvenWhenEachAxisIsSmall() {
        // 20px + 20px in each direction is 28px of travel — past a 24px slop.
        assertFalse(ResizeSlider.isTap(20f, 20f, 24f))
    }
}
