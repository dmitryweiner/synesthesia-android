package io.github.dmitryweiner.synesthesia.audio

/**
 * What the device has *played*, on the player's clock: the player's time when
 * the device started, plus the frames the device has played since.
 *
 * `AudioTrack.getPlaybackHeadPosition()` is an unsigned 32-bit frame count in
 * a Java `int`: it turns negative after ~12 h at 48 kHz and wraps after ~25 h.
 * This keeps a 64-bit total across both. A position that moves *back*
 * without wrapping (a track resets it to 0 once a stop has drained) is
 * ignored, so the clock never jumps.
 */
class PlayedClock(private val sampleRate: Int, private val startTime: Double) {
    private var lastRaw = 0L
    private var total = 0L

    /** Feeds a fresh head position; returns the played time in seconds. */
    @Synchronized
    fun update(headPosition: Int): Double {
        val raw = headPosition.toLong() and 0xFFFF_FFFFL
        if (raw < lastRaw && lastRaw < WRAP_ZONE) return seconds()
        total += (raw - lastRaw) and 0xFFFF_FFFFL
        lastRaw = raw
        return seconds()
    }

    private companion object {
        /** Only a position this far up can wrap to a small one. */
        const val WRAP_ZONE = 0x8000_0000L
    }

    /** The played time as of the last update. */
    @Synchronized
    fun seconds(): Double = startTime + total.toDouble() / sampleRate
}
