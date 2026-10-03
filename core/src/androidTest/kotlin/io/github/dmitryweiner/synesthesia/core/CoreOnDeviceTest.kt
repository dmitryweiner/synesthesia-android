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
}
