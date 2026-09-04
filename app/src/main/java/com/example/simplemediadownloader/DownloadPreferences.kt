package com.example.simplemediadownloader

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class DefaultDownloadChoice(
    val label: String,
    val actionLabel: String,
) {
    ALWAYS_ASK("Always ask for quality", "Choose quality"),
    BEST_VIDEO("Best video", "Download best video"),
    VIDEO_1080("1080p", "Download 1080p"),
    VIDEO_720("720p", "Download 720p"),
    VIDEO_480("480p", "Download 480p"),
    ORIGINAL_AUDIO("Original audio", "Download audio"),
    MP3("MP3", "Download MP3"),
    ;

    val targetHeight: Int?
        get() = when (this) {
            VIDEO_1080 -> 1080
            VIDEO_720 -> 720
            VIDEO_480 -> 480
            else -> null
        }

    companion object {
        fun fromStored(value: String?): DefaultDownloadChoice =
            entries.firstOrNull { it.name == value } ?: BEST_VIDEO
    }
}

interface DownloadPreferenceStore {
    val defaultChoice: StateFlow<DefaultDownloadChoice>
    suspend fun setDefaultChoice(choice: DefaultDownloadChoice)
}

class SharedPreferencesDownloadPreferenceStore(
    context: Context,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : DownloadPreferenceStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _defaultChoice = MutableStateFlow(
        DefaultDownloadChoice.fromStored(preferences.getString(KEY_DEFAULT_CHOICE, null)),
    )
    override val defaultChoice: StateFlow<DefaultDownloadChoice> = _defaultChoice.asStateFlow()

    @SuppressLint("UseKtx") // commit() reports persistence failures; edit(commit = true) does not.
    override suspend fun setDefaultChoice(choice: DefaultDownloadChoice) {
        withContext(dispatchers.io) {
            check(
                preferences.edit()
                    .putString(KEY_DEFAULT_CHOICE, choice.name)
                    .commit(),
            ) { "Could not save the default download choice." }
            _defaultChoice.value = choice
        }
    }

    companion object {
        internal const val PREFERENCES_NAME = "download_preferences"
        private const val KEY_DEFAULT_CHOICE = "default_download_choice"
    }
}

internal object DefaultDownloadChoiceMapper {
    fun select(
        choice: DefaultDownloadChoice,
        catalog: MediaFormatCatalog,
        bestVideoFallback: AvailableFormat,
    ): AvailableFormat? = when (choice) {
        DefaultDownloadChoice.ALWAYS_ASK -> null
        DefaultDownloadChoice.BEST_VIDEO -> bestVideo(catalog) ?: bestVideoFallback
        DefaultDownloadChoice.VIDEO_1080,
        DefaultDownloadChoice.VIDEO_720,
        DefaultDownloadChoice.VIDEO_480 -> videoAtOrBelow(
            catalog,
            requireNotNull(choice.targetHeight),
        )
        DefaultDownloadChoice.ORIGINAL_AUDIO -> catalog.audioFormats
            .asSequence()
            .filter { it.mode == DownloadMode.AUDIO_ORIGINAL }
            .maxWithOrNull(
                compareBy<AvailableFormat> { it.bitrateKbps }
                    .thenBy { it.estimatedSizeBytes ?: 0L },
            )
        DefaultDownloadChoice.MP3 -> catalog.audioFormats
            .asSequence()
            .filter { it.mode == DownloadMode.AUDIO_MP3 }
            .minWithOrNull(
                compareBy<AvailableFormat> { abs(it.bitrateKbps - DEFAULT_MP3_BITRATE) }
                    .thenByDescending { it.bitrateKbps },
            )
    }

    private fun bestVideo(catalog: MediaFormatCatalog): AvailableFormat? =
        catalog.videoFormats
            .asSequence()
            .maxWithOrNull(videoQualityComparator)

    private fun videoAtOrBelow(catalog: MediaFormatCatalog, targetHeight: Int): AvailableFormat? =
        catalog.videoFormats
            .asSequence()
            .filter { it.height in 1..targetHeight }
            .maxWithOrNull(videoQualityComparator)
            ?: catalog.videoFormats.firstOrNull {
                it.isQuickPreset && it.height == targetHeight
            }

    private val videoQualityComparator = compareBy<AvailableFormat> { it.height }
        .thenBy { it.width }
        .thenBy { it.fps }
        .thenBy { it.bitrateKbps }

    private const val DEFAULT_MP3_BITRATE = 192
}
