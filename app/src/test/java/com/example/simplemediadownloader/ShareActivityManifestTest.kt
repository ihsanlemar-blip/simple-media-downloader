package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareActivityManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `text shares resolve to only the compact share activity`() {
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).filter { it.activityInfo.packageName == context.packageName }

        assertEquals(1, matches.size)
        assertEquals(ShareDownloadActivity::class.java.name, matches.single().activityInfo.name)
    }

    @Test
    fun `share activity is excluded from recents while main remains the launcher`() {
        val shareInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, ShareDownloadActivity::class.java),
            0,
        )
        val mainInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )

        assertTrue(shareInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(mainInfo.exported)
        assertFalse(mainInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}
