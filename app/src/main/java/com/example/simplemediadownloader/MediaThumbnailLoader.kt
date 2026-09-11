package com.example.simplemediadownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 16).coerceIn(4096, 32768) // KB
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun getCachedThumbnail(key: String): Bitmap? = memoryCache.get(key)

    suspend fun loadThumbnail(
        context: Context,
        contentUriString: String,
        isVideo: Boolean,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cached = memoryCache.get(contentUriString)
        if (cached != null) return@withContext cached

        val uri = runCatching { contentUriString.toUri() }.getOrNull() ?: return@withContext null
        val bitmap = runCatching {
            if (isVideo) {
                loadVideoThumbnail(context, uri)
            } else {
                loadAudioThumbnail(context, uri)
            }
        }.getOrNull()

        if (bitmap != null) {
            memoryCache.put(contentUriString, bitmap)
        }
        bitmap
    }

    private fun loadVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(512, 384), null)
            } catch (_: Exception) {}
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadAudioThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val pictureBytes = retriever.embeddedPicture
            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, boundsOptions)
                var sampleSize = 1
                while (boundsOptions.outWidth / (sampleSize * 2) >= 300 && boundsOptions.outHeight / (sampleSize * 2) >= 300) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, decodeOptions)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}
