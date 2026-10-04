package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings page as the app receives it: generated from the schema, with
 * every control the app needs to draw one — and nothing it would have to know
 * about the parameters themselves.
 */
class SettingsBindingsTest {
    private val page = settingsPage()

    private fun section(id: String) = page.first { it.id == id }

    private fun control(id: String): Control =
        page.flatMap { it.controls + listOfNotNull(it.toggle) }.first { it.id == id }

    @Test
    fun thePageArrivesWithBothTabsAndItsOwnTitles() {
        assertTrue(page.isNotEmpty())
        assertTrue(page.any { it.tab == SettingsTab.SOUND })
        assertTrue(page.any { it.tab == SettingsTab.PICTURE })
        // Titles the schema holds: an FX module's switch, a formula's enable
        // gene, a card's title.
        assertEquals("Filter", section("filterOn").title)
        assertEquals("Harmonic Sum", section("a.additive").title)
        assertEquals("Reaction", section("v.reaction").title)
        assertEquals("Route 1", section("route.0").title)
        // Nothing is shown twice, and nothing is unreachable.
        val ids = page.flatMap { it.controls + listOfNotNull(it.toggle) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("a point has hundreds of parameters: ${ids.size}", ids.size > 200)
    }

    @Test
    fun aControlCarriesWhatItTakesToDrawIt() {
        val cutoff = control("fx.filterFreq")
        assertEquals(ControlKind.SLIDER, cutoff.kind)
        assertTrue("a frequency moves in octaves", cutoff.exp)
        assertTrue(cutoff.min > 0.0 && cutoff.max > cutoff.min)
        assertEquals("fx.filterOn", cutoff.activeIf)
        assertEquals("Filter cutoff", cutoff.shortLabel)

        val type = control("fx.filterType")
        assertEquals(ControlKind.CHOICE, type.kind)
        assertEquals((type.max - type.min).toInt() + 1, type.options.size)
        assertTrue(type.options.contains("lowpass"))

        assertEquals(ControlKind.SWITCH, control("fx.filterOn").kind)
        // Under "Reaction", "Reaction · Feed" reads as "Feed".
        assertEquals("Feed", control("v.reaction.feed").shortLabel)
    }

    @Test
    fun theNewestParametersAreOnThePageWithNothingWrittenForThem() {
        assertEquals("Tanpura", section("a.tanpura").title)
        assertTrue(section("a.tanpura").controls.any { it.shortLabel == "Jawari" })
        assertTrue(section("delayOn").controls.any { it.id == "fx.delayShimmer" })
        assertTrue(control("lfo.0.shape").options.contains("pink"))
    }

    @Test
    fun anEditMovesOneControlAndTheRestStandStill() {
        PointEdit(requireNotNull(presetStateJson(0u))).use { edit ->
            val before = edit.pointJson()
            edit.setValue("fx.filterFreq", 440.0)
            assertEquals(440.0, edit.value("fx.filterFreq"), 1.0)
            val after = edit.pointJson()
            assertNotEquals(before, after)
            // The volume is not a gene and survives an edit elsewhere.
            val gain = edit.masterGain()
            edit.setValue("v.reaction.feed", 0.05)
            assertEquals(gain, edit.masterGain(), 0.0)
            edit.setMasterGain(0.3)
            assertEquals(0.3, edit.masterGain(), 0.0)
            // What comes out is a point the sound can play.
            SoundPlayer(22_050u, edit.pointJson()).use { player ->
                player.fadeIn()
                assertEquals(256 * 4, player.render(256u).size)
            }
        }
    }

    @Test
    fun aSwitchSaysWhatItGates() {
        PointEdit(requireNotNull(presetStateJson(0u))).use { edit ->
            edit.setValue("fx.filterOn", 1.0)
            assertTrue(edit.isActive("fx.filterFreq"))
            edit.setValue("fx.filterOn", 0.0)
            assertFalse("an off module's controls do nothing", edit.isActive("fx.filterFreq"))
            assertTrue("but keep their values", edit.value("fx.filterFreq") > 0.0)
        }
    }

    @Test
    fun closingSettingsIsOneUndoableStepAndNoChangeIsNoStep() {
        val config = defaultSessionConfig().copy(scout = false)
        Session.onPreset(0u, config).use { session ->
            session.openSettings(0.0)
            PointEdit(session.pointJson()).use { edit ->
                // Nothing touched: no step.
                session.closeSettings(0.1, edit.pointJson())
                assertFalse(session.view().canUndo)
                assertEquals(0u, session.view().steps)

                session.openSettings(0.2)
                edit.setValue("v.reaction.feed", 0.05)
                val effects = session.closeSettings(0.3, edit.pointJson())
                assertTrue(effects.any { it is SessionEffect.SetPoint })
                assertTrue("one undoable step", session.view().canUndo)
                assertEquals(1u, session.view().steps)
                assertTrue(session.view().status.contains("Settings"))
            }
        }
    }
}
