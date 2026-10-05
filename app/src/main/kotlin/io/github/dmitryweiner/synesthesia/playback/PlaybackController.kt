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
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import io.github.dmitryweiner.synesthesia.R
import io.github.dmitryweiner.synesthesia.audio.AudioOutput
import io.github.dmitryweiner.synesthesia.audio.OutputStats
import io.github.dmitryweiner.synesthesia.core.AudioFrame
import io.github.dmitryweiner.synesthesia.core.CoreException
import io.github.dmitryweiner.synesthesia.core.LinkPoint
import io.github.dmitryweiner.synesthesia.core.PictureDriver
import io.github.dmitryweiner.synesthesia.core.PointEdit
import io.github.dmitryweiner.synesthesia.core.PointList
import io.github.dmitryweiner.synesthesia.core.PresetInfo
import io.github.dmitryweiner.synesthesia.core.Session
import io.github.dmitryweiner.synesthesia.core.SessionConfig
import io.github.dmitryweiner.synesthesia.core.SessionEffect
import io.github.dmitryweiner.synesthesia.core.SessionView
import io.github.dmitryweiner.synesthesia.core.SoundPlayer
import io.github.dmitryweiner.synesthesia.core.defaultSessionConfig
import io.github.dmitryweiner.synesthesia.core.pointFromLink
import io.github.dmitryweiner.synesthesia.core.pointFromToken
import io.github.dmitryweiner.synesthesia.core.pointToken
import io.github.dmitryweiner.synesthesia.core.presets
import io.github.dmitryweiner.synesthesia.gl.PictureSource
import io.github.dmitryweiner.synesthesia.store.AppFiles
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The session of the app: which point it is on, whether it plays, and
 * everything Android asks of an app that plays in the background — audio
 * focus (a call pauses it, the end of the call resumes it), headphones
 * unplugged (stops), a CPU wake lock while it plays, and the foreground
 * service with its notification ([PlaybackService]).
 *
 * The control logic itself is the core's ([Session], PLAN.md decision 2): the
 * 👍 👎 🎲 ↩, the morph, the scout's scheduling, the point's name and the
 * status line. This class is what the core has no business knowing: a clock,
 * an audio device, a background thread and Android's lifecycle. Every session
 * call answers with effects, and [applyEffects] is the whole of what they mean here.
 *
 * Main thread only, with three exceptions, each marked: the picture's GL
 * thread reads [soundFrame] and [takeReseed] and drives the core's
 * [PictureDriver], and one scout job at a time runs on [scouts].
 */
