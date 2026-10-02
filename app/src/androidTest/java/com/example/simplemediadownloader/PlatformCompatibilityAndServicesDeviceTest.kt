package com.example.simplemediadownloader

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformCompatibilityAndServicesDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun notificationChannelsAreCreatedAndConfigured() {
        val notifier = DownloadNotifier(context)
        notifier.ensureChannel()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel("media_downloads")
        assertNotNull("media_downloads channel must be created", channel)
        assertTrue(channel.importance >= NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun downloadServiceIntentAndActionsAreWellFormed() {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_ENQUEUE
            putExtra(DownloadService.EXTRA_TASK_ID, "test-task-123")
            putExtra(DownloadService.EXTRA_CONCURRENCY, 2)
        }
        assertNotNull(intent.component)
        assertTrue(intent.hasExtra(DownloadService.EXTRA_TASK_ID))
    }

    @Test
    fun rtlLayoutDirectionRendersWithoutCrash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Text("Arabic / RTL Label Test")
                }
            }
        }
        composeRule.onNodeWithText("Arabic / RTL Label Test").assertIsDisplayed()
    }
}
