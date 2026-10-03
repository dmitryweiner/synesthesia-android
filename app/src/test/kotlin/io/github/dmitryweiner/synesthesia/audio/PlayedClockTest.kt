package io.github.dmitryweiner.synesthesia.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayedClockTest {
    private val sr = 48_000

    @Test
    fun itStartsAtThePlayersTimeAndCountsPlayedFrames() {
        val c = PlayedClock(sr, startTime = 10.0)
        assertEquals(10.0, c.seconds(), 0.0)
        assertEquals(10.5, c.update(24_000), 1e-12)
        assertEquals(11.0, c.update(48_000), 1e-12)
    }

    @Test
    fun itCountsOnPastTheSignBitAndTheWrap() {
        val c = PlayedClock(sr, startTime = 0.0)
        c.update(Int.MAX_VALUE)
        c.update(Int.MIN_VALUE) // 2^31: the int turned negative
        assertEquals(2_147_483_648.0 / sr, c.seconds(), 1e-9)
        c.update(-1) // 2^32 - 1
        c.update(1000) // wrapped
        assertEquals((4_294_967_296.0 + 1000) / sr, c.seconds(), 1e-9)
    }

    @Test
    fun aPositionThatFallsBackIsIgnored() {
        val c = PlayedClock(sr, startTime = 2.0)
        c.update(96_000)
        c.update(0) // a stopped track reset its position
        assertEquals(4.0, c.seconds(), 1e-12)
        c.update(96_000 + 4_800)
        assertEquals(4.1, c.seconds(), 1e-12)
    }
}
