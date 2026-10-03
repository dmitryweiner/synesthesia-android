package io.github.dmitryweiner.synesthesia.playback

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.MainThread
import io.github.dmitryweiner.synesthesia.audio.AudioOutput
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.PresetInfo
import io.github.dmitryweiner.synesthesia.core.SoundPlayer
import io.github.dmitryweiner.synesthesia.core.presetStateJson
import io.github.dmitryweiner.synesthesia.core.presets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The sound of the app: which point plays, whether it plays, and everything
 * Android asks of an app that plays in the background — audio focus (a call
 * pauses it, the end of the call resumes it), headphones unplugged (stops),
 * a CPU wake lock while it plays, and the foreground service with its
 * notification ([PlaybackService]).
 *
 * Main thread only. The audio itself runs on [AudioOutput]'s thread.
 */
@MainThread
class PlaybackController(private val context: Context) {
    data class State(
        val playing: Boolean = false,
        /** Stopped by another app taking the sound for a while; resumes by itself. */
        val pausedForFocus: Boolean = false,
        val presetIndex: Int = 0,
        val pointName: String = "",
        /** Something the user should know, e.g. why the sound stopped. */
        val message: String? = null,
    ) {
        /** The service stays in the foreground while this holds. */
        val holdsForeground: Boolean get() = playing || pausedForFocus
    }

    val presets: List<PresetInfo> = presets()

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** The device's own output rate: no resampling between the core and the speaker. */
    val sampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000

    private val _state = MutableStateFlow(State(presetIndex = 0, pointName = presets.first().name))
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: SoundPlayer? = null
    private var output: AudioOutput? = null
    /** The run that was stopped last; it may still be fading out of the player. */
    private var stopping: AudioOutput? = null
    private val launchRunnable = Runnable { launchOutput() }

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "synesthesia:playback")
        .apply { setReferenceCounted(false) }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioOutput.attributes)
        .setOnAudioFocusChangeListener(::onFocusChange, main)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }
    private var noisyRegistered = false

    /** Makes built-in point [index] the current one; if sound plays, it switches at once. */
    fun select(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        val json = presetStateJson(index.toUInt()) ?: return
        _state.update { it.copy(presetIndex = index, pointName = preset.name, message = null) }
        player?.switchTo(json)
    }

    fun toggle() = if (_state.value.holdsForeground) stop() else play()

    fun play() {
        val s = _state.value
        if (s.playing) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _state.update { it.copy(message = "Another app is holding the sound (a call?)") }
            return
        }
        startOutput()
        if (!PlaybackService.isInForeground) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }

    fun stop() {
        stopOutput()
        audioManager.abandonAudioFocusRequest(focusRequest)
        _state.update { it.copy(playing = false, pausedForFocus = false) }
    }

    /** What is being heard now, for the meters and the picture. */
    fun frameNow(): AudioFrame? {
        val out = output ?: return null
        return player?.frameAt(out.playedSeconds())
    }

    fun stats(): OutputStats? = output?.stats()

    @SuppressLint("WakelockTimeout") // held exactly while the sound plays, released in stopOutput()
    private fun startOutput() {
        if (player == null) {
            player = SoundPlayer(sampleRate.toUInt(), checkNotNull(presetStateJson(_state.value.presetIndex.toUInt())))
        }
        _state.update { it.copy(playing = true, pausedForFocus = false, message = null) }
        launchOutput()
        wakeLock.acquire()
        if (!noisyRegistered) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
    }

    /** Starts a run once the last one has let go of the player (a few hundred ms at most). */
    private fun launchOutput() {
        if (!_state.value.playing) return // stopped meanwhile
        if (output != null) return
        val previous = stopping
        if (previous != null && !previous.isDoneWithPlayer) {
            main.removeCallbacks(launchRunnable)
            main.postDelayed(launchRunnable, 20)
            return
        }
        stopping = null
        val p = player ?: return
        lateinit var run: AudioOutput
        try {
            // An error of a run that has since been replaced is not this one's.
            run = AudioOutput(p, sampleRate) { error -> main.post { if (output === run) fail(error) } }
        } catch (e: RuntimeException) {
            fail(e) // the device refused the track (format, rate)
            return
        }
        output = run
        run.start()
    }

    private fun stopOutput() {
        main.removeCallbacks(launchRunnable)
        output?.let {
            it.stop()
            stopping = it
        }
        output = null
        if (wakeLock.isHeld) wakeLock.release()
        if (noisyRegistered) {
            context.unregisterReceiver(noisy)
            noisyRegistered = false
        }
    }

    private fun fail(error: Throwable) {
        stop()
        _state.update { it.copy(message = "The sound stopped: ${error.message ?: error.javaClass.simpleName}") }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            // A call, an alarm, a navigation prompt: pause, keep the request,
            // and come back on AUDIOFOCUS_GAIN. (Ducking for a notification
            // sound is done by the system since Android 8.)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> if (_state.value.playing) {
                stopOutput()
                _state.update { it.copy(playing = false, pausedForFocus = true) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (_state.value.pausedForFocus) startOutput()
        }
    }
}
