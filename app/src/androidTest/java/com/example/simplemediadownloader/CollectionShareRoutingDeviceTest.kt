package com.example.simplemediadownloader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test

class CollectionShareRoutingDeviceTest {
    @Test fun collectionPreviewSurvivesRemovalOfIsolatedShareTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Profile enumeration is deliberately unsupported; this exercises routing without live media.
        val source = "https://www.instagram.com/batch_fixture_${System.nanoTime()}/"
        val intent = Intent(context, ShareDownloadActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, source)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var preview: BatchActivity? = null
        try {
            ActivityScenario.launch<ShareDownloadActivity>(intent).use {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (preview == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        preview = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<BatchActivity>().firstOrNull()
                    }
                    if (preview == null) Thread.sleep(50)
                }
                assertNotNull("Batch preview must remain visible after the share task is removed", preview)
                instrumentation.runOnMainSync { assertFalse(preview!!.isFinishing) }
            }
        } finally {
            instrumentation.runOnMainSync { preview?.finish() }
            runBlocking {
                val batches = (context.applicationContext as SimpleMediaDownloaderApp).batchRepository
                batches.batches.first().filter { it.sourceUrl == source }.forEach { batches.deleteHistory(it.batchId) }
            }
        }
    }
}
