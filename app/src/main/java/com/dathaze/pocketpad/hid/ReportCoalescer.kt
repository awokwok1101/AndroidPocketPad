package com.dathaze.pocketpad.hid

/**
 * Holds the gamepad state between the thread that produces it and the
 * Bluetooth thread that sends it.
 *
 * `BluetoothHidDevice.sendReport` is a synchronous binder call into the
 * Bluetooth stack, and it blocks when the radio's outgoing queue is full.
 * Calling it from the UI thread — which is where touch events arrive — meant a
 * slow host froze the pad along with the report, and queueing every report made
 * it worse: by the time the stack accepted them they described where the stick
 * *had been*, so the host lagged further behind the longer it stalled. Keeping
 * at most [MAX_PENDING] states means a backlog cannot build up behind the
 * radio, and the one that goes out last is always where the pad is now.
 *
 * Two slots rather than one, because a single slot can swallow a tap. Press and
 * release can arrive within one radio turnaround, and with one slot the release
 * overwrote the press before the stack ever saw it — the button simply did not
 * fire. With two, the press stays owed while the release waits behind it, so the
 * host sees both; anything older than that is genuinely stale and is dropped.
 *
 * A report is dropped as a duplicate only when it matches the state the host is
 * already heading for, not merely the last one the radio accepted. Sending
 * A, B, A must deliver all three: skip that last A and the host is left holding
 * B while the pad thinks it is at A.
 *
 * Not thread-safe by itself — the caller holds its own lock, as
 * [HidGamepadManager] does.
 */
class ReportCoalescer {

    private val pending = ArrayDeque<ByteArray>()

    /** Report the host is heading for: the newest one offered. */
    private var newest: ByteArray? = null

    /** Queue [report], unless the host is already heading for that exact state. */
    fun offer(report: ByteArray) {
        if (report.contentEquals(newest)) return
        newest = report
        makeRoom()
        pending.addLast(report)
    }

    /**
     * Room for one more, by dropping the state in the middle: the oldest is
     * still owed to the host and the one being added is where the pad actually
     * is, so only the one between them can go.
     */
    private fun makeRoom() {
        if (pending.size >= MAX_PENDING) pending.removeAt(pending.size - 1)
    }

    /** The report to send next, or null when there is nothing new. */
    fun take(): ByteArray? = pending.removeFirstOrNull()

    /**
     * The state the host is heading for: the newest one offered.
     *
     * A host that polls with Get_Report is asking what the pad is doing *now*,
     * and this is the honest answer -- the last one [take] handed out may still
     * be sitting in the radio's queue, describing where the stick was. Null
     * before anything has been offered.
     */
    fun newestOffered(): ByteArray? = newest

    /**
     * The stack refused [report], the one [take] handed out. It goes back to the
     * front: a refused press is exactly the press the player is waiting for.
     * [newest] follows it, so a state that was dropped to make room is queued
     * again if the pad returns to it.
     */
    fun failed(report: ByteArray) {
        newest = report
        makeRoom()
        pending.addFirst(report)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Drop everything: used when the link goes away. */
    fun reset() {
        pending.clear()
        newest = null
    }

    companion object {
        /**
         * How many states may wait for the radio. Enough to keep a press and the
         * release that follows it; small enough that the host is never reading
         * input from more than one radio turnaround ago.
         */
        const val MAX_PENDING = 2
    }
}
