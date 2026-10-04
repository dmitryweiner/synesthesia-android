package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core's session through the generated Kotlin: what an app has to do with
 * a press, and that the points it hands back are points the player accepts.
 * The behaviour itself is pinned by `syn-session`'s own host tests; this is
 * the shape of it after it has crossed the FFI.
 */
class SessionBindingsTest {
    /** Without the scout: a candidate render is seconds of work. */
    private fun config() = defaultSessionConfig().copy(scout = false)

    private fun kinds(effects: List<SessionEffect>) = effects.map {
        when (it) {
            is SessionEffect.SetPoint -> "set"
            is SessionEffect.SwitchTo -> "switch"
            SessionEffect.Reseed -> "reseed"
            is SessionEffect.SaveLastPoint -> "save"
            SessionEffect.StartScout -> "scout"
            is SessionEffect.Status -> "status"
        }
    }

    @Test
    fun aSessionStartsOnABuiltInPointAndNamesIt() {
        Session.onPreset(0u, config()).use { session ->
            val view = session.view()
            assertEquals("Fractal garden", view.name)
            assertEquals("Fractal garden", view.pointName)
            assertEquals(0u, view.steps)
            assertFalse(view.canUndo)
            assertFalse(view.morphing)
            assertFalse(session.wantsTick())
            assertTrue(session.pointJson().contains("\"presetName\":\"Fractal garden\""))
        }
    }

    @Test
    fun aPressMorphsTheSoundAndLandsOnThePointItNamed() {
        Session.onPreset(0u, config()).use { session ->
            session.setPlaying(0.0, true)
            assertEquals(listOf("status"), kinds(session.like(0.0)))
            assertTrue(session.wantsTick())
            assertEquals(1u, session.view().steps)
            assertEquals("Fractal garden · 1 step", session.view().name)
            val said = session.view().status
            assertTrue(said, said.contains("👍") && said.contains("step 1"))

            // Half-way: one push, and not yet where it is heading.
            assertEquals(listOf("set"), kinds(session.tick(1.0)))
            // At the end: the last push is the point itself, and it is kept.
            val end = session.tick(2.0)
            assertEquals(listOf("set", "save"), kinds(end))
            val landed = end.filterIsInstance<SessionEffect.SetPoint>().last().pointJson
            assertEquals(session.pointJson(), landed)
            assertFalse(session.wantsTick())

            // And it is a point the live player plays.
            SoundPlayer(22_050u, landed).use { player ->
                player.fadeIn()
                assertEquals(1024 * 4, player.render(1024u).size)
            }
        }
    }

    @Test
    fun aLoadSwitchesHardAndStartsTheSearchOver() {
        Session.onPreset(0u, config()).use { session ->
            session.like(0.0)
            session.tick(2.0)
            assertTrue(session.view().canUndo)

            val fx = session.loadPreset(3.0, 5u)
            assertEquals(listOf("switch", "reseed", "status", "save"), kinds(fx))
            val view = session.view()
            assertEquals(presets()[5].name, view.name)
            assertEquals(0u, view.steps)
            assertFalse("a load clears the history", view.canUndo)
            val switched = fx.filterIsInstance<SessionEffect.SwitchTo>().single().pointJson
            assertTrue(switched.contains("\"presetName\":\"${presets()[5].name}\""))
        }
    }

    @Test
    fun undoGoesBackAndSaysWhenThereIsNothingLeft() {
        Session.onPreset(0u, config()).use { session ->
            assertEquals(
                "nothing to undo",
                session.undo(0.0).filterIsInstance<SessionEffect.Status>().single().text,
            )
            session.like(0.0)
            session.tick(2.0)
            assertEquals(1u, session.view().undoDepth)
            session.undo(2.0)
            assertEquals(0u, session.view().steps)
            assertTrue(session.view().morphing)
        }
    }

    @Test
    fun aMalformedPointIsAnErrorAndSoIsAnUnknownPreset() {
        Session.onPreset(0u, config()).use { session ->
            try {
                session.load(0.0, "nope", "{}")
                throw AssertionError("a malformed point should not load")
            } catch (e: CoreException.InvalidPoint) {
                assertTrue(e.reason.isNotEmpty())
            }
            val pastTheEnd = presets().size.toUInt()
            try {
                session.loadPreset(0.0, pastTheEnd)
                throw AssertionError("there is no preset $pastTheEnd")
            } catch (e: CoreException.NoSuchPreset) {
                assertEquals(pastTheEnd, e.index)
            }
        }
    }

    @Test
    fun theScoutRendersCandidatesOnItsOwnThreadsAndAPressTakesOne() {
        // One candidate a direction, a second of audio each: enough to show
        // the whole way round — a job goes out, a thread runs it, the line
        // comes back, and the next press is made from what it found.
        val config = defaultSessionConfig().copy(scoutCandidates = 1u, scoutSeconds = 1.0, scoutThreads = 2u)
        Session.onPreset(0u, config).use { session ->
            assertEquals(2, session.scoutThreads().toInt())
            session.setPlaying(0.0, true)
            assertEquals(listOf("scout"), kinds(session.tick(config.scoutSettle + 0.01)))
            assertTrue(session.view().scoutBusy)

            val done = session.runScout()
            val said = done.filterIsInstance<SessionEffect.Status>().single().text
            assertTrue(said, said.startsWith("scouted 1 + 1 candidates in "))
            assertFalse(session.view().scoutBusy)
            assertEquals(1u, session.view().scoutedLike)

            val press = session.like(2.0).filterIsInstance<SessionEffect.Status>().single().text
            assertTrue(press, press.contains("best of 1"))
            assertEquals(0u, session.view().scoutedLike)
            assertTrue("a job that was never started runs nothing", session.runScout().isEmpty())
        }
    }

    @Test
    fun theDefaultsComeFromTheCore() {
        val c = defaultSessionConfig()
        assertEquals(2.0, c.morphSeconds, 0.0)
        assertEquals(0.8, c.undoMorphSeconds, 0.0)
        assertEquals(0.05, c.pushInterval, 0.0)
        assertTrue(c.scout)
        assertEquals(3u, c.scoutCandidates)
        assertEquals(24.0, c.scoutSeconds, 0.0)
        assertEquals(8000.0, c.scoutSampleRate, 0.0)
        assertEquals(0u, c.scoutThreads)
    }
}
