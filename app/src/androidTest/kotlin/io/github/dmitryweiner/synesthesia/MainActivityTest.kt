package io.github.dmitryweiner.synesthesia

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    }

    @Test
    fun theFirstPointIsShownAndCanBePlayed() {
        // The current point's name is in the header and in the list.
        compose.onAllNodesWithText("Fractal garden").onFirst().assertExists()
        compose.onNodeWithText("▶ Play").assertExists()
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
