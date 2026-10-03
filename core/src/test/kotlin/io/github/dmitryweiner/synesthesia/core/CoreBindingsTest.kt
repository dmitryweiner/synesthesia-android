package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated Kotlin against the real core, built for this machine: what
 * the app gets through the bindings is what the core holds.
 */
class CoreBindingsTest {
    @Test
    fun theTwelvePresetsComeFromTheCoreInTheWebAppsOrder() {
        val list = presets()
        assertEquals(12, list.size)
        assertEquals((0 until 12).toList(), list.map { it.index.toInt() })
        assertEquals("Fractal garden", list.first().name)
        assertEquals("Subway basalt", list.last().name)
    }

    @Test
    fun aPresetsPointIsTheWebAppsJson() {
        val json = presetStateJson(0u)
        requireNotNull(json)
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("\"presetName\":\"Fractal garden\""))
        assertNull(presetStateJson(12u))
    }

    @Test
    fun theSchemaIsTheDumpedOne() {
        assertTrue(schemaJson().contains("\"formulaIds\""))
    }

    @Test
    fun theCoreSaysWhichVersionItIs() {
        assertTrue(coreVersion().isNotBlank())
    }
}
