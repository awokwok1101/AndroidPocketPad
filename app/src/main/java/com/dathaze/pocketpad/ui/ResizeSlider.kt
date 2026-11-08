package com.dathaze.pocketpad.ui

/**
 * The horizontal slider that sets one control's size in layout-edit mode.
 *
 * Deliberately pure: the mapping between a finger's x and a size multiplier is
 * the part worth getting right, and it can be tested without a device. The
 * track runs from [min] at its left end to [max] at its right end.
 */
object ResizeSlider {

    /** How far along the track [x] sits, 0 at [trackLeft] and 1 at [trackRight]. */
    fun fractionAt(x: Float, trackLeft: Float, trackRight: Float): Float {
        val width = trackRight - trackLeft
        if (width <= 0f) return 0f
        return ((x - trackLeft) / width).coerceIn(0f, 1f)
    }

    /** Where the knob sits for [fraction] — the inverse of [fractionAt]. */
    fun xAt(fraction: Float, trackLeft: Float, trackRight: Float): Float =
        trackLeft + (trackRight - trackLeft) * fraction.coerceIn(0f, 1f)

    /** Size multiplier shown at [fraction]. */
    fun scaleAt(fraction: Float, min: Float, max: Float): Float =
        min + (max - min) * fraction.coerceIn(0f, 1f)

    /** Where the knob sits for [scale] — the inverse of [scaleAt]. */
    fun fractionFor(scale: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        return ((scale - min) / (max - min)).coerceIn(0f, 1f)
    }

    /**
     * True when a finger that moved by ([dx], [dy]) never left the tap area.
     * Touches that stay put open the size panel; anything further moves the
     * button instead, so the two gestures cannot fire each other.
     */
    fun isTap(dx: Float, dy: Float, slop: Float): Boolean =
        dx * dx + dy * dy <= slop * slop
}