@MainThread
class PlaybackController(
    private val context: Context,
    config: SessionConfig = defaultSessionConfig(),
) : PictureSource {
    data class State(
        val playing: Boolean = false,
        /** Stopped by another app taking the sound for a while; resumes by itself. */
        val pausedForFocus: Boolean = false,
        /** What the core's session says about itself: name, step, status, undo. */
        val session: SessionView,
        /** ⚙ Settings is open: the picture stops while it is. */
        val settingsOpen: Boolean = false,
        /** Something the user should know, e.g. why the sound stopped. */
        val message: String? = null,
    ) {
        /** The service stays in the foreground while this holds. */
        val holdsForeground: Boolean get() = playing || pausedForFocus

        /** The point's name with its step count — the title and the notification. */
        val pointName: String get() = session.name
    }

    val presets: List<PresetInfo> = presets()

    /** What the app tells the user, in the phone's language. */
    private fun say(@StringRes line: Int, vararg args: Any): String = context.getString(line, *args)

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** The device's own output rate: no resampling between the core and the speaker. */
    val sampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000

    /** What is kept between runs, in the app's own directory (decision 7). */
    val files = AppFiles(context.filesDir)

    /**
     * The points the user kept. The core holds the list and its rules; this
     * class writes the file whenever the list changes.
     */
    val points: PointList = loadPoints(files)

    /**
     * A session on the point the app was last left on, or on the first
     * built-in one, with its own seed, so two runs of the app do not explore
     * the same way.
     */
    private val session: Session = startSession(files, config.copy(seed = System.nanoTime().toUInt()))

    /**
     * The picture's per-frame driver (PLAN.md decision 4). It holds the point
     * the picture is of, the ripples, the LFO clock and the quality rung; the
     * GL thread asks it for a frame and this class keeps its point in step
     * with the sound's.
     */
    override val picture: PictureDriver =
        PictureDriver(System.nanoTime().toUInt(), session.livePointJson(), measure = true)

    /** Set by a session effect, taken by the GL thread on its next frame. */
    private val reseedPending = AtomicBoolean(false)

    /**
     * The thread a scout job is handed to. The rendering itself spreads over
     * the core's own pool (`cores − 2` threads, PLAN.md decision 6); this
     * thread only waits for it, so nothing on screen ever waits.
     */
    private val scouts = Executors.newSingleThreadExecutor { r ->
        Thread(r, "syn-scout-host").apply { priority = Thread.MIN_PRIORITY }
    }

    private val _state = MutableStateFlow(State(session = session.view()))
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    // The GL thread reads these through soundFrame(); only this thread writes.
    @Volatile private var player: SoundPlayer? = null

    @Volatile private var output: AudioOutput? = null
    /** The run that was stopped last; it may still be fading out of the player. */
    private var stopping: AudioOutput? = null
    private val launchRunnable = Runnable { launchOutput() }
    private val tickRunnable = Runnable { tick() }

    /** The point ⚙ Settings is editing, while it is open. */
    private var editing: PointEdit? = null
    private var lastEditPush = Double.NEGATIVE_INFINITY
    private val editPushRunnable = Runnable {
        editing?.let { edit ->
            lastEditPush = now()
            player?.setPoint(edit.pointJson())
            picture.setPoint(edit.pointJson())
        }
    }

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

    // --- what the user presses ---------------------------------------------

    /** 👍 more of this: the search carries on the way it was going. */
    fun like() = applyEffects(session.like(now()))

    /** 👎 not this: back, and elsewhere. */
    fun dislike() = applyEffects(session.dislike(now()))

    /** 🎲 somewhere else entirely, near another built-in point. */
    fun surprise() = applyEffects(session.surprise(now()))

    /** ↩ back one step. */
    fun undo() = applyEffects(session.undo(now()))

    /** Loads built-in point [index]: a fresh search, and a hard switch if sound plays. */
    fun select(index: Int) {
        if (index !in presets.indices) return
        applyEffects(session.loadPreset(now(), index.toUInt()))
    }

    /** The name to offer for 💾: the user's own name again, or a fresh one. */
    fun suggestedName(): String = points.suggestName(session.view().pointName.takeIf { session.view().steps == 0u })

    /**
     * 💾 Keeps the point under `name`, replacing one of the same name. The
     * point becomes the user's own, named one — the title says so.
     */
    fun keep(name: String) {
        val clean = name.trim().ifEmpty { suggestedName() }
        try {
            points.keep(clean, session.pointJson())
        } catch (e: CoreException) {
            _state.update { it.copy(message = say(R.string.msg_keep_failed, e.message.orEmpty())) }
            return
        }
        files.savePoints(points.toJson())
        applyEffects(session.keptAs(clean))
    }

    /** Loads a kept point: a fresh search, a hard switch, a new picture. */
    fun open(index: Int) {
        val json = points.pointJson(index.toUInt()) ?: return
        val name = points.nameAt(index.toUInt()) ?: ""
        applyEffects(session.load(now(), name, json))
    }

    /** Forgets a kept point. What is playing keeps playing. */
    fun forget(index: Int) {
        if (!points.forget(index.toUInt())) return
        files.savePoints(points.toJson())
        _state.update { it.copy(session = session.view()) }
    }

    /** The point the search is at — what Details reads and 💾 keeps. */
    fun pointJson(): String = session.pointJson()

    /** The point as the web app's `#s=` token, to paste into a browser. */
    fun token(): String = pointToken(session.pointJson())

    /**
     * A point pasted from a browser: a token, or the whole link it was in.
     * Says what happened, for the status line.
     */
    fun importToken(text: String): String {
        val json = try {
            pointFromToken(text)
        } catch (e: CoreException) {
            return say(R.string.msg_not_a_token)
        }
        applyEffects(session.load(now(), "", json))
        return say(R.string.msg_opened_pasted)
    }

    /**
     * The web app's URL, handed over by the system. A link to a point kept on
     * its server cannot be opened here — there is no network (decision 8) —
     * and says so.
     */
    fun openLink(url: String): String? = when (val target = pointFromLink(url)) {
        is LinkPoint.Point -> {
            applyEffects(session.load(now(), "", target.pointJson))
            say(R.string.msg_opened_link)
        }
        is LinkPoint.Preset -> {
            val index = target.index.toInt()
            if (index in presets.indices) {
                select(index)
                say(R.string.msg_opened_preset, presets[index].name)
            } else {
                say(R.string.msg_link_unknown)
            }
        }
        is LinkPoint.NeedsTheWebApp ->
            say(R.string.msg_link_needs_web)
        LinkPoint.Nothing -> null
    }

    fun toggle() = if (_state.value.holdsForeground) stop() else play()

    fun play() {
        if (_state.value.playing) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _state.update { it.copy(message = say(R.string.msg_focus_denied)) }
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
        applyEffects(session.setPlaying(now(), false))
    }

    /**
     * What is being heard now — for the meters and for the picture, which
     * runs on the sound rather than on the render clock (the device buffers
     * 250 ms ahead of the speaker).
     *
     * Any thread: the two fields it reads are volatile and written only here.
     */
    override fun soundFrame(): AudioFrame? {
        val out = output ?: return null
        return player?.frameAt(out.playedSeconds())
    }

    /** What is being heard now. */
    fun frameNow(): AudioFrame? = soundFrame()

    /** Any thread: the GL thread takes this on its next frame. */
    override fun takeReseed(): Boolean = reseedPending.getAndSet(false)

    // --- ⚙ Settings --------------------------------------------------------

    /**
     * ⚙ Settings opens: a morph in flight lands (the page edits the point it
     * was heading to), the scout stops, and the picture stops with it — the
     * web app pauses it so the result is seen on closing, and a phone has the
     * cores to spare for the sound instead.
     *
     * The page edits the returned [PointEdit]; [settingsEdited] is what makes
     * an edit audible.
     */
    fun openSettings(): PointEdit {
        applyEffects(session.openSettings(now()))
        _state.update { it.copy(settingsOpen = true) }
        val edit = PointEdit(session.pointJson())
        editing = edit
        return edit
    }

    /**
     * The page changed something. The point goes to the sound at most as
     * often as a morph pushes: a finger on a slider fires far more often than
     * the engine needs, and the last value always arrives (the tick that
     * follows carries it).
     */
    fun settingsEdited() {
        val edit = editing ?: return
        val now = now()
        if (now - lastEditPush >= PUSH_SECONDS) {
            lastEditPush = now
            player?.setPoint(edit.pointJson())
            picture.setPoint(edit.pointJson())
        } else {
            main.removeCallbacks(editPushRunnable)
            main.postDelayed(editPushRunnable, (PUSH_SECONDS * 1000).toLong())
        }
    }

    /**
     * ⚙ Settings closes. A change is one undoable step and a jump, not a
     * morph: the sound was edited as it played, so it is already there.
     * Nothing changed means nothing but a settle.
     */
    fun closeSettings() {
        main.removeCallbacks(editPushRunnable)
        val edit = editing ?: return
        editing = null
        applyEffects(session.closeSettings(now(), edit.pointJson()))
        _state.update { it.copy(settingsOpen = false) }
    }

    fun stats(): OutputStats? = output?.stats()

    /** Puts a line in front of the user — what a link did, or what went wrong. */
    fun say(message: String) {
        _state.update { it.copy(message = message) }
    }

    /** Threads the scout renders on — for the bench and the details page. */
    fun scoutThreads(): Int = session.scoutThreads().toInt()

    // --- the session's effects ---------------------------------------------

    /**
     * The clock the pure session has none of: monotonic seconds, the same one
     * every call is stamped with.
     *
     * It stands still while the device is in deep sleep, and so does the
     * `Handler` that ticks — which is the right pair: the wake lock keeps
     * both running while the sound plays, and when nothing plays a morph that
     * waited through a sleep lands on its target at the next tick.
     */
    private fun now(): Double = System.nanoTime() / 1_000_000_000.0

    /**
     * Carries out what a session call asked for, and keeps the clock running
     * exactly as long as the session has something due — one main-thread
     * wake-up per morph frame, and none at all in between (the screen may be
     * off for hours).
     */
    private fun applyEffects(effects: List<SessionEffect>) {
        for (effect in effects) {
            when (effect) {
                // A morph: the parameters glide, the engine is not rebuilt.
                is SessionEffect.SetPoint -> {
                    player?.setPoint(effect.pointJson)
                    picture.setPoint(effect.pointJson)
                }
                // A load: the previous point's reverb and delay tails go.
                is SessionEffect.SwitchTo -> {
                    player?.switchTo(effect.pointJson)
                    picture.setPoint(effect.pointJson)
                }
                // The picture starts over from a fresh seed, on the GL thread
                // (it is the one that can draw the spots).
                SessionEffect.Reseed -> reseedPending.set(true)
                // The point to come back to, through a temporary file on a
                // thread of its own (AppFiles), and what it is called on
                // screen — the point itself claims no name once it has been
                // stepped away from the one it came from.
                is SessionEffect.SaveLastPoint -> {
                    files.saveLastPoint(effect.pointJson)
                    files.saveLastName(session.view().pointName)
                }
                // Seconds of rendering, off this thread. A result for a point
                // the user has left is dropped by the session, by its version.
                SessionEffect.StartScout -> scouts.execute {
                    val done = session.runScout()
                    main.post { applyEffects(done) }
                }
                // The line is in the view, which every applyEffects publishes.
                is SessionEffect.Status -> Unit
            }
        }
        _state.update { it.copy(session = session.view()) }
        if (session.wantsTick()) {
            main.removeCallbacks(tickRunnable)
            main.postDelayed(tickRunnable, TICK_MS)
        } else {
            main.removeCallbacks(tickRunnable)
        }
    }

    private fun tick() = applyEffects(session.tick(now()))

    // --- the sound ---------------------------------------------------------

    @SuppressLint("WakelockTimeout") // held exactly while the sound plays, released in stopOutput()
    private fun startOutput() {
        val existing = player
        if (existing == null) {
            player = SoundPlayer(sampleRate.toUInt(), session.livePointJson())
        } else {
            // The point may have moved while nothing was playing.
            existing.setPoint(session.livePointJson())
        }
        _state.update { it.copy(playing = true, pausedForFocus = false, message = null) }
        launchOutput()
        wakeLock.acquire()
        if (!noisyRegistered) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
        applyEffects(session.setPlaying(now(), true))
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
        _state.update {
            it.copy(message = say(R.string.msg_sound_stopped, error.message ?: error.javaClass.simpleName))
        }
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
                // A change still arrives while the call lasts; it is simply
                // not pushed to a sound nobody can hear.
                applyEffects(session.setPlaying(now(), false))
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (_state.value.pausedForFocus) startOutput()
        }
    }

    /** What the two files mean on the way in; the device tests read them. */
    internal companion object {
        /**
         * How often the session is stepped while something is due: a morph
         * pushes at most every 50 ms (the core's `push_interval`), so this is
         * twice as often and no more.
         */
        const val TICK_MS = 25L

        /**
         * How often an edit reaches the sound — the core's `push_interval`,
         * which is what a morph uses.
         */
        const val PUSH_SECONDS = 0.05

        private const val TAG = "SynPlayback"

        /**
         * The kept points. A file that cannot be read is kept, not
         * overwritten: an empty list is written back only once the user keeps
         * something, and until then the file is still there to be rescued.
         */
        fun loadPoints(files: AppFiles): PointList {
            val json = files.points() ?: return PointList()
            return try {
                PointList.parse(json)
            } catch (e: CoreException) {
                Log.w(TAG, "the points file is not readable; starting with none", e)
                PointList()
            }
        }

        /** Where the app was left, or the first built-in point. */
        fun startSession(files: AppFiles, config: SessionConfig): Session {
            val json = files.lastPoint()
            if (json != null) {
                try {
                    // A kept point carries its own name, and "" takes it; a
                    // point left mid-search carries none, and then the name
                    // that was on screen says where it came from.
                    return if (json.contains("\"presetName\"")) {
                        Session("", json, config)
                    } else {
                        Session.restored(files.lastName() ?: "", json, config)
                    }
                } catch (e: CoreException) {
                    Log.w(TAG, "the last point is not readable; starting from the first", e)
                }
            }
            return Session.onPreset(0u, config)
        }
    }
}
