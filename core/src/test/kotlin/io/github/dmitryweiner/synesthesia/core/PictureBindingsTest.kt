package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture's driver through the generated Kotlin: the uniforms a renderer
 * fills, the discs it injects, the spots it seeds from and the rung it runs
 * at. The behaviour is pinned by `syn-core`'s own tests; this is the shape of
 * it after it has crossed the FFI, and that every number the shaders need is
 * actually there.
 */
class PictureBindingsTest {
    private fun driver(measure: Boolean = false) =
        PictureDriver(7u, requireNotNull(presetStateJson(0u)), measure)

    private fun sound(time: Double, hits: ULong) = AudioFrame(
        time = time,
        peak = 0.4f,
        rms = 0.2f,
        limiterDb = 0f,
        loudness = 0.7,
        swell = 0.8,
        brightness = 0.6,
        onset = 0.9,
        low = 0.8,
        mid = 0.4,
        high = 0.2,
        hits = hits,
        spectrum = ByteArray(64),
    )

    @Test
    fun aFrameFillsEveryUniformTheSevenPassesTake() {
        driver().use { driver ->
            val f = driver.frame(0.0, null, 1.78f)
            assertTrue("substeps ${f.reaction.substeps}", f.reaction.substeps >= 1u)
            assertTrue(f.reaction.feed > 0f && f.reaction.kill > 0f)
            assertTrue(f.reaction.diffU > 0f && f.reaction.diffV > 0f)
            for (v in listOf(f.palette.a, f.palette.b, f.palette.c, f.palette.d, f.palette.lightDir)) {
                assertEquals(3, v.size)
            }
            assertEquals(3, f.display.tint.size)
            assertEquals(1f, f.display.exposure, 1e-6f)
            assertEquals(0f, f.display.flash, 1e-6f)
            // Silence: nothing to inject and nothing rippling.
            assertTrue(f.injects.isEmpty() && f.ripples.isEmpty())
            assertEquals(0.0, f.time, 1e-9)
        }
    }

    @Test
    fun theSoundDrivesTheClockTheHitsAndTheColours() {
        driver().use { driver ->
            // The first frame only learns the hit counter.
            driver.frame(99.0, null, 1f)
            val f = driver.frame(100.0, sound(time = 12.0, hits = 3uL), 1f)
            assertEquals(12.0, f.time, 1e-9)
            assertTrue("a swell brightens: ${f.display.exposure}", f.display.exposure > 1f)
            assertTrue("an onset flares: ${f.display.flash}", f.display.flash > 0f)
            assertTrue(f.display.tint.any { it > 0f })
            assertEquals(3, f.injects.size)
            assertEquals(3, f.ripples.size)
            for ((disc, ring) in f.injects.zip(f.ripples)) {
                assertEquals(disc.x, ring.x, 0f)
                assertEquals(disc.y, ring.y, 0f)
                assertTrue(disc.x in 0.08f..0.92f && disc.y in 0.08f..0.92f)
            }
            // The LFOs carry on from the sound's clock when it stops.
            assertEquals(13.0, driver.frame(101.0, null, 1f).time, 1e-9)
        }
    }

    @Test
    fun aFingerPaintsAStrokeAndLiftsOff() {
        driver().use { driver ->
            driver.frame(0.0, null, 1f)
            driver.pointerDown(0.25f, 0.5f, 0.0)
            val landed = driver.frame(0.1, null, 1f)
            assertEquals(1, landed.injects.size)
            assertEquals(0.25f, landed.injects[0].x, 0f)
            assertEquals(1, landed.ripples.size)

            driver.pointerMoved(0.9f, 0.5f)
            val stroke = driver.frame(0.2, null, 1f)
            assertTrue("a drag is a stroke: ${stroke.injects.size}", stroke.injects.size > 1)
            assertEquals(0.9f, stroke.injects.last().x, 1e-6f)

            driver.pointerUp()
            assertTrue(driver.frame(0.3, null, 1f).injects.isEmpty())
        }
    }

    @Test
    fun aReseedIsTheSpotsTheSeedPassDraws() {
        driver().use { driver ->
            val seed = driver.reseed()
            assertEquals(seed.count.toInt() * 2, seed.xy.size)
            assertTrue(seed.count in 19u..maxSeedSpots())
            assertTrue(seed.radius > 0f)
            assertTrue(seed.xy.all { it in 0f..1f })
            assertNotEquals(seed.xy, driver.reseed().xy)
        }
    }

    @Test
    fun thePointTheRendererDrawsIsThePointThatPlays() {
        driver().use { driver ->
            val before = driver.frame(0.0, null, 1f)
            driver.setPoint(requireNotNull(presetStateJson(7u)))
            val after = driver.frame(0.1, null, 1f)
            assertNotEquals(before.reaction, after.reaction)
            try {
                driver.setPoint("{}")
                throw AssertionError("a malformed point should not load")
            } catch (e: CoreException.InvalidPoint) {
                assertTrue(e.reason.isNotEmpty())
            }
        }
    }

    @Test
    fun theProbeWalksTheLadderAndThenNeverMovesAgain() {
        driver(measure = true).use { driver ->
            assertEquals(0u, driver.rung().index)
            assertFalse(driver.probeDone())
            var moved = 0
            repeat(100) { if (driver.probeFrame(1.0)) moved++ }
            assertTrue(driver.probeDone())
            val top = qualityLadder().last()
            assertEquals(top.index, driver.rung().index)
            assertEquals(qualityLadder().size - 1, moved)
            assertFalse("a settled probe stays put", driver.probeFrame(5_000.0))
            assertEquals(top.index, driver.rung().index)
        }
        driver().use { fixed ->
            assertTrue("a driver told not to measure starts at the top", fixed.probeDone())
            assertEquals(qualityLadder().last().index, fixed.rung().index)
        }
    }

    @Test
    fun theSizesAndTheLadderComeFromTheCore() {
        val ladder = qualityLadder()
        assertEquals(6, ladder.size)
        assertTrue("the cheapest rung caps the surface", ladder.first().maxSide > 0u)
        assertEquals("the top rung is uncapped", 0u, ladder.last().maxSide)
        for (i in 1 until ladder.size) {
            assertTrue(ladder[i].res > ladder[i - 1].res)
        }
        // A 1080x2400 phone at the cheapest rung, and the grid that fits it.
        val store = backingStore(ladder[0].maxSide, 1080u, 2400u)
        assertEquals(288u, store.width)
        assertEquals(640u, store.height)
        val grid = simGrid(ladder[0].res, store.width, store.height)
        assertEquals(192u, grid.height)
        assertTrue(grid.width < grid.height)
        assertEquals(96u, fieldGrid(grid.width, grid.height).height)
        assertEquals(24u, maxSeedSpots())
        assertEquals(4u, maxRipples())
    }
}
