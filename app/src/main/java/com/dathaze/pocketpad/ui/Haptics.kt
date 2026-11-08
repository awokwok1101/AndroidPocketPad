package com.dathaze.pocketpad.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Button-press haptics with three user-selectable intensities.
 * "Strong" uses a crisp click on every press so the on-screen pad
 * feels as physical as possible.
 */
class Haptics(context: Context) {

    enum class Level { OFF, LIGHT, STRONG }

    /**
     * Written from the UI thread (the menu) and read on the buzzing thread: a
     * level changed in settings applies to the next buzz instead of racing the
     * one in flight.
     */
    @Volatile
    var level: Level = Level.STRONG

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** The thread every buzz runs on; started on first use, see [buzz]. */
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var waiting: Runnable? = null

    /** Fired on every button/stick press. */
    fun press() {
        when (level) {
            Level.OFF -> return
            Level.LIGHT -> buzz { vibrate(VibrationEffect.EFFECT_TICK, 10, 120) }
            Level.STRONG -> buzz { vibrate(VibrationEffect.EFFECT_CLICK, 25, 255) }
        }
    }

    /** Fired when the d-pad direction changes while held. */
    fun tick() {
        when (level) {
            Level.OFF -> return
            Level.LIGHT -> buzz { oneShot(8, 90) }
            Level.STRONG -> buzz { vibrate(VibrationEffect.EFFECT_TICK, 12, 180) }
        }
    }

    /** Stop the buzzing thread; the view is going away. */
    fun shutdown() {
        waiting = null
        handler = null
        worker?.quitSafely()
        worker = null
    }

    /**
     * Run [effect] on the buzzing thread instead of the caller's.
     *
     * `Vibrator.vibrate` is a binder call into the vibrator service, and it used
     * to be made from [ControllerView.onTouchEvent] — after the report had been
     * handed over, but still inside the event that the next touch has to wait
     * behind. On a device where that call takes a few milliseconds, tapping
     * quickly makes the pad feel like it drops presses: the report goes out, and
     * then the phone spends the next instant talking to the vibrator rather than
     * reading the finger. Off the touch thread the two stop competing.
     *
     * A buzz that is still waiting is replaced rather than queued. Haptics
     * confirm what just happened, so a trail of late ticks arriving behind the
     * presses they were confirming reads worse than one buzz fewer.
     */
    @Synchronized
    private fun buzz(effect: () -> Unit) {
        if (vibrator == null) return
        val h = handler ?: newHandler() ?: return
        waiting?.let { h.removeCallbacks(it) }
        val runnable = Runnable { runCatching { effect() } }
        waiting = runnable
        h.post(runnable)
    }

    private fun newHandler(): Handler? {
        val thread = try {
            HandlerThread("pocketpad-haptics").apply { start() }
        } catch (_: Throwable) {
            return null
        }
        worker = thread
        return Handler(thread.looper).also { handler = it }
    }

    private fun vibrate(effectId: Int, fallbackMs: Long, fallbackAmp: Int) {
        val v = vibrator ?: return
        v.vibrate(predefinedOr(effectId, fallbackMs, fallbackAmp))
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }

    private fun predefinedOr(effectId: Int, fallbackMs: Long, fallbackAmp: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(effectId)
        } else {
            VibrationEffect.createOneShot(fallbackMs, fallbackAmp)
        }
}
