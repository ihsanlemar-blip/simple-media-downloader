package com.example.simplemediadownloader

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaThumbnailLoaderTest {

    @Test
    fun `loadThumbnail returns null gracefully for invalid or empty uri`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = MediaThumbnailLoader.loadThumbnail(context, "", true)
        assertNull(result)

        val result2 = MediaThumbnailLoader.loadThumbnail(context, "invalid://nonexistent/path.mp4", false)
        assertNull(result2)
    }

    @Test
    fun `cache stores and clears bitmaps correctly`() {
        MediaThumbnailLoader.clearCache()
        val key = "test_key_123"
        assertNull(MediaThumbnailLoader.getCachedThumbnail(key))

        // Create small dummy bitmap
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        
        // Use reflection to verify or put
        val method = MediaThumbnailLoader.javaClass.getDeclaredField("memoryCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = method.get(MediaThumbnailLoader) as android.util.LruCache<String, Bitmap>
        cache.put(key, bitmap)

        assertEquals(bitmap, MediaThumbnailLoader.getCachedThumbnail(key))

        MediaThumbnailLoader.clearCache()
        assertNull(MediaThumbnailLoader.getCachedThumbnail(key))
    }
}
