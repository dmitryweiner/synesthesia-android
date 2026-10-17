package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The generated Kotlin against the real core, built for this machine: what
 * the app gets through the bindings is what the core holds.
 */
class CoreBindingsTest {
    @Test
    fun theBuiltInPointsComeFromTheCoreInTheWebAppsOrder() {
        val list = presets()
        assertEquals(20, list.size)
        assertEquals(list.indices.toList(), list.map { it.index.toInt() })
        assertEquals("Fractal garden", list.first().name)
        // The three the web app added last: they use the tanpura, the singing
        // bowl, the delay's shimmer and the pink LFOs.
        assertEquals(listOf("Overtone steppe", "Candle glaze", "Tanpura halo"), list.slice(12..14).map { it.name })
        // Then the five with recorded samples and drums (the core's S5).
        assertEquals(
            listOf("Night meadow", "Workshop", "Glass loom", "Magic forest", "Sea shore"),
            list.takeLast(5).map { it.name },
        )
    }

    @Test
    fun aSampleBankIsRefusedWhenItIsNotOne() {
        val e = runCatching { SoundBank("nope".toByteArray()) }.exceptionOrNull()
        assertTrue("$e", e is CoreException.BadBank)
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
    fun aStereoPlayerGivesTwoEarsAndAMonoOneTheSameInBoth() {
        val point = requireNotNull(presetStateJson(0u))
            .replaceFirst("{", "{\"bin\":{\"on\":true,\"beat\":6,\"width\":0.8},")
        val frames = 4800
        fun ears(player: SoundPlayer): FloatArray {
            player.fadeIn()
            var bytes = ByteArray(0)
            repeat(5) { bytes = player.renderStereo(frames.toUInt()) }
            val out = FloatArray(2 * frames)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
        SoundPlayer.newStereo(48_000u, point).use { stereo ->
            assertEquals(2u, stereo.channels())
            val s = ears(stereo)
            assertTrue("the ears differ", (0 until frames).any { s[2 * it] != s[2 * it + 1] })
        }
        SoundPlayer(48_000u, point).use { mono ->
            assertEquals(1u, mono.channels())
            val s = ears(mono)
            assertTrue("one sound in both ears", (0 until frames).all { s[2 * it] == s[2 * it + 1] })
        }
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
