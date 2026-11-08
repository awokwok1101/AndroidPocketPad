package com.dathaze.pocketpad.ui

import kotlin.math.abs
import kotlin.math.pow

/**
 * Maps a stick's raw deflection to what is sent to the host: a deadzone around
 * the centre, then an optional response curve.
 *
 * Deliberately free of `android.*` — this is the arithmetic that decides what a
 * game actually feels, and it is easy to get subtly wrong (a stick that cannot
 * reach the edge, or one that jumps the moment it leaves the centre), so it is
 * unit-tested directly rather than inferred from a pad in someone's hand.
 *
 * Two properties are load-bearing:
 *
 *  - **The deadzone is removed, not just clamped.** The remaining travel is
 *    rescaled to the full 0..1 range, so a large deadzone does not cost the
 *    stick its maximum. A centred-80% stick is read as "walk" by every game
 *    that maps full deflection to "run", and no deadzone setting may cause it.
 *
 *  - **The deadzone applies to the length of the deflection vector, not to each
 *    axis.** Per-axis deadzones carve a square hole out of a round stick:
 *    pushing at a shallow angle would move one axis while the other stayed
 *    still, and a straight diagonal would come out as a staircase.
 */
object StickResponse {

    /** Deadzone as a fraction of full travel. 40% is already unusable in practice. */
    const val DEADZONE_MIN = 0f
    const val DEADZONE_MAX = 0.40f

    /**
     * Exponent applied after the deadzone. 1 is linear; larger values give up
     * movement near the centre (finer aim) without changing the full-throw end.
     */
    const val CURVE_MIN = 1.0f
    const val CURVE_MAX = 2.0f

    /** Both off, which is what every build before this setting did. */
    const val DEADZONE_DEFAULT = 0f
    const val CURVE_DEFAULT = 1.0f

    /**
     * @param deflection how far the stick is pushed, as a fraction of full
     *   travel: 0 at the centre, 1 at the edge, negative on the opposite side.
     * @param deadzone radius around the centre that reports nothing, 0..[DEADZONE_MAX].
     * @param curve exponent, [CURVE_MIN]..[CURVE_MAX].
     * @return the shaped deflection, same sign as [deflection], -1..1.
     */
    fun apply(deflection: Float, deadzone: Float, curve: Float): Float {
        if (!deflection.isFinite() || deflection == 0f) return 0f
        val zone = deadzone.coerceIn(DEADZONE_MIN, DEADZONE_MAX)
        val exponent = curve.coerceIn(CURVE_MIN, CURVE_MAX)

        val magnitude = abs(deflection).coerceAtMost(1f)
        if (magnitude <= zone) return 0f

        // zone < 1 by construction, so this never divides by zero, and the
        // result reaches exactly 1 at the edge for any deadzone.
        val live = (magnitude - zone) / (1f - zone)
        val shaped = if (exponent == 1f) live else live.pow(exponent)
        return if (deflection < 0f) -shaped else shaped
    }
}
