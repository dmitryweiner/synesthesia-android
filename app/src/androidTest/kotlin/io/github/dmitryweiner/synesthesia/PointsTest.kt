package io.github.dmitryweiner.synesthesia

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dmitryweiner.synesthesia.core.PointList
import io.github.dmitryweiner.synesthesia.core.Session
import io.github.dmitryweiner.synesthesia.core.defaultSessionConfig
import io.github.dmitryweiner.synesthesia.playback.PlaybackController
import io.github.dmitryweiner.synesthesia.core.pointToken
import io.github.dmitryweiner.synesthesia.core.presetStateJson
import io.github.dmitryweiner.synesthesia.store.AppFiles
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The point files on a device (PLAN.md decision 7): that what the app writes
 * is what the console writes, that it comes back, and that an interrupted or
 * unreadable file does not take the points with it.
 */
@RunWith(AndroidJUnit4::class)
class PointsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private lateinit var files: AppFiles

    @Before
    fun aDirectoryOfItsOwn() {
        dir = File(context.cacheDir, "points-test-${System.nanoTime()}")
        dir.mkdirs()
        files = AppFiles(dir)
    }

    @After
    fun tidyUp() {
        dir.deleteRecursively()
    }

    @Test
    fun theLastPointComesBackAndTheFileIsTheWebAppsJson() {
        assertNull("nothing to come back to yet", files.lastPoint())
        val point = requireNotNull(presetStateJson(6u))
        files.saveLastPoint(point)
        assertTrue("the write finished", files.awaitWrites(5_000))

        assertEquals(point, files.lastPoint())
        val onDisk = File(dir, "last-point.json")
        assertTrue("named as the console names it", onDisk.exists())
        assertTrue(onDisk.readText().contains("\"presetName\""))
        assertTrue("no temporary left behind", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun keptPointsSurviveTheirFile() {
        val list = PointList()
        list.keep("Dawn", requireNotNull(presetStateJson(1u)))
        list.keep("Dusk", requireNotNull(presetStateJson(9u)))
        files.savePoints(list.toJson())
        assertTrue(files.awaitWrites(5_000))

        val back = PointList.parse(requireNotNull(files.points()))
        assertEquals(listOf("Dawn", "Dusk"), back.names())
        // And the point in it is a point the app can open.
        val json = requireNotNull(back.pointJson(1u))
        assertEquals(json, io.github.dmitryweiner.synesthesia.core.pointFromToken(pointToken(json)))
    }

    @Test
    fun anEmptyOrMissingFileIsSimplyNoPoints() {
        assertNull(files.points())
        File(dir, "points.json").writeText("   ")
        assertNull("whitespace is nothing", files.points())
        File(dir, "points.json").writeText("[]")
        assertEquals(0u, PointList.parse(requireNotNull(files.points())).count())
    }

    @Test
    fun theAppRestoresThePointItWasLeftOn() {
        // The app's own files, not the test's: this is the path the real
        // startup takes (PlaybackController reads them in its constructor).
        val playback = (context as SynesthesiaApp).playback
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { playback.select(4) }
        val name = playback.presets[4].name
        assertEquals(name, playback.state.value.session.pointName)

        // A load settles at once, so the last point is written by now.
        val appFiles = AppFiles(context.filesDir)
        waitFor("the last point to reach the disk") {
            appFiles.lastPoint()?.contains("\"presetName\":\"$name\"") == true
        }
        assertNotNull(appFiles.lastPoint())
    }

    @Test
    fun aPointLeftMidSearchComesBackUnderTheNameItHadOnScreen() {
        // A pressed point claims no name of its own — that is what a press
        // takes away — so the point file alone would come back as "", and the
        // title on a restart was empty. What the screen called it is kept
        // beside it.
        val config = defaultSessionConfig()
        val pressed = Session.onPreset(0u, config).use { session ->
            session.like(0.0)
            session.pointJson()
        }
        assertTrue("a pressed point has no name in it", !pressed.contains("presetName"))
        files.saveLastPoint(pressed)
        files.saveLastName("Fractal garden")
        assertTrue(files.awaitWrites(5_000))

        PlaybackController.startSession(files, config).use { back ->
            assertEquals("Fractal garden", back.view().pointName)
            // And it is still the user's unnamed point: a press counts from
            // here, and 💾 is what makes the name its own.
            assertEquals("Fractal garden", back.view().name)
        }

        // Without the name there is nothing to say, and nothing is said.
        File(dir, "last-name.txt").delete()
        PlaybackController.startSession(files, config).use { nameless ->
            assertEquals("", nameless.view().pointName)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }
}
