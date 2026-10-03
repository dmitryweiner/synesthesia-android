package io.github.dmitryweiner.synesthesia.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core on Android itself: JNA's Android natives load, libsyn_android.so
 * for this device's ABI loads, and the calls return what the core holds —
 * the part the JVM tests (a host build) cannot show.
 */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    @Test
    fun theCoreLoadsAndListsTheTwelvePresets() {
        val list = presets()
        assertEquals(12, list.size)
        assertEquals("Fractal garden", list.first().name)
    }

    @Test
    fun aPresetsPointComesBackAsJson() {
        val json = presetStateJson(0u)
        requireNotNull(json)
        assertTrue(json.contains("\"presetName\":\"Fractal garden\""))
    }

    /**
     * An object across the FFI on a real Android runtime: on API 26 there is
     * no java.lang.ref.Cleaner, and the bindings must fall back to JNA's.
     */
    @Test
    fun aPlayerRendersFloatPcmAndPublishesFrames() {
        SoundPlayer(48_000u, requireNotNull(presetStateJson(0u))).use { p ->
            p.fadeIn()
            var bytes = ByteArray(0)
            repeat(48) { bytes = p.render(1024u) }
            assertEquals(1024 * 4, bytes.size)
            assertEquals(48 * 1024 / 48_000.0, p.time(), 1e-9)
            val frame = requireNotNull(p.frameAt(0.5))
            assertTrue(frame.time <= 0.5)
            assertEquals(64, frame.spectrum.size)
            p.switchTo(requireNotNull(presetStateJson(5u)))
            p.render(1024u)
        }
    }

    @Test
    fun aMalformedPointIsAnExceptionNotACrash() {
        try {
            SoundPlayer(48_000u, "{}")
            throw AssertionError("expected CoreException")
        } catch (e: CoreException.InvalidPoint) {
            assertTrue(e.reason.isNotEmpty())
        }
    }
}
