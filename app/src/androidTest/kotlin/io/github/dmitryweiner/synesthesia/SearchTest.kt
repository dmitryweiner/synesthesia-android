package io.github.dmitryweiner.synesthesia

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dmitryweiner.synesthesia.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search on a device: a press is heard, the morph runs on the app's own
 * clock (and must keep running with the screen off), and the notification
 * steers the point without unlocking the phone (PLAN.md phase 2).
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val playback get() = (context as SynesthesiaApp).playback

    @Test
    fun aPressMorphsTheLiveSoundAndSettlesOnTheNewPoint() {
        allowNotifications()
        ActivityScenario.launch(MainActivity::class.java).use {
            onMain { playback.play() }
            waitFor("playing") { playback.state.value.playing }
            Thread.sleep(600)
            val before = requireNotNull(frameNow()) { "the sound should be reporting frames" }

            onMain { playback.like() }
            val pressed = playback.state.value.session
            assertEquals(1u, pressed.steps)
            assertTrue("a change is arriving", pressed.morphing)
            assertTrue(pressed.status, pressed.status.contains("👍"))

            // The controller's own ticker carries the morph to its end: this
            // is what has to keep working with no screen in front of it.
            waitFor("the morph lands", 6000) { !playback.state.value.session.morphing }
            val after = requireNotNull(frameNow()) { "the sound should still be playing" }
            assertTrue("the sound went on through the morph: $before → $after", after > before)
            assertTrue(playback.state.value.session.name.endsWith("1 step"))

            onMain { playback.stop() }
            waitFor("service out of front") { !PlaybackService.isInForeground }
        }
    }

    @Test
    fun theNotificationSteersTheSearch() {
        allowNotifications()
        ActivityScenario.launch(MainActivity::class.java).use {
            onMain { playback.play() }
            waitFor("service in front") { PlaybackService.isInForeground }

            val notification = activeNotification()
            // 👎 ⏹ 👍 — the whole of the search, on the lock screen.
            assertEquals(3, notification.actions.size)

            val steps = playback.state.value.session.steps
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_DISLIKE))
            waitFor("the press arrived") { playback.state.value.session.steps > steps }
            assertTrue(playback.state.value.session.status.contains("👎"))

            onMain { playback.stop() }
            waitFor("service out of front") { !PlaybackService.isInForeground }
        }
    }

    @Test
    fun aBuiltInPointLoadsWhileTheSoundPlays() {
        allowNotifications()
        ActivityScenario.launch(MainActivity::class.java).use {
            onMain { playback.play() }
            waitFor("playing") { playback.state.value.playing }
            onMain { playback.like() }
            waitFor("the morph lands", 6000) { !playback.state.value.session.morphing }

            onMain { playback.select(3) }
            val loaded = playback.state.value.session
            assertEquals(playback.presets[3].name, loaded.pointName)
            assertEquals(0u, loaded.steps)
            assertTrue("a load clears the history", !loaded.canUndo)

            Thread.sleep(600)
            assertTrue("the sound survived the switch", requireNotNull(frameNow()) > 0.0)
            onMain { playback.stop() }
            waitFor("service out of front") { !PlaybackService.isInForeground }
        }
    }

    private fun frameNow(): Double? {
        var t: Double? = null
        onMain { t = playback.frameNow()?.time }
        return t
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun activeNotification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .first { it.id == 1 }
        .notification

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }
}
