package com.example.simplemediadownloader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class AppStartupAndNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsAndNavigatesBetweenAllMainTabs() {
        // App launches on Gateway/Link tab
        composeRule.onNodeWithText("Link", substring = true).assertIsDisplayed()

        // Navigate to Tasks / Transfers tab
        composeRule.onNodeWithText("Tasks", substring = true).performClick()
        composeRule.waitForIdle()

        // Navigate to Vault tab
        composeRule.onNodeWithText("Vault", substring = true).performClick()
        composeRule.waitForIdle()

        // Navigate to Config / Settings tab
        composeRule.onNodeWithText("Config", substring = true).performClick()
        composeRule.waitForIdle()

        // Navigate back to Link tab
        composeRule.onNodeWithText("Link", substring = true).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun urlInputFieldAcceptsInputWithoutCrash() {
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        composeRule.onNodeWithText("Paste or type a supported link…", substring = true)
            .assertIsDisplayed()
            .performClick()
            .performTextInput(testUrl)

        composeRule.waitForIdle()
    }
}
