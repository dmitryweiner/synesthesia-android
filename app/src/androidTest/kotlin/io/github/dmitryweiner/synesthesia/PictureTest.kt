package io.github.dmitryweiner.synesthesia

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dmitryweiner.synesthesia.core.PictureDriver
import io.github.dmitryweiner.synesthesia.core.PictureFrame
import io.github.dmitryweiner.synesthesia.core.presetStateJson
import io.github.dmitryweiner.synesthesia.core.simGrid
import io.github.dmitryweiner.synesthesia.gl.SimRenderer
import io.github.dmitryweiner.synesthesia.gl.floatTargetsAvailable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seven passes on a real GL driver (PLAN.md phase 3): that the web app's
 * shaders compile here, that the float targets the simulation lives in exist,
 * and that what comes out is a picture — a Gray–Scott pattern that grows,
 * takes colour from the point and answers an injected disc.
 *
 * It renders into an off-screen surface of its own rather than into the
 * app's, so it can read the pixels back — which is also what lets it compare
 * the GPU's picture with the core's CPU one on a single seeded field.
 */
@RunWith(AndroidJUnit4::class)
class PictureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var display: EGLDisplay
    private lateinit var surface: EGLSurface
    private lateinit var eglContext: EGLContext

    @Before
    fun makeAnOffscreenContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        check(EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val chosen = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, chosen, 0,
            ) && chosen[0] > 0,
        ) { "no ES 3.0 config" }
        eglContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "no ES 3.0 context" }
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, SIDE, EGL14.EGL_HEIGHT, SIDE, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "eglMakeCurrent failed" }
    }

    @After
    fun releaseTheContext() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
    }

    @Test
    fun thePassesCompileAndDrawAPatternThatGrowsAndTakesItsPointsColour() {
        assumeTrue("this device has no float render targets: the CPU picture is its path", floatTargetsAvailable())
        val driver = PictureDriver(11u, requireNotNull(presetStateJson(0u)), false)
        val grid = simGrid(driver.rung().res.coerceAtMost(128u), SIDE.toUInt(), SIDE.toUInt())
        val sim = SimRenderer(context.assets, grid.width.toInt(), grid.height.toInt())
        assertEquals(grid.width.toInt(), sim.gridWidth)

        sim.reseed(driver.reseed())
        val seeded = render(sim, driver, steps = 1)
        assertTrue("the seed spots are visible", spread(seeded) > 2)

        // Thirty steps of the reaction: the pattern is not the seed any more.
        val grown = render(sim, driver, steps = 30)
        assertTrue("the pattern changed as it reacted", different(seeded, grown) > 0.02)
        assertTrue("and it is still a picture, not a flat colour", spread(grown) > 2)

        // Another point is another colour.
        driver.setPoint(requireNotNull(presetStateJson(10u)))
        val other = render(sim, driver, steps = 1)
        assertTrue("a different point paints differently", different(grown, other) > 0.01)
    }

    @Test
    fun aFingerLeavesItsMarkWhereTheFingerWas() {
        assumeTrue("this device has no float render targets", floatTargetsAvailable())
        // The field changes everywhere at every step, so "did the finger do
        // something" cannot be asked of two moments of one picture. It is
        // asked of two pictures of the same moment: same seed, same frames,
        // and a finger in only one of them.
        val grid = simGrid(96u, SIDE.toUInt(), SIDE.toUInt())
        val touched = PictureDriver(3u, requireNotNull(presetStateJson(1u)), false)
        val plain = PictureDriver(3u, requireNotNull(presetStateJson(1u)), false)
        val a = SimRenderer(context.assets, grid.width.toInt(), grid.height.toInt())
        val b = SimRenderer(context.assets, grid.width.toInt(), grid.height.toInt())
        val seed = touched.reseed()
        plain.reseed() // keep the two drivers' randomness in step
        a.reseed(seed)
        b.reseed(seed)

        var t = 0.0
        repeat(10) {
            t += 1.0 / 30.0
            a.stepAndDraw(touched.frame(t, null, a.aspect))
            b.stepAndDraw(plain.frame(t, null, b.aspect))
        }

        // A finger in the middle of one of them.
        touched.pointerDown(0.5f, 0.5f, t)
        t += 1.0 / 30.0
        val withFinger = touched.frame(t, null, a.aspect)
        val without = plain.frame(t, null, b.aspect)
        assertEquals(1, withFinger.injects.size)
        assertTrue("the other picture was not touched", without.injects.isEmpty())
        a.stepAndDraw(withFinger)
        val marked = readPixels()
        b.stepAndDraw(without)
        val unmarked = readPixels()
        touched.pointerUp()

        val middle = different(marked, unmarked, inMiddle = true)
        val edges = different(marked, unmarked, inMiddle = false)
        assertTrue("the finger's disc shows: $middle of the middle moved", middle > 0.1)
        assertTrue("and it shows where the finger was: $middle in the middle, $edges at the edges", middle > 3 * edges)
    }

    @Test
    fun theCpuPictureAndTheGpuPictureAreTheSamePicture() {
        assumeTrue("this device has no float render targets", floatTargetsAvailable())
        // The CPU picture's grid is twice its pixels, so a PIXELS-wide picture
        // and a 2*PIXELS grid put both paths on the same field (PLAN.md
        // decision 4: the CPU picture is the reference).
        val pixels = 64
        val driver = PictureDriver(21u, requireNotNull(presetStateJson(0u)), false)
        driver.useCpuPicture(21u, pixels.toUInt(), pixels.toUInt())
        val sim = SimRenderer(context.assets, 2 * pixels, 2 * pixels)
        val seed = driver.reseed()
        sim.reseed(seed)
        driver.cpuSeed(seed)

        var gpu = ByteArray(0)
        var cpu = ByteArray(0)
        var t = 0.0
        repeat(3) {
            t += 1.0 / 30.0
            val frame = driver.frame(t, null, sim.aspect)
            sim.step(frame)
            sim.draw(frame, pixels, pixels)
            // Both start at the field's row 0: glReadPixels hands back the
            // row at `uv.y = 0` first, and that is the row the CPU picture's
            // own first row draws.
            gpu = readPixels(pixels)
            cpu = requireNotNull(driver.cpuFrame()) { "the CPU picture" }
        }
        assertEquals(cpu.size, gpu.size)

        // A control: the same two paths from a different seed. The agreement
        // between the two renderers has to be far closer than that, or this
        // test would pass on any two pictures.
        val other = PictureDriver(22u, requireNotNull(presetStateJson(0u)), false)
        other.useCpuPicture(22u, pixels.toUInt(), pixels.toUInt())
        other.cpuSeed(other.reseed())
        other.frame(1.0 / 30.0, null, 1f)
        val unrelated = requireNotNull(other.cpuFrame())

        val agreement = meanDifference(gpu, cpu)
        val control = meanDifference(unrelated, cpu)
        assertTrue("two pictures of the same field: $agreement apart, two of different ones: $control", control > 4 * agreement)
        assertTrue("and they agree closely: $agreement", agreement < 24.0)
    }

    /** Steps and draws `steps` frames, and reads back the last one. */
    private fun render(sim: SimRenderer, driver: PictureDriver, steps: Int): ByteArray {
        var t = 0.0
        repeat(steps) {
            t += 1.0 / 30.0
            sim.stepAndDraw(driver.frame(t, null, sim.aspect))
        }
        return readPixels()
    }

    private fun SimRenderer.stepAndDraw(frame: PictureFrame) {
        step(frame)
        draw(frame, SIDE, SIDE)
    }

    private fun readPixels(side: Int = SIDE): ByteArray {
        val buffer = ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glReadPixels(0, 0, side, side, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        assertEquals("glReadPixels", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val out = ByteArray(side * side * 4)
        buffer.rewind()
        buffer.get(out)
        return out
    }

    /** Mean absolute difference per colour channel, 0..255. */
    private fun meanDifference(a: ByteArray, b: ByteArray): Double {
        var sum = 0L
        var counted = 0
        for (i in a.indices) {
            if (i % 4 == 3) continue // alpha
            sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            counted++
        }
        return sum.toDouble() / counted
    }

    /** How many distinct luminance levels the image has — a flat fill has one. */
    private fun spread(pixels: ByteArray): Int {
        val seen = HashSet<Int>()
        for (i in pixels.indices step 4) {
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            seen.add((r + g + b) / 3 / 8)
        }
        return seen.size
    }

    /**
     * The share of pixels that moved by more than a hair, over the whole
     * image or, with `inMiddle`, inside / outside the middle third.
     */
    private fun different(a: ByteArray, b: ByteArray, inMiddle: Boolean? = null): Double {
        var counted = 0
        var moved = 0
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                if (inMiddle != null) {
                    val middle = x > SIDE / 3 && x < 2 * SIDE / 3 && y > SIDE / 3 && y < 2 * SIDE / 3
                    if (middle != inMiddle) continue
                }
                val i = (y * SIDE + x) * 4
                counted++
                val d = (0..2).maxOf { c ->
                    kotlin.math.abs((a[i + c].toInt() and 0xFF) - (b[i + c].toInt() and 0xFF))
                }
                if (d > 4) moved++
            }
        }
        return if (counted == 0) 0.0 else moved.toDouble() / counted
    }

    private companion object {
        const val SIDE = 128
    }
}
