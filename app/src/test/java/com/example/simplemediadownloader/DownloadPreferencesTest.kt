package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadPreferencesTest {
    private val progressive720 = video("muxed-720", 720)
    private val progressive480 = video("muxed-480", 480)
    private val separate1080 = video("separate-1080", 1080, companionAudio = "audio")
    private val originalAudio = audio("original", DownloadMode.AUDIO_ORIGINAL, 160)
    private val mp3 = audio("mp3", DownloadMode.AUDIO_MP3, 192)
    private val fallback = video("fallback", 0)
    private val catalog = MediaFormatCatalog(
        sourceUrl = "https://example.test/watch?v=1",
        title = "Example",
        videoFormats = listOf(separate1080, progressive480, progressive720),
        audioFormats = listOf(originalAudio, mp3),
    )

    @Before
    fun clearPreferences() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(
                SharedPreferencesDownloadPreferenceStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `stored values map safely and unknown values retain the default`() {
        DefaultDownloadChoice.entries.forEach { choice ->
            assertEquals(choice, DefaultDownloadChoice.fromStored(choice.name))
        }
        assertEquals(DefaultDownloadChoice.BEST_VIDEO, DefaultDownloadChoice.fromStored(null))
        assertEquals(DefaultDownloadChoice.BEST_VIDEO, DefaultDownloadChoice.fromStored("future-value"))
    }

    @Test
    fun `video preferences select highest quality stream including adaptive streams`() {
        assertSame(
            separate1080,
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.BEST_VIDEO,
                catalog,
                fallback,
            ),
        )
        assertSame(
            separate1080,
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.VIDEO_1080,
                catalog,
                fallback,
            ),
        )
        assertSame(
            progressive720,
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.VIDEO_720,
                catalog,
                fallback,
            ),
        )
        assertSame(
            progressive480,
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.VIDEO_480,
                catalog,
                fallback,
            ),
        )
        org.junit.Assert.assertTrue(separate1080.requiresFfmpeg)
    }

    @Test
    fun `audio and always ask preferences map to their intended action`() {
        assertSame(
            originalAudio,
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.ORIGINAL_AUDIO,
                catalog,
                fallback,
            ),
        )
        assertSame(
            mp3,
            DefaultDownloadChoiceMapper.select(DefaultDownloadChoice.MP3, catalog, fallback),
        )
        assertNull(
            DefaultDownloadChoiceMapper.select(
                DefaultDownloadChoice.ALWAYS_ASK,
                catalog,
                fallback,
            ),
        )
    }

    @Test
    fun `default choice survives store recreation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = AppDispatchers(io = Dispatchers.Unconfined)
        val firstStore = SharedPreferencesDownloadPreferenceStore(context, dispatchers)

        firstStore.setDefaultChoice(DefaultDownloadChoice.VIDEO_720)

        val recreatedStore = SharedPreferencesDownloadPreferenceStore(context, dispatchers)
        assertEquals(DefaultDownloadChoice.VIDEO_720, recreatedStore.defaultChoice.value)
    }

    @Test
    fun `theme mode values map safely and unknown values fallback to system`() {
        AppThemeMode.entries.forEach { mode ->
            assertEquals(mode, AppThemeMode.fromStored(mode.name))
        }
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStored("invalid_theme"))
    }

    @Test
    fun `theme mode survives store recreation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatchers = AppDispatchers(io = Dispatchers.Unconfined)
        val firstStore = SharedPreferencesDownloadPreferenceStore(context, dispatchers)

        firstStore.setThemeMode(AppThemeMode.AMOLED_DARK)

        val recreatedStore = SharedPreferencesDownloadPreferenceStore(context, dispatchers)
        assertEquals(AppThemeMode.AMOLED_DARK, recreatedStore.themeMode.value)
    }

    private fun video(key: String, height: Int, companionAudio: String? = null) = AvailableFormat(
        key = key,
        mode = DownloadMode.VIDEO,
        formatId = key,
        companionAudioFormatId = companionAudio,
        extension = "mp4",
        height = height,
    )

    private fun audio(key: String, mode: DownloadMode, bitrate: Int) = AvailableFormat(
        key = key,
        mode = mode,
        formatId = key,
        extension = if (mode == DownloadMode.AUDIO_MP3) "mp3" else "m4a",
        bitrateKbps = bitrate,
    )
}
