package com.dathaze.pocketpad.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCoalescerTest {

    private fun report(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun anIdenticalReportIsNotSentTwice() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(1, 2, 3))
        assertArrayEquals(report(1, 2, 3), coalescer.take())
        coalescer.offer(report(1, 2, 3))
        assertNull(coalescer.take())
        assertFalse(coalescer.hasPending())
    }

    @Test
    fun aBurstKeepsWhatIsStillOwedAndWhatIsCurrent() {
        // Three states in one radio turnaround: the oldest has not been sent yet
        // and the newest is where the pad is now, so the middle one is the only
        // one that can be dropped.
        val coalescer = ReportCoalescer()
        coalescer.offer(report(1, 0))
        coalescer.offer(report(2, 0))
        coalescer.offer(report(3, 0))
        assertArrayEquals(report(1, 0), coalescer.take())
        assertArrayEquals(report(3, 0), coalescer.take())
        assertNull(coalescer.take())
    }

    /**
     * A tap can press and release inside one radio turnaround. With a single
     * slot the release replaced the press before the stack saw it, and the
     * button never fired — the slow-feeling presses in fast play.
     */
    @Test
    fun aTapSurvivesItsOwnRelease() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(1)) // press
        coalescer.offer(report(0)) // release, one millisecond later
        assertArrayEquals(report(1), coalescer.take())
        assertArrayEquals(report(0), coalescer.take())
    }

    @Test
    fun returningToAnEarlierStateStillSendsIt() {
        // The host is heading for A again, so B alone would leave it stuck.
        val coalescer = ReportCoalescer()
        coalescer.offer(report(9))
        assertTakes(coalescer, report(9))
        coalescer.offer(report(5))
        assertTakes(coalescer, report(5))
        coalescer.offer(report(9))
        assertTakes(coalescer, report(9))
    }

    @Test
    fun aRefusedReportIsRetriedBeforeAnythingNewer() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(7))
        assertArrayEquals(report(7), coalescer.take())
        coalescer.failed(report(7))
        assertTrue(coalescer.hasPending())
        assertArrayEquals(report(7), coalescer.take())
        assertNull(coalescer.take())
    }

    @Test
    fun aRefusedReportDoesNotDisplaceWhatWasAlreadyWaiting() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(1))
        assertArrayEquals(report(1), coalescer.take())
        coalescer.offer(report(2))
        coalescer.failed(report(1))
        assertArrayEquals(report(1), coalescer.take())
        assertArrayEquals(report(2), coalescer.take())
    }

    @Test
    fun theQueueCannotGrowWithoutBound() {
        val coalescer = ReportCoalescer()
        for (state in 0 until 50) coalescer.offer(report(state))
        var sent = 0
        var last: ByteArray? = null
        while (true) {
            last = coalescer.take() ?: break
            sent++
        }
        assertEquals(ReportCoalescer.MAX_PENDING, sent)
        assertArrayEquals(report(49), last)
    }

    @Test
    fun takingTheLastReportClearsTheQueue() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(4))
        assertTrue(coalescer.hasPending())
        coalescer.take()
        assertFalse(coalescer.hasPending())
    }

    @Test
    fun resetForgetsBothTheQueueAndTheLastState() {
        val coalescer = ReportCoalescer()
        coalescer.offer(report(8))
        coalescer.reset()
        assertFalse(coalescer.hasPending())
        assertNull(coalescer.take())
        // After a reconnect the same state is new information again.
        coalescer.offer(report(8))
        assertArrayEquals(report(8), coalescer.take())
    }

    private fun assertTakes(coalescer: ReportCoalescer, expected: ByteArray) {
        assertArrayEquals(expected, coalescer.take())
    }
}
