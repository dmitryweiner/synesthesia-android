package io.github.dmitryweiner.synesthesia

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
    fun theBuiltInPointsAreListed() {
        compose.onNodeWithText("Fractal garden").assertExists()
        compose.onNodeWithText("12 built-in points", substring = true).assertExists()
    }
}
