package io.github.dmitryweiner.synesthesia

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dmitryweiner.synesthesia.audio.AudioOutput
import io.github.dmitryweiner.synesthesia.core.SoundPlayer
import io.github.dmitryweiner.synesthesia.core.presetStateJson
import io.github.dmitryweiner.synesthesia.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The sound path on a device: the track plays, the clock follows it, the service comes and goes. */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun anOutputPlaysAndItsClockFollowsTheTrack() {
        SoundPlayer(48_000u, requireNotNull(presetStateJson(0u))).use { player ->
            var error: Throwable? = null
            val out = AudioOutput(player, 48_000) { error = it }
            out.start()
            Thread.sleep(1500)
            val played = out.playedSeconds()
            val stats = out.stats()
            out.stop()
            assertTrue("the run ended", out.awaitFinished(3000))
            assertEquals(null, error)
            assertTrue("played $played s", played > 0.5 && played < 2.0)
            // What is heard trails what is rendered by about the buffer.
            assertTrue(player.time() >= played)
            assertTrue(stats.load > 0.0)
            assertTrue(requireNotNull(player.frameAt(played)).time <= played)
        }
    }

    @Test
    fun aStoppedRunLetsGoOfThePlayerSoTheNextCanStart() {
        SoundPlayer(48_000u, requireNotNull(presetStateJson(0u))).use { player ->
            val first = AudioOutput(player, 48_000) { throw AssertionError(it) }
            first.start()
            Thread.sleep(400)
            first.stop()
            waitFor("the first run done with the player", 2000) { first.isDoneWithPlayer }
            val second = AudioOutput(player, 48_000) { throw AssertionError(it) }
            second.start()
            Thread.sleep(800)
            val played = second.playedSeconds()
            assertTrue("the second run plays on from the first: ", played > 0.6)
            second.stop()
            assertTrue(second.awaitFinished(3000))
            assertTrue(first.awaitFinished(3000))
        }
    }

    /**
     * A stop that lands before the run's thread is under way: the run's fade
     * in must not undo the stop's fade out, or the player is never silent,
     * the run never ends and no later run may start — on the API 26 emulator
     * every ▶ after such a stop waited for good.
     */
    @Test
    fun aRunStoppedAsItStartsStillEnds() {
        SoundPlayer(48_000u, requireNotNull(presetStateJson(0u))).use { player ->
            repeat(10) { i ->
                // Kept, not thrown: a run that never ends outlives the test
                // and its player, and a throw there would take the process.
                var error: Throwable? = null
                val out = AudioOutput(player, 48_000) { error = it }
                out.start()
                out.stop()
                assertTrue("run $i let go of the player", out.awaitFinished(3000))
                assertEquals(null, error)
            }
        }
    }

    @Test
    fun stopAndPlayAtOnceKeepsPlaying() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val playback = (context as SynesthesiaApp).playback
            instrumentation.runOnMainSync { playback.play() }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                playback.stop()
                playback.play()
            }
            Thread.sleep(1200)
            var t1 = 0.0
            instrumentation.runOnMainSync { t1 = playback.frameNow()?.time ?: -1.0 }
            Thread.sleep(500)
            var t2 = 0.0
            instrumentation.runOnMainSync { t2 = playback.frameNow()?.time ?: -1.0 }
            assertTrue("frames advance after a restart: $t1 → $t2", t1 > 0 && t2 > t1)
            // The stop let the service go; the play must bring one back, not
            // be fooled by the one on its way out (API 35 emulator: the sound
            // played on with no service and no ⏹ in the notification).
            assertTrue("a service is in front after a restart", PlaybackService.isInForeground)
            instrumentation.runOnMainSync { playback.stop() }
            waitFor("service out of front") { !PlaybackService.isInForeground }
        }
    }

    @Test
    fun playingPutsTheServiceInFrontAndStoppingTakesItBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // A foreground service may only be started by an app that is in
        // front, as it is when the user presses ▶.
        ActivityScenario.launch(MainActivity::class.java).use { playInFront() }
    }

    /**
     * The notification left behind by a stop and the one of the next run
     * carry the same session, and it is alive: a session released under a
     * notification that stays makes SystemUI drop the player as dead and call
     * `stop` on the next run's session — the sound went silent a second after
     * ▶ on the API 35 emulator.
     */
    @Test
    fun theNotificationKeepsOneLiveSessionFromRunToRun() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val playback = (context as SynesthesiaApp).playback
        ActivityScenario.launch(MainActivity::class.java).use {
            playInFront()
            val left = waitForToken("the stopped notification") { true }
            val controller = MediaController(context, left)
            assertEquals(PlaybackState.STATE_STOPPED, controller.playbackState?.state)

            instrumentation.runOnMainSync { playback.play() }
            waitFor("service in front") { PlaybackService.isInForeground }
            val next = waitForToken("the playing notification") { playback.state.value.playing }
            assertEquals(left, next)
            waitFor("the session to say it plays") { controller.playbackState?.state == PlaybackState.STATE_PLAYING }

            instrumentation.runOnMainSync { playback.stop() }
            waitFor("service out of front") { !PlaybackService.isInForeground }
        }
    }

    /** The session token in our notification, once [ready] holds and the notification has one. */
    @Suppress("DEPRECATION") // getParcelable(key, class) is API 33+
    private fun waitForToken(what: String, ready: () -> Boolean): MediaSession.Token {
        val nm = context.getSystemService(NotificationManager::class.java)
        var token: MediaSession.Token? = null
        waitFor(what) {
            token = nm.activeNotifications.firstOrNull { it.id == 1 }?.notification?.extras
                ?.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            ready() && token != null
        }
        return requireNotNull(token)
    }

    private fun playInFront() {
        val playback = (context as SynesthesiaApp).playback
        instrumentation.runOnMainSync { playback.play() }
        waitFor("service in front") { PlaybackService.isInForeground }
        assertTrue(playback.state.value.playing)
        val nm = context.getSystemService(NotificationManager::class.java)
        waitFor("a notification") { nm.activeNotifications.isNotEmpty() }

        instrumentation.runOnMainSync { playback.select(3) }
        assertEquals(playback.presets[3].name, playback.state.value.session.pointName)

        instrumentation.runOnMainSync { playback.stop() }
        assertFalse(playback.state.value.playing)
        waitFor("service out of front") { !PlaybackService.isInForeground }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }
}
