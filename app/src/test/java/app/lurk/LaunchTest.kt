package app.lurk

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Starts the app and draws the first screen, to catch crashes on launch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LaunchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToHomeFeed() {
        compose.waitForIdle()
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Hot").assertExists()
    }
}
