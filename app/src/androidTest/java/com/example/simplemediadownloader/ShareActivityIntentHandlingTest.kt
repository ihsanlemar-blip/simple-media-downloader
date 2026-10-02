package com.example.simplemediadownloader

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareActivityIntentHandlingTest {

    @Test
    fun shareActivityHandlesValidTextIntentGracefully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ShareDownloadActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        }

        val scenario = ActivityScenario.launch<ShareDownloadActivity>(intent)
        assertNotNull(scenario)
        scenario.onActivity { activity ->
            assertNotNull(activity)
        }
        scenario.close()
    }

    @Test
    fun shareActivityHandlesEmptyIntentWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, ShareDownloadActivity::class.java)

        val scenario = ActivityScenario.launch<ShareDownloadActivity>(intent)
        assertNotNull(scenario)
        scenario.close()
    }
}
