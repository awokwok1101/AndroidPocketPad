package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two things this has to get right are the ones a quick manual test hides:
 * full throw must survive any deadzone, and the deadzone must not make a
 * diagonal push uneven. Both are arithmetic, so they are pinned here.
 */
class StickResponseTest {

    private val tolerance = 1e-5f

    @Test
    fun `centred stick sends centre`() {
        assertEquals(0f, StickResponse.apply(0f, 0.2f, 1.6f), tolerance)
    }

    @Test
    fun `movement inside the deadzone sends nothing`() {
        assertEquals(0f, StickResponse.apply(0.19f, 0.2f, 1f), tolerance)
    }

    @Test
    fun `the deadzone edge itself is still silence`() {
        assertEquals(0f, StickResponse.apply(0.2f, 0.2f, 1f), tolerance)
    }

    @Test
    fun `outside the deadzone the remaining travel is rescaled`() {
        // 0.6 with a 0.2 deadzone is halfway through the live range: (0.6-0.2)/0.8.
        assertEquals(0.5f, StickResponse.apply(0.6f, 0.2f, 1f), tolerance)
    }

    @Test
    fun `full throw still reaches full speed with any deadzone`() {
        for (zone in listOf(0f, 0.1f, 0.25f, 0.4f)) {
            assertEquals(1f, StickResponse.apply(1f, zone, 1f), tolerance)
        }
    }

    @Test
    fun `deadzone does not change the direction of the push`() {
        assertEquals(
            -StickResponse.apply(0.9f, 0.3f, 1f),
            StickResponse.apply(-0.9f, 0.3f, 1f),
            tolerance
        )
    }

    @Test
    fun `curve of one is linear`() {
        assertEquals(0.4f, StickResponse.apply(0.4f, 0f, 1f), tolerance)
    }

    @Test
    fun `a steeper curve holds back the middle`() {
        assertEquals(0.25f, StickResponse.apply(0.5f, 0f, 2f), tolerance)
    }

    @Test
    fun `the curve still reaches full throw at the edge`() {
        assertEquals(1f, StickResponse.apply(1f, 0f, 2f), tolerance)
        assertEquals(-1f, StickResponse.apply(-1f, 0.3f, 2f), tolerance)
    }

    @Test
    fun `curve is applied after the deadzone, not before`() {
        // 0.6 through a 0.2 deadzone is 0.5 live, then squared: 0.25. Applying
        // the curve first would give 0.36, then 0.2 — a different stick.
        assertEquals(0.25f, StickResponse.apply(0.6f, 0.2f, 2f), tolerance)
    }

    @Test
    fun `out of range settings are clamped, not obeyed`() {
        // A deadzone above the maximum behaves as 0.40: (0.5-0.4)/0.6.
        assertEquals(1f / 6f, StickResponse.apply(0.5f, 5f, 1f), tolerance)
        // A curve above the maximum behaves as 2.0: 0.5 squared.
        assertEquals(0.25f, StickResponse.apply(0.5f, 0f, 8f), tolerance)
        // A negative deadzone behaves as none, and a curve under 1 as linear.
        assertEquals(0.5f, StickResponse.apply(0.5f, -2f, 0.1f), tolerance)
    }

    @Test
    fun `a broken value cannot produce a broken report`() {
        assertEquals(0f, StickResponse.apply(Float.NaN, 0.2f, 1.5f), tolerance)
        assertEquals(0f, StickResponse.apply(Float.POSITIVE_INFINITY, 0.2f, 1.5f), tolerance)
        assertEquals(1f, StickResponse.apply(4f, 0f, 1f), tolerance)
    }
}
