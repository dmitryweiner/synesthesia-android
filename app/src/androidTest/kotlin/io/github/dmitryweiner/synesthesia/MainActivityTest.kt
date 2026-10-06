package io.github.dmitryweiner.synesthesia

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dmitryweiner.synesthesia.core.PointEdit
import io.github.dmitryweiner.synesthesia.core.SettingsTab
import io.github.dmitryweiner.synesthesia.core.settingsPage
import io.github.dmitryweiner.synesthesia.ui.REPOSITORY
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app starts on a device, shows the points the core holds, and steers. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** One session per process: start from the first point, as [SearchTest] does. */
    @Before
    fun startFromTheFirstPoint() {
        val app = compose.activity.application as SynesthesiaApp
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.playback.stop()
            app.playback.select(0)
        }
        // The welcome is shown once per install; whichever test runs first
        // meets it, so every test closes it if it is there.
        val welcome = compose.onAllNodesWithTag("welcome")
        if (welcome.fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Start listening").performClick()
        }
    }

    @Test
    fun theFirstRunSaysWhatToPressFirst() {
        val app = compose.activity.application as SynesthesiaApp
        // Whatever the @Before found, the welcome has been shown by now and
        // does not come back.
        app.playback.files.rememberWelcomeShown()
        compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().let {
            assertEquals("the welcome is shown once", 0, it.size)
        }
        // And what it says is where the sound comes from.
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openHelp").performClick()
        compose.onNodeWithText("▶ Play starts the sound", substring = true).assertExists()
        compose.onNodeWithText("Got it").performClick()
    }

    @Test
    fun aSectionInSettingsSaysWhatTheThingIsAndWhereToReadMore() {
        compose.onNodeWithTag("openSettings").performClick()
        // The schema's own words about a formula, in a tooltip behind the (?),
        // with the Wikipedia article about the thing where the core has one.
        compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("explain:a.additive"))
        compose.onNodeWithTag("explain:a.additive").performClick()
        compose.onNodeWithTag("about:a.additive").assertIsDisplayed()
        compose.onNodeWithTag("read:a.additive").assertIsDisplayed()
        compose.onNodeWithTag("closeSettings").performClick()
    }

    @Test
    fun theFirstPointIsShownAndCanBePlayed() {
        // The current point's name is in the header and in the list.
        compose.onAllNodesWithText("Fractal garden").onFirst().assertExists()
        compose.onNodeWithText("▶ Play").assertExists()
    }

    @Test
    fun aPointIsKeptUnderANameAndOpensFromTheSheet() {
        val app = compose.activity.application as SynesthesiaApp
        val playback = app.playback
        val before = playback.points.count()

        // 💾 offers a name and keeps the point under it.
        compose.onNodeWithTag("keepPoint").performClick()
        compose.onNodeWithTag("name").assertExists()
        compose.onNodeWithTag("keep").performClick()
        assertEquals(before + 1u, playback.points.count())
        val kept = playback.points.nameAt(before) ?: error("the kept point")
        // The title is the name it was kept under.
        compose.onAllNodesWithText(kept).onFirst().assertExists()

        // It is in the sheet, under "My points", and opens from there. The
        // rows carry a tag: the kept name is on the title too, and the two
        // Forget buttons — the row's and the dialog's — say the same word.
        // The header's own button, which the review asked to have back.
        compose.onNodeWithTag("points").performClick()
        compose.onNodeWithText("My points").assertExists()
        compose.onAllNodesWithTag("keptPoint").onFirst().performClick()
        compose.onAllNodesWithText(kept).onFirst().assertExists()

        // And it can be forgotten again.
        compose.onNodeWithTag("openPoints").performClick()
        compose.onAllNodesWithTag("forget").onFirst().performClick()
        compose.onNodeWithTag("forgetConfirm").performClick()
        assertEquals(before, playback.points.count())
    }

    @Test
    fun theHeaderShowsTheAppsMarkAndTheMenuItsVersion() {
        compose.onNodeWithTag("logo").assertIsDisplayed()
        // The version is reference, not decoration: it is in the menu, and it
        // says which build and which core — the two numbers a report needs.
        compose.onNodeWithText("core", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("version").assertIsDisplayed()
        compose.onNodeWithTag("version").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        // Tapping it leaves the app for the repository the build came from,
        // which is where anything about it is reported. The press itself is
        // not made here: it would hand the test to a browser.
        assertEquals("https://github.com/dmitryweiner/synesthesia-android/", REPOSITORY)
    }

    @Test
    fun theDetailsSayWhatThePointIsAndWhatTheLastPressChanged() {
        compose.onNodeWithTag("like").performClick()
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openDetails").performClick()
        // The half of the status the main screen does not show.
        compose.onNodeWithTag("changed").assertIsDisplayed()
        // The rest is below the fold of a sheet, so it is scrolled to rather
        // than assumed composed. Which sections are listed depends on what
        // the press left switched on, so the heading is what is asserted.
        compose.onNodeWithTag("detailsList").performScrollToNode(hasText("What is switched on"))
        compose.onNodeWithText("What is switched on").assertIsDisplayed()
    }

    @Test
    fun theHelpSaysWhatThePressesDo() {
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("openHelp").performClick()
        compose.onNodeWithText("Synesthesia").assertExists()
        compose.onNodeWithText("more of this", substring = true).assertExists()
        compose.onNodeWithText("Got it").performClick()
    }

    @Test
    fun thePointsNameHasALineOfItsOwn() {
        // It is the longest thing on the screen; sharing a line with the
        // buttons left "Candle glaze · 3 steps" as "Cand…".
        val name = compose.onNodeWithTag("openPoints")
        name.assertIsDisplayed()
        val width = name.fetchSemanticsNode().size.width
        val controls = compose.onNodeWithTag("points").fetchSemanticsNode().size.width
        assertTrue("the name has the width to itself: $width vs a button's $controls", width > 4 * controls)
    }

    @Test
    fun theSwitchUnderTheBlockSwapsThePictureAndTheSpectrum() {
        // It offers the other one, which is what a switch should say.
        compose.onNodeWithTag("viewMode").assertTextEquals("Spectrum")
        compose.onNodeWithTag("picture").assertExists()

        compose.onNodeWithTag("viewMode").performClick()
        compose.onNodeWithTag("viewMode").assertTextEquals("Picture")
        // The spectrum takes the picture's place rather than sharing it.
        compose.onNodeWithTag("picture").assertDoesNotExist()

        compose.onNodeWithTag("viewMode").performClick()
        compose.onNodeWithTag("picture").assertExists()
    }

    @Test
    fun theWayIntoFullScreenSaysWhatItIs() {
        // The button is a glyph, large enough to read; what it means is in its
        // description, which is what TalkBack reads out.
        compose.onNodeWithContentDescription("Fill the screen with the picture").assertIsDisplayed()
    }

    @Test
    fun fullScreenKeepsThePressesAndComesBackWhereItCameFrom() {
        // From the spectrum, so that Close has somewhere of its own to return.
        compose.onNodeWithTag("viewMode").performClick()
        compose.onNodeWithTag("picture").assertDoesNotExist()

        compose.onNodeWithTag("fullScreen").performClick()
        // The picture is all there is, and the presses stay with it.
        compose.onNodeWithTag("picture").assertExists()
        compose.onNodeWithTag("like").assertIsDisplayed()
        compose.onNodeWithTag("play").assertDoesNotExist()
        compose.onNodeWithTag("points").assertDoesNotExist()

        compose.onNodeWithTag("closeFull").performClick()
        // Back to the spectrum, not to whatever is first.
        compose.onNodeWithTag("picture").assertDoesNotExist()
        compose.onNodeWithTag("play").assertIsDisplayed()
        compose.onNodeWithTag("viewMode").performClick()
    }

    @Test
    fun theTokensAreUnderTheHeadersMenu() {
        compose.onNodeWithTag("more").performClick()
        compose.onNodeWithTag("copyToken").assertIsDisplayed()
        compose.onNodeWithTag("pasteToken").assertIsDisplayed()
        compose.onNodeWithTag("copyToken").performClick()
        compose.onNodeWithText("The point is on the clipboard, as a #s= token").assertExists()
    }

    @Test
    fun aPressDoesNotMoveWhatIsAboveIt() {
        // The status line keeps its height whatever it says — the text is as
        // wide as its words, which is fine; it is the height that pushed the
        // picture up and down on every press.
        val line = compose.onNodeWithTag("status").fetchSemanticsNode().size.height
        val picture = compose.onNodeWithTag("picture").fetchSemanticsNode().size
        compose.onNodeWithTag("like").performClick()
        compose.onNodeWithTag("surprise").performClick()
        assertEquals(line, compose.onNodeWithTag("status").fetchSemanticsNode().size.height)
        assertEquals(picture, compose.onNodeWithTag("picture").fetchSemanticsNode().size)
    }

    @Test
    fun settingsEditAPointAndClosingIsOneUndoableStep() {
        val playback = (compose.activity.application as SynesthesiaApp).playback

        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithText("Settings").assertExists()
        // The page is generated: the tabs and the sections come from the
        // schema, and so does every control in them.
        compose.onNodeWithTag("tabSound").assertExists()
        compose.onNodeWithTag("tabPicture").assertExists()
        compose.onNodeWithTag("volume").assertExists()
        // The picture stops while the page is open (PLAN.md phase 5).
        assertTrue(playback.state.value.settingsOpen)

        // A section opens and its controls are there; one of them is the
        // delay's shimmer, which nothing in this app was written for.
        compose.onNodeWithTag("tabSound").performClick()
        compose.onNodeWithText("▸  Delay").performClick()
        compose.onNodeWithTag("fx.delayShimmer").assertExists()
        compose.onNodeWithTag("fx.delayOn").assertExists()

        // Changing something and closing is one undoable step. The slider is
        // set through its own semantics rather than by a swipe: a gesture's
        // landing value is the layout's business, and this test is about the
        // edit reaching the point.
        val steps = playback.state.value.session.steps
        compose.onNodeWithTag("fx.delayShimmer")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        compose.onNodeWithTag("closeSettings").performClick()
        assertFalse(playback.state.value.settingsOpen)
        assertEquals(steps + 1u, playback.state.value.session.steps)
        assertTrue(playback.state.value.session.canUndo)
        assertTrue(playback.state.value.session.status.contains("Settings"))
    }

    @Test
    fun eitherHalfOfThePointCanBeStartedAgainFromNothing() {
        val playback = (compose.activity.application as SynesthesiaApp).playback
        // Which gene belongs to which tab is the core's business, so the
        // test asks it rather than naming parameters of its own.
        val page = settingsPage()
        val sound = requireNotNull(page.first { it.tab == SettingsTab.SOUND && it.toggle != null }.toggle)
        val picture = requireNotNull(page.first { it.tab == SettingsTab.PICTURE && it.toggle != null }.toggle)
        val before = PointEdit(playback.pointJson()).use { it.value(sound.id) to it.value(picture.id) }

        compose.onNodeWithTag("openSettings").performClick()
        compose.onNodeWithTag("tabPicture").performClick()
        compose.onNodeWithTag("resetTab").performClick()
        compose.onNodeWithTag("closeSettings").performClick()

        PointEdit(playback.pointJson()).use {
            assertEquals("the picture starts again", 0.0, it.value(picture.id), 0.0)
            assertEquals("and the sound is left alone", before.first, it.value(sound.id), 0.0)
        }
        // It is an edit like any other: one step, and ↩ takes it back.
        assertTrue(playback.state.value.session.canUndo)
        compose.onNodeWithTag("undo").performClick()
        PointEdit(playback.pointJson()).use {
            assertEquals("the picture comes back", before.second, it.value(picture.id), 0.0)
        }
    }

    @Test
    fun thePressesAreOnTheScreenAndAPressNamesWhatItDid() {
        for (tag in listOf("dislike", "like", "surprise", "undo")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("like").performClick()
        // The title carries the step count, and the status line says why.
        compose.onNodeWithText("Fractal garden · 1 step").assertExists()
        compose.onNodeWithTag("status").assertIsDisplayed()
        // ↩ was dead until there was something to undo; the step count goes.
        compose.onNodeWithTag("undo").performClick()
        compose.onAllNodesWithText("Fractal garden").onFirst().assertExists()
        compose.onNodeWithText("Fractal garden · 1 step").assertDoesNotExist()
    }
}
