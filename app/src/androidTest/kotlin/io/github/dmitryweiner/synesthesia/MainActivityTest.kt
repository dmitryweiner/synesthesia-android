package io.github.dmitryweiner.synesthesia

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app starts on a device and shows the points the core holds. */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFirstPointIsShownAndCanBePlayed() {
        // The current point's name is in the header and in the list.
        compose.onAllNodesWithText("Fractal garden").onFirst().assertExists()
        compose.onNodeWithText("▶ Play").assertExists()
    }
}
