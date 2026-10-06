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
    fun theBuiltInPointsComeFromTheCoreInTheWebAppsOrder() {
        val list = presets()
        assertEquals(15, list.size)
        assertEquals(list.indices.toList(), list.map { it.index.toInt() })
        assertEquals("Fractal garden", list.first().name)
        // The three the web app added last: they use the tanpura, the singing
        // bowl, the delay's shimmer and the pink LFOs.
        assertEquals(listOf("Overtone steppe", "Candle glaze", "Tanpura halo"), list.takeLast(3).map { it.name })
    }

    @Test
    fun theNewInstrumentsAndTheShimmerArrivedWithThem() {
        val halo = requireNotNull(presetStateJson(14u))
        assertTrue("the tanpura is in Tanpura halo", halo.contains("\"tanpura\""))
        assertTrue("and it is switched on", halo.contains("\"tanpura\":{\"enabled\":true"))
        val schema = schemaJson()
        for (id in listOf("tanpura", "bowl")) {
            assertTrue("the schema knows $id", schema.contains("\"$id\""))
        }
        assertTrue("and the delay's shimmer", schema.contains("delayShimmer"))
        assertTrue("and the pink LFO", schema.contains("\"pink\""))
    }

    @Test
    fun aPresetsPointIsTheWebAppsJson() {
        val json = presetStateJson(0u)
        requireNotNull(json)
        assertTrue(json.contains("\"v\":1"))
        assertTrue(json.contains("\"preset_name\":\"Fractal garden\""))
        assertNull("past the end of the list", presetStateJson(presets().size.toUInt()))
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
