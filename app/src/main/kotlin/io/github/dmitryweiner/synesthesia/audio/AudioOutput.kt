package io.github.dmitryweiner.synesthesia.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.github.dmitryweiner.synesthesia.core.SoundPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How the output is doing, for the screen and the bench (PLAN.md decision 12). */
data class OutputStats(
    val sampleRate: Int,
    /** 2 when the player renders the point's space and beat (headphones). */
    val channels: Int,
    val bufferMs: Int,
    val chunkFrames: Int,
    /** Time spent in the core per second of audio, smoothed: 0.05 = 5% of one core. */
    val load: Double,
    /** The worst single chunk since the start, same unit. */
    val loadPeak: Double,
    /** `AudioTrack.getUnderrunCount()`: times the device ran dry. */
    val underruns: Int,
)

/**
 * One run of sound: an `AudioTrack` and the thread that fills it from the
 * core (PLAN.md decision 5 — the simplest thing first, measured).
 *
 * The thread pulls [CHUNK_FRAMES] at a time and blocks in `write` while the
 * track's buffer is full, which is what paces it. The buffer is deep
 * ([BUFFER_MS]): this app does not need low latency — a change morphs over
 * 2 s — and a deep buffer is what rides out a busy phone with the screen off.
 * The picture does not suffer for it: it asks for the frame at the *played*
 * time ([playedSeconds]), not the rendered one.
 *
 * The track has the player's channels: a stereo player (headphones,
 * PLAN-BINAURAL.md) fills a stereo track with its two ears, interleaved; a
 * mono one a mono track.
 *
 * Start fades in; [stop] fades out, lets the buffer play out and releases
 * the track on the audio thread itself, so neither end clicks and the
 * caller never waits.
 */
class AudioOutput(
    private val player: SoundPlayer,
    val sampleRate: Int,
    private val onError: (Throwable) -> Unit,
) {
    companion object {
        const val CHUNK_FRAMES = 1024
        const val BUFFER_MS = 250
        private const val TAG = "SynAudio"

        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private val channels = player.channels().toInt()
    private val track: AudioTrack
    private val clock: PlayedClock
    private val bufferMs: Int
    private val thread = Thread(::run, "syn-audio")

    @Volatile private var stopRequested = false
    @Volatile private var doneWithPlayer = false
    @Volatile private var finished = false
    @Volatile private var load = 0.0
    @Volatile private var loadPeak = 0.0
    @Volatile private var lastUnderruns = 0

    init {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        val wantBytes = sampleRate * BUFFER_MS / 1000 * channels * Float.SIZE_BYTES
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBytes, wantBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        bufferMs = track.bufferSizeInFrames * 1000 / sampleRate
        // Nothing of this run is rendered yet: what the track plays from
        // here on starts at the player's current time.
        clock = PlayedClock(sampleRate, player.time())
    }

    fun start() {
        // Here, not on the thread: a stop() that comes before the thread is
        // under way must find the fade in already done, or that fade in
        // undoes the stop's fade out, the player is never silent and the
        // run never ends (on the API 26 emulator, 5 ms between the two).
        player.fadeIn()
        thread.start()
    }

    /** Fades out and ends the run; returns at once. */
    fun stop() {
        stopRequested = true
        player.fadeOut()
    }

    /** True once the track is released. */
    val isFinished: Boolean get() = finished

    /**
     * True once this run renders nothing more from the player — its fade out
     * is done (the track may still be playing it out). Only then may another
     * run start on the same player: two threads rendering one player would
     * interleave its sound.
     */
    val isDoneWithPlayer: Boolean get() = doneWithPlayer

    /** For tests: waits until the run has ended. */
    fun awaitFinished(timeoutMs: Long): Boolean {
        thread.join(timeoutMs)
        return finished
    }

    /** The player's time of what is being heard now. */
    fun playedSeconds(): Double {
        if (finished) return clock.seconds()
        val head = try {
            track.playbackHeadPosition
        } catch (e: IllegalStateException) {
            return clock.seconds()
        }
        // `finished` is set before the track is released: a position read
        // after that may be garbage, and this second look catches it.
        if (finished) return clock.seconds()
        return clock.update(head)
    }

    fun stats(): OutputStats {
        if (!finished) {
            try {
                lastUnderruns = track.underrunCount
            } catch (e: IllegalStateException) {
                // released meanwhile; keep the last count
            }
        }
        return OutputStats(sampleRate, channels, bufferMs, CHUNK_FRAMES, load, loadPeak, lastUnderruns)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val samples = CHUNK_FRAMES * channels
        val floats = FloatArray(samples)
        val chunkNanos = CHUNK_FRAMES * 1_000_000_000.0 / sampleRate
        try {
            track.play()
            while (true) {
                val t0 = System.nanoTime()
                val bytes = if (channels == 2) {
                    player.renderStereo(CHUNK_FRAMES.toUInt())
                } else {
                    player.render(CHUNK_FRAMES.toUInt())
                }
                val chunkLoad = (System.nanoTime() - t0) / chunkNanos
                load = if (load == 0.0) chunkLoad else load * 0.98 + chunkLoad * 0.02
                if (chunkLoad > loadPeak) loadPeak = chunkLoad

                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                var offset = 0
                while (offset < samples) {
                    val n = track.write(floats, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
                    check(n >= 0) { "AudioTrack.write returned $n" }
                    offset += n
                }
                if (stopRequested && player.isSilent()) break
            }
            doneWithPlayer = true
            // In stream mode stop() plays out what is buffered; release only
            // after that, or the fade is cut off.
            track.stop()
            Thread.sleep(bufferMs + 50L)
        } catch (t: Throwable) {
            Log.e(TAG, "the audio thread stopped", t)
            onError(t)
        } finally {
            doneWithPlayer = true
            try {
                lastUnderruns = track.underrunCount
            } catch (e: IllegalStateException) {
                // never initialized
            }
            finished = true
            track.release()
        }
    }
}
